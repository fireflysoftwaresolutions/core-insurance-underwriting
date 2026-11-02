package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.*;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteTenantBindingResolver;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.fireflyframework.core.audit.AuditAppendAuthority;
import org.fireflyframework.kernel.audit.*;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import reactor.core.publisher.Mono;

/** Native STAFF purpose plus actual current namespace binding and same-response delegated PDP evidence. */
public final class CurrentStaffUnderwritingAdmissionImpl implements StaffUnderwritingAdmission,AuditAppendAuthority {
    public record Profile(String issuer,String audience,String allowedClientId,String platformTenant,UUID resourceTenantId,String canonicalStaffRealm,String authorityLevel){
        public Profile {UnderwritingGovernanceValues.text(issuer,500);UnderwritingGovernanceValues.text(audience,255);UnderwritingGovernanceValues.text(allowedClientId,255);UnderwritingGovernanceValues.text(platformTenant,1024);UnderwritingGovernanceValues.id(resourceTenantId);UnderwritingGovernanceValues.text(canonicalStaffRealm,255);UnderwritingGovernanceValues.text(authorityLevel,100);}
    }
    private final SecurityContextPort security;private final PolicyEvaluationPort pdp;private final QuoteTenantBindingResolver bindings;private final Profile profile;
    private final Clock clock;private final LongSupplier nanos;private final Supplier<UUID> ids;private final Object executionKey=new Object(),appendKey=new Object();
    public CurrentStaffUnderwritingAdmissionImpl(SecurityContextPort security,PolicyEvaluationPort pdp,QuoteTenantBindingResolver bindings,Profile profile,Clock clock,LongSupplier nanos,Supplier<UUID> ids){
        this.security=Objects.requireNonNull(security);this.pdp=Objects.requireNonNull(pdp);this.bindings=Objects.requireNonNull(bindings);this.profile=Objects.requireNonNull(profile);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);this.ids=Objects.requireNonNull(ids);
    }
    public Mono<GovernedUnderwritingCommandResponse> execute(GovernedUnderwritingIntent intent,String originalExpectedDigest,Function<StaffUnderwritingAdmission.Permit,Mono<GovernedUnderwritingCommandResponse>> work){
        Objects.requireNonNull(intent);Objects.requireNonNull(work);UnderwritingGovernanceValues.digest(originalExpectedDigest);
        return invoke(intent,intent.actionId(),originalExpectedDigest,false,true,work).onErrorResume(StaffUnderwritingWriteFailure.class,error->Mono.just(new GovernedUnderwritingCommandResponse(error.status(),intent.actionId(),originalExpectedDigest,null)));
    }
    public Mono<GovernedUnderwritingPreparedIntent> prepare(GovernedUnderwritingIntent intent){Objects.requireNonNull(intent);String digest=intent.intentDigest(profile.resourceTenantId());return invoke(intent,intent.actionId(),digest,false,false,permit->revalidate(permit).thenReturn(new GovernedUnderwritingPreparedIntent(intent.operation(),intent.actionId(),digest)));}
    public Mono<GovernedUnderwritingRecovery> recover(UUID actionId,String digest,Function<StaffUnderwritingAdmission.Permit,Mono<GovernedUnderwritingRecovery>> read){return invoke(null,actionId,digest,true,false,read);}
    private <T> Mono<T> invoke(GovernedUnderwritingIntent intent,UUID actionId,String expectedDigest,boolean recovery,boolean mutation,Function<StaffUnderwritingAdmission.Permit,Mono<T>> work){return Mono.deferContextual(context->{
        if(context.hasKey(executionKey))return Mono.error(denied());UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.digest(expectedDigest);
        Instant observed=clock.instant();long before=nanos.getAsLong();var request=new StaffUnderwritingAdmission.Request(intent,actionId,expectedDigest,recovery);
        return Mono.defer(security::currentPrincipal).switchIfEmpty(Mono.error(denied())).flatMap(principal->{
            requirePrincipal(principal,request);Instant expiry=principal.claim("exp",Instant.class);Instant until=expiry.isBefore(observed.plusSeconds(5))?expiry:observed.plusSeconds(5);
            var execution=new ExecutionImpl(request,principal,observed,before,until);
            return currentBinding(execution).flatMap(binding->{
                if(intent!=null && !expectedDigest.equals(intent.intentDigest(binding.resourceTenantId())))return Mono.error(denied());execution.binding=binding;
                return evaluate(execution,"authorize").flatMap(authorization->{
                    execution.authorization=authorization;Instant end=binding.validUntil().isBefore(until)?binding.validUntil():until;
                    execution.permit=new StaffUnderwritingAdmission.Permit(request,binding,profile.authorityLevel(),new AuditActor(principal.issuer(),principal.subject(),profile.allowedClientId(),principal.tenantId(),profile.canonicalStaffRealm(),staffId(principal)),authorization.provenance().orElseThrow().policyRevision(),UnderwritingGovernanceValues.id(ids.get()),UnderwritingGovernanceValues.id(ids.get()),observed,before,end);
                    return Mono.using(()->execution,held->Mono.defer(()->work.apply(held.permit)).switchIfEmpty(Mono.error(denied())).timeout(remaining(held)).onErrorResume(java.util.concurrent.TimeoutException.class,error->{
                            if(mutation && intent!=null){@SuppressWarnings("unchecked") T unknown=(T)new GovernedUnderwritingCommandResponse(GovernedUnderwritingCommandResponse.Status.OUTCOME_UNKNOWN,actionId,expectedDigest,null);return Mono.just(unknown);}return Mono.error(error);
                        })
                        .flatMap(value->{
                            if(value instanceof GovernedUnderwritingCommandResponse command && command.status()==GovernedUnderwritingCommandResponse.Status.COMMITTED)
                                return validateResponse(held).thenReturn(value).onErrorResume(error->{
                                    @SuppressWarnings("unchecked") T unavailable=(T)new GovernedUnderwritingCommandResponse(GovernedUnderwritingCommandResponse.Status.COMMITTED_RESPONSE_UNAVAILABLE,command.actionId(),command.intentDigest(),null);return Mono.just(unavailable);
                                });
                            return Mono.just(value);
                        }).contextWrite(owned->owned.put(executionKey,held)),held->held.active.set(false),true);
                });
            });
        });
    });}
    @Override public Mono<Void> revalidate(StaffUnderwritingAdmission.Permit permit){return Mono.deferContextual(context->{var execution=current(context);if(execution.permit!=permit)return Mono.error(denied());
        if(execution.request.recovery())return evaluate(execution,"response").then();
        if(!execution.appended.get())return principal(execution).then(currentBinding(execution).filter(execution.binding::sameDefinition).switchIfEmpty(Mono.error(denied()))).then();
        return evaluate(execution,"response").then();
    });}
    private Mono<Void> validateResponse(ExecutionImpl execution){return evaluate(execution,"response").then();}
    @Override public Mono<Void> observeLockedSource(StaffUnderwritingAdmission.Permit permit,io.fireflysoftware.core.insurance.underwriting.models.governance.UnderwritingGovernanceRows.Source source){return Mono.deferContextual(context->{var execution=current(context);if(execution.permit!=permit || execution.appended.get() || execution.captured.get() || execution.source!=null && !execution.source.equals(source))return Mono.error(denied());StaffUnderwritingEffectRulesImpl.source(permit,source);execution.source=source;return principal(execution).then();});}
    @Override public Mono<StaffUnderwritingDecisionEvidence> requireDecisionEvidence(StaffUnderwritingAdmission.Permit permit){return Mono.deferContextual(context->{var execution=current(context);if(execution.permit!=permit || execution.authorization==null || execution.source==null || !execution.captured.compareAndSet(false,true))return Mono.error(denied());return evaluate(execution,"admit").map(value->{execution.admission=value;return new StaffUnderwritingDecisionEvidence(execution.authorization.provenance().orElseThrow(),value.provenance().orElseThrow());});});}
    @Override public <T> Mono<T> withinExpectedAppend(StaffUnderwritingAdmission.Permit permit,AuditEvent event,Supplier<Mono<T>> work){return Mono.deferContextual(context->{var execution=current(context);
        if(execution.permit!=permit || execution.request.recovery() || !execution.captured.get() || context.hasKey(appendKey) || !permit.actor().equals(event.actor()) || event.outcome()!=AuditEvent.Outcome.COMPLETED || !event.action().equals(switch(permit.request().intent().operation()){case REFERRAL_CREATE->"insurance.underwriting.referral.create";case REFERRAL_APPROVE->"insurance.underwriting.referral.approve";case DECISION_RECORD->"insurance.underwriting.decision.record";})
                || !event.stream().equals(new AuditStream(permit.resourceTenantId(),"core-insurance-underwriting","governed-underwriting",permit.request().intent().submissionId()))
                || !execution.admission.provenance().orElseThrow().policyRevision().equals(event.policyRevision()) || !execution.appended.compareAndSet(false,true))return Mono.error(denied());
        return Mono.defer(work).contextWrite(current->current.put(appendKey,new ExpectedImpl(execution,event)));
    });}
    @Override public Mono<AuditAppendAuthority.Permit> requireCurrent(AuditEvent event){return Mono.deferContextual(context->{var execution=current(context);Object value=context.getOrDefault(appendKey,null);
        if(!(value instanceof ExpectedImpl expected) || expected.execution!=execution || !expected.event.equals(event) || expected.calls.incrementAndGet()>2)return Mono.error(denied());
        return principal(execution).map(ignored->new AuditAppendAuthority.Permit(event.stream(),execution.permit.actor(),execution.permit.notAfter()));
    });}
    private Mono<PolicyEvaluation> evaluate(ExecutionImpl execution,String role){return principal(execution).flatMap(principal->currentBinding(execution).flatMap(binding->{
        if(execution.binding!=null && !execution.binding.sameDefinition(binding))return Mono.error(denied());
        var request=execution.request;var facts=new LinkedHashMap<String,Object>();facts.put("profile","insurance-underwriting-staff-term-pc-1");facts.put("role",role);facts.put("resourceTenantId",binding.resourceTenantId());facts.put("bindingId",binding.bindingId());facts.put("bindingVersion",binding.version());facts.put("bindingDigest",binding.definitionDigest());facts.put("authorityLevel",profile.authorityLevel());facts.put("staffRealm",profile.canonicalStaffRealm());facts.put("staffId",staffId(principal));facts.put("actionId",request.actionId());facts.put("intentDigest",request.intentDigest());
        if(request.intent()!=null){var intent=request.intent();facts.put("submissionId",intent.submissionId());facts.put("riskId",intent.riskId());if(intent.referralId()!=null){facts.put("referralId",intent.referralId());facts.put("expectedReferralVersion",intent.expectedReferralVersion());facts.put("expectedReceiptDigest",intent.expectedReceiptDigest());}if(intent.reason()!=null)facts.put("reasonDigest",UnderwritingGovernanceValues.hash("UNDERWRITING_REFERRAL_REASON_V1",intent.reason()));}
        if(execution.source!=null){var source=execution.source;facts.put("lockedSourceDigest",StaffUnderwritingEffectRulesImpl.digest(source));facts.put("partyId",source.partyId());facts.put("productId",source.productId());facts.put("declaredValue",source.declaredValue());facts.put("currency",source.currency());facts.put("valueId",source.valueId());facts.put("insuredObjectType",source.objectType());}
        String operation=request.recovery()?"action.recover":switch(request.intent().operation()){case REFERRAL_CREATE->"referral.create";case REFERRAL_APPROVE->"referral.approve";case DECISION_RECORD->"decision.record";};
        return Mono.defer(()->pdp.evaluate(principal,"insurance.underwriting."+operation+"."+role,"urn:firefly:insurance:underwriting:tenant:"+binding.resourceTenantId()+":action:"+request.actionId(),Map.copyOf(facts)))
            .filter(value->value.decision().granted() && value.decision().obligations().isEmpty() && value.provenance().isPresent())
            .switchIfEmpty(Mono.error(denied())).flatMap(value->principal(execution).thenReturn(value));
    })).timeout(remaining(execution)).onErrorMap(error->denied());}
    private Mono<QuoteTenantBindingResolver.Observation> currentBinding(ExecutionImpl execution){return Mono.defer(()->bindings.resolve(execution.principal,profile.resourceTenantId(),execution.original,execution.until,()->remaining(execution)))
        .filter(value->profile.issuer().equals(value.issuer()) && profile.platformTenant().equals(value.platformTenant()) && profile.resourceTenantId().equals(value.resourceTenantId()))
        .switchIfEmpty(Mono.error(denied())).doOnNext(value->{execution.original.remaining(value.validUntil());remaining(execution);}).onErrorMap(error->denied());}
    private Mono<SecurityPrincipal> principal(ExecutionImpl execution){return Mono.defer(security::currentPrincipal).filter(execution.principal::equals).switchIfEmpty(Mono.error(denied())).map(value->{requirePrincipal(value,execution.request);remaining(execution);return value;}).onErrorMap(error->denied());}
    private void requirePrincipal(SecurityPrincipal value,StaffUnderwritingAdmission.Request request){Instant now=clock.instant(),expiry=value.claim("exp",Instant.class),nbf=value.claim("nbf",Instant.class);List<?> audience=value.claim("aud",List.class);
        Set<String> scopes=request.recovery()?Set.of("insurance:underwriting:actions:recover","insurance:underwriting:read"):Set.of(switch(request.intent().operation()){case REFERRAL_CREATE->"insurance:underwriting:referrals:create";case REFERRAL_APPROVE->"insurance:underwriting:referrals:approve";case DECISION_RECORD->"insurance:underwriting:decisions:record";});
        staffId(value);
        if(!profile.issuer().equals(value.issuer()) || value.subject()==null || value.subject().isBlank() || !"STAFF".equals(value.claim("principal_kind",String.class)) || !profile.platformTenant().equals(value.tenantId()) || !profile.platformTenant().equals(value.claim("tenant_id",String.class))
                || !profile.allowedClientId().equals(value.claim("client_id",String.class)) || audience==null || audience.size()!=1 || !profile.audience().equals(audience.getFirst())
                || expiry==null || nbf==null || !now.isBefore(expiry) || now.isBefore(nbf) || !nbf.isBefore(expiry) || !scopes.equals(value.scopes()))throw denied();
    }
    private static UUID staffId(SecurityPrincipal principal){String value=principal.claim("staff_actor_id",String.class);try{UUID id=UUID.fromString(value);if(!id.toString().equals(value) || id.equals(new UUID(0,0)))throw denied();return id;}catch(IllegalArgumentException|NullPointerException invalid){throw denied();}}
    private ExecutionImpl current(reactor.util.context.ContextView context){Object value=context.getOrDefault(executionKey,null);if(!(value instanceof ExecutionImpl execution) || !execution.active.get())throw denied();remaining(execution);return execution;}
    private Duration remaining(ExecutionImpl execution){if(!execution.active.get())throw denied();execution.original.remaining(execution.until);if(execution.permit!=null)return execution.permit.remaining(clock,nanos);return execution.original.remaining(execution.until);}
    private final class ExecutionImpl {
        final StaffUnderwritingAdmission.Request request;final SecurityPrincipal principal;final Instant observedAt,until;final long observedNanos;final QuoteAuthorityBudget original;
        final AtomicBoolean active=new AtomicBoolean(true),appended=new AtomicBoolean(),captured=new AtomicBoolean();QuoteTenantBindingResolver.Observation binding;StaffUnderwritingAdmission.Permit permit;PolicyEvaluation authorization,admission;io.fireflysoftware.core.insurance.underwriting.models.governance.UnderwritingGovernanceRows.Source source;
        ExecutionImpl(StaffUnderwritingAdmission.Request request,SecurityPrincipal principal,Instant observedAt,long observedNanos,Instant until){this.request=request;this.principal=principal;this.observedAt=observedAt;this.observedNanos=observedNanos;this.until=until;original=QuoteAuthorityBudget.observe(observedAt,until,observedNanos,clock,nanos);}
    }
    private final class ExpectedImpl {final ExecutionImpl execution;final AuditEvent event;final AtomicInteger calls=new AtomicInteger();ExpectedImpl(ExecutionImpl execution,AuditEvent event){this.execution=execution;this.event=event;}}
    private static SecurityException denied(){return new SecurityException("Current registered STAFF underwriting delegation required");}
    @Override public String toString(){return "CurrentStaffUnderwritingAdmission[redacted]";}
}
