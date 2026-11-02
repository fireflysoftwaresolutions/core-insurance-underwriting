package io.fireflysoftware.core.insurance.underwriting.core.services.governance;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteTenantBindingResolver;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.*;
import java.time.*;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.fireflyframework.kernel.audit.*;
import reactor.core.publisher.Mono;

public interface StaffUnderwritingAdmission {
    record Request(GovernedUnderwritingIntent intent,UUID actionId,String intentDigest,boolean recovery){
        public Request {UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.digest(intentDigest);if(recovery!=(intent==null) || intent!=null && !intent.actionId().equals(actionId))throw new IllegalArgumentException("Exact original owner underwriting request required");}
    }
    record Permit(Request request,QuoteTenantBindingResolver.Observation binding,String authorityLevel,AuditActor actor,String authorizationPolicyRevision,
            UUID processId,UUID correlationId,Instant observedAt,long observedNanos,Instant notAfter){
        public Permit {Objects.requireNonNull(request);Objects.requireNonNull(binding);UnderwritingGovernanceValues.text(authorityLevel,100);Objects.requireNonNull(actor);UnderwritingGovernanceValues.text(actor.staffRealm(),255);UnderwritingGovernanceValues.id(actor.staffId());UnderwritingGovernanceValues.text(authorizationPolicyRevision,128);UnderwritingGovernanceValues.id(processId);UnderwritingGovernanceValues.id(correlationId);if(observedAt==null || notAfter==null || !notAfter.isAfter(observedAt) || Duration.between(observedAt,notAfter).compareTo(Duration.ofSeconds(5))>0 || notAfter.isAfter(binding.validUntil()))throw new IllegalArgumentException("Current bounded owner underwriting admission required");}
        public UUID resourceTenantId(){return binding.resourceTenantId();}
        public UUID bindingId(){return binding.bindingId();}
        public long bindingVersion(){return binding.version();}
        public String bindingDigest(){return binding.definitionDigest();}
        public Duration remaining(Clock clock,LongSupplier nanos){long elapsed=nanos.getAsLong()-observedNanos;var wall=Duration.between(clock.instant(),notAfter);var monotonic=Duration.between(observedAt,notAfter).minusNanos(elapsed);if(elapsed<0 || wall.isNegative() || wall.isZero() || monotonic.isNegative() || monotonic.isZero())throw new SecurityException("Current underwriting admission expired");return wall.compareTo(monotonic)<0?wall:monotonic;}
        @Override public String toString(){return "StaffUnderwritingPermit[redacted]";}
    }
    Mono<Void> observeLockedSource(Permit original,io.fireflysoftware.core.insurance.underwriting.models.governance.UnderwritingGovernanceRows.Source source);
    Mono<Void> revalidate(Permit original);
    Mono<StaffUnderwritingDecisionEvidence> requireDecisionEvidence(Permit original);
    <T> Mono<T> withinExpectedAppend(Permit original,AuditEvent event,Supplier<Mono<T>> work);
}
