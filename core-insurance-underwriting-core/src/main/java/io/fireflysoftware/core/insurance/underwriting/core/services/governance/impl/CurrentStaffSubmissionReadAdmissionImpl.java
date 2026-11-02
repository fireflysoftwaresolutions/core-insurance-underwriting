package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteTenantBindingResolver;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.UnderwritingGovernanceValues;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import org.fireflyframework.security.spi.*;
import reactor.core.publisher.Mono;

/** Current read-only authority for one persisted submission; no action, intent or write admission exists here. */
public final class CurrentStaffSubmissionReadAdmissionImpl {
    public enum Purpose { READ_SUBMISSION }
    public record Profile(String issuer,String audience,String allowedClientId,String platformTenant,UUID resourceTenantId,String canonicalStaffRealm){
        public Profile {
            UnderwritingGovernanceValues.text(issuer,500);UnderwritingGovernanceValues.text(audience,255);
            UnderwritingGovernanceValues.text(allowedClientId,255);UnderwritingGovernanceValues.text(platformTenant,1024);
            UnderwritingGovernanceValues.id(resourceTenantId);UnderwritingGovernanceValues.text(canonicalStaffRealm,255);
        }
    }
    public final class Permit {
        private final ExecutionImpl execution;
        private Permit(ExecutionImpl execution){this.execution=execution;}
        public Purpose purpose(){return Purpose.READ_SUBMISSION;}
        public UUID submissionId(){return execution.submissionId;}
        public UUID resourceTenantId(){return execution.binding.resourceTenantId();}
        public Duration remaining(){return CurrentStaffSubmissionReadAdmissionImpl.this.remaining(execution);}
        public R2dbcOperationBudget operationBudget(){
            remaining();Instant until=execution.until.isBefore(execution.binding.validUntil())?execution.until:execution.binding.validUntil();
            return R2dbcOperationBudget.observed(execution.observedAt,until,execution.observedNanos,Duration.ofSeconds(5),clock,nanos);
        }
        @Override public String toString(){return "StaffSubmissionReadPermit[redacted]";}
    }
    private static final Set<String> READ_SCOPE=Set.of("insurance:underwriting:submissions:read");
    private final SecurityContextPort security;
    private final PolicyEvaluationPort policies;
    private final QuoteTenantBindingResolver bindings;
    private final Profile profile;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Object executionKey=new Object();

    public CurrentStaffSubmissionReadAdmissionImpl(SecurityContextPort security,PolicyEvaluationPort policies,
            QuoteTenantBindingResolver bindings,Profile profile,Clock clock,LongSupplier nanos){
        this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);
        this.bindings=Objects.requireNonNull(bindings);this.profile=Objects.requireNonNull(profile);
        this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }
    public <T> Mono<T> read(UUID submissionId,Function<Permit,Mono<T>> select){
        return Mono.deferContextual(context->{
            UnderwritingGovernanceValues.id(submissionId);Objects.requireNonNull(select);
            if(context.hasKey(executionKey))return Mono.error(denied());
            Instant observed=clock.instant();long before=nanos.getAsLong();
            return Mono.defer(security::currentPrincipal).timeout(Duration.ofSeconds(5)).onErrorMap(java.util.concurrent.TimeoutException.class,error->denied()).switchIfEmpty(Mono.error(denied())).flatMap(principal->{
                requirePrincipal(principal);
                Instant expiry=principal.claim("exp",Instant.class),until=expiry.isBefore(observed.plusSeconds(5))?expiry:observed.plusSeconds(5);
                var execution=new ExecutionImpl(submissionId,principal,observed,before,until);
                return Mono.using(()->execution,held->currentBinding(held).flatMap(binding->{
                    held.binding=binding;held.permit=new Permit(held);
                    return evaluate(held,"authorize").then(Mono.defer(()->select.apply(held.permit)))
                        .switchIfEmpty(Mono.error(denied())).flatMap(value->evaluate(held,"response").thenReturn(value));
                }).timeout(remaining(held)).onErrorMap(java.util.concurrent.TimeoutException.class,error->denied())
                    .contextWrite(owned->owned.put(executionKey,held)),held->held.active.set(false),true);
            });
        });
    }
    public Mono<Void> revalidate(Permit permit){
        return Mono.deferContextual(context->{
            Object current=context.getOrDefault(executionKey,null);
            if(!(current instanceof ExecutionImpl execution)||!execution.active.get()||execution.permit!=permit)return Mono.error(denied());
            return principal(execution).then(currentBinding(execution))
                .filter(execution.binding::sameDefinition).switchIfEmpty(Mono.error(denied())).then();
        });
    }
    private Mono<Void> evaluate(ExecutionImpl execution,String role){
        return principal(execution).then(currentBinding(execution)).filter(execution.binding::sameDefinition)
            .switchIfEmpty(Mono.error(denied())).flatMap(binding->{
                var facts=new LinkedHashMap<String,Object>();
                facts.put("purpose",Purpose.READ_SUBMISSION.name());facts.put("role",role);
                facts.put("resourceTenantId",binding.resourceTenantId());facts.put("submissionId",execution.submissionId);
                facts.put("bindingId",binding.bindingId());facts.put("bindingVersion",binding.version());facts.put("bindingDigest",binding.definitionDigest());
                facts.put("staffRealm",profile.canonicalStaffRealm());facts.put("staffId",staffId(execution.principal));
                return Mono.defer(()->policies.evaluate(execution.principal,"insurance.underwriting.submission.read",
                    "urn:firefly:insurance:underwriting:tenant:"+binding.resourceTenantId()+":submission:"+execution.submissionId,Map.copyOf(facts)))
                    .filter(value->value.decision().granted()&&value.decision().obligations().isEmpty()&&value.provenance().isPresent())
                    .switchIfEmpty(Mono.error(denied())).flatMap(value->principal(execution)).then();
            }).timeout(remaining(execution)).onErrorMap(error->denied());
    }
    private Mono<QuoteTenantBindingResolver.Observation> currentBinding(ExecutionImpl execution){
        return principal(execution).flatMap(principal->Mono.defer(()->bindings.resolve(principal,profile.resourceTenantId(),
            execution.original,execution.until,()->remaining(execution))))
            .filter(binding->profile.issuer().equals(binding.issuer())&&profile.platformTenant().equals(binding.platformTenant())
                &&profile.resourceTenantId().equals(binding.resourceTenantId()))
            .switchIfEmpty(Mono.error(denied())).doOnNext(binding->{execution.original.remaining(binding.validUntil());remaining(execution);})
            .onErrorMap(error->denied());
    }
    private Mono<SecurityPrincipal> principal(ExecutionImpl execution){
        return Mono.defer(security::currentPrincipal).filter(execution.principal::equals).switchIfEmpty(Mono.error(denied()))
            .map(principal->{requirePrincipal(principal);remaining(execution);return principal;}).onErrorMap(error->denied());
    }
    private void requirePrincipal(SecurityPrincipal principal){
        Instant now=clock.instant(),expiry=principal.claim("exp",Instant.class),nbf=principal.claim("nbf",Instant.class);
        List<?> audience=principal.claim("aud",List.class);staffId(principal);
        if(!profile.issuer().equals(principal.issuer())||principal.subject()==null||principal.subject().isBlank()
            ||!"STAFF".equals(principal.claim("principal_kind",String.class))||!profile.platformTenant().equals(principal.tenantId())
            ||!profile.platformTenant().equals(principal.claim("tenant_id",String.class))||!profile.allowedClientId().equals(principal.claim("client_id",String.class))
            ||audience==null||!List.of(profile.audience()).equals(audience)||!READ_SCOPE.equals(principal.scopes())
            ||expiry==null||nbf==null||!now.isBefore(expiry)||now.isBefore(nbf)||!nbf.isBefore(expiry))throw denied();
    }
    private Duration remaining(ExecutionImpl execution){
        if(!execution.active.get()||clock.instant().isBefore(execution.observedAt))throw denied();
        try{return execution.original.remaining(execution.binding==null?execution.until:execution.binding.validUntil());}
        catch(RuntimeException expired){throw denied();}
    }
    private static UUID staffId(SecurityPrincipal principal){
        try{String value=principal.claim("staff_actor_id",String.class);UUID id=UUID.fromString(value);if(!id.toString().equals(value)||id.equals(new UUID(0,0)))throw denied();return id;}
        catch(IllegalArgumentException|NullPointerException invalid){throw denied();}
    }
    private final class ExecutionImpl {
        final UUID submissionId;final SecurityPrincipal principal;final Instant observedAt,until;final long observedNanos;final QuoteAuthorityBudget original;
        final AtomicBoolean active=new AtomicBoolean(true);QuoteTenantBindingResolver.Observation binding;Permit permit;
        ExecutionImpl(UUID submissionId,SecurityPrincipal principal,Instant observed,long before,Instant until){
            this.submissionId=submissionId;this.principal=principal;this.observedAt=observed;this.observedNanos=before;this.until=until;original=QuoteAuthorityBudget.observe(observed,until,before,clock,nanos);
        }
    }
    private static SecurityException denied(){return new SecurityException("Current registered STAFF submission read authority required");}
    @Override public String toString(){return "CurrentStaffSubmissionReadAdmission[redacted]";}
}
