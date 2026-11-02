/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.core.services.governance.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.governance.*;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.fireflyframework.core.audit.*;
import org.fireflyframework.core.audit.impl.SpringDataTransactionalAuditAppender;
import org.fireflyframework.core.transaction.*;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction;
import org.fireflyframework.kernel.audit.*;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;
import io.fireflysoftware.core.insurance.underwriting.interfaces.governance.GovernedUnderwritingCommandResponse.Status;

/** The actual canonical append and all mapped effects share this verified Core root. */
public final class StaffUnderwritingAuditRootImpl {
    public record Effect(AuditRecord record,StaffUnderwritingDecisionEvidence decisions) {}
    private final BoundedR2dbcResources resources;
    private final R2dbcEntityTemplate template;
    private final BoundedRootTransaction root;
    private final TransactionalAuditAppender append;
    private final StaffUnderwritingAdmission authority;
    private final Clock clock;
    private final LongSupplier nanos;
    public StaffUnderwritingAuditRootImpl(BoundedR2dbcResources resources,DatabaseClient client,R2dbcEntityTemplate template,
            R2dbcTransactionManager manager,CleanupPolicy cleanup,AuditAppendAuthority appendAuthority,
            StaffUnderwritingAdmission authority,Clock clock,LongSupplier nanos){
        this.resources=BoundedR2dbcResources.verify(Objects.requireNonNull(resources).factory(),client,template,manager);
        if(appendAuthority!=authority)throw new IllegalArgumentException("One actual underwriting and append authority required");
        this.template=template;this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
        this.root=new DefaultBoundedRootTransaction(this.resources,Objects.requireNonNull(cleanup));
        this.append=new SpringDataTransactionalAuditAppender(this.resources,client,template,manager,appendAuthority,clock,nanos,UUID::randomUUID);
    }
    public Mono<Void> observeLockedSource(StaffUnderwritingAdmission.Permit permit,io.fireflysoftware.core.insurance.underwriting.models.governance.UnderwritingGovernanceRows.Source source){return authority.observeLockedSource(permit,source);}
    public void requireTemplate(R2dbcEntityTemplate value){if(template!=value)throw new IllegalArgumentException("Exact underwriting factory/template required");}
    public <T> Mono<T> execute(StaffUnderwritingAdmission.Permit permit,UUID submissionId,UUID eventId,String action,
            java.util.function.Supplier<String> evidenceDigest,java.util.function.Supplier<UUID> predecessor,java.util.function.Supplier<Mono<java.util.Optional<T>>> replay,Function<Effect,Mono<T>> writes){return Mono.defer(()->{
        permit.remaining(clock,nanos);
        var budget=R2dbcOperationBudget.observed(permit.observedAt(),permit.notAfter(),permit.observedNanos(),Duration.ofSeconds(5),clock,nanos);
        var definition=new DefaultTransactionDefinition();definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return authority.revalidate(permit).timeout(permit.remaining(clock,nanos)).then(root.execute(budget,definition,scope->
            resources.factory().requireBoundScope(resources,scope).then(Mono.defer(replay))
                .switchIfEmpty(Mono.error(invalid())).flatMap(retained->{
                    permit.remaining(clock,nanos);scope.remaining();
                    if(retained.isPresent())return Mono.just(retained.orElseThrow());
                    return Mono.defer(()->authority.requireDecisionEvidence(permit)).switchIfEmpty(Mono.error(invalid())).flatMap(decisions->{
                    if(!permit.authorizationPolicyRevision().equals(decisions.authorization().policyRevision()))return Mono.error(invalid());
                    String pairedDigest=io.fireflysoftware.core.insurance.underwriting.interfaces.governance.UnderwritingGovernanceValues.hash("CORE_UNDERWRITING_PAIRED_EFFECT_V1",evidenceDigest.get(),permit.bindingId().toString(),Long.toString(permit.bindingVersion()),permit.bindingDigest(),decisions.digest());
        var event=new AuditEvent(eventId,new AuditStream(permit.resourceTenantId(),"core-insurance-underwriting","governed-underwriting",submissionId),
                permit.actor(),action,AuditEvent.Outcome.COMPLETED,permit.processId(),permit.correlationId(),predecessor.get(),clock.instant(),pairedDigest,decisions.admission().policyRevision());
                    return Mono.defer(()->authority.withinExpectedAppend(permit,event,()->append.append(scope,event)))
                .switchIfEmpty(Mono.error(invalid())).flatMap(appended->{
                    var record=appended.record();
                    if(appended.replay() || record==null || !event.equals(record.event()) || !AuditRecordHasher.record(
                            new AuditCheckpoint(event.stream(),record.sequence()-1,record.previousHash()),event,record.recordedAt()).equals(record))return Mono.error(invalid());
                    permit.remaining(clock,nanos);scope.remaining();
                    return resources.factory().requireBoundScope(resources,scope).then(Mono.defer(()->writes.apply(new Effect(record,decisions))))
                        .switchIfEmpty(Mono.error(invalid())).timeout(scope.remaining()).map(value->{permit.remaining(clock,nanos);scope.remaining();return value;});
                });});}).onErrorResume(error->resources.factory().markBoundScopeRollbackOnly(resources,scope)
                    .onErrorResume(markFailure->Mono.error(error)).then(Mono.error(error)))))
            .map(completed->{var facts=completed.transactionFacts();
                if(!facts.nativeAttempted() || !facts.commitAcknowledged() || !facts.boundaryCompleted() || facts.rollbackAcknowledged()
                        || completed.cleanupFact()!=CleanupFact.COMPLETE)throw new StaffUnderwritingWriteFailure(facts.commitAcknowledged()&&!facts.rollbackAcknowledged()?Status.COMMITTED_RESPONSE_UNAVAILABLE:Status.OUTCOME_UNKNOWN);
                return completed.value();
            }).onErrorMap(RootTransactionException.class,error->{var facts=error.transactionFacts();
                Status status=facts.commitAcknowledged()&&!facts.rollbackAcknowledged()?Status.COMMITTED_RESPONSE_UNAVAILABLE:
                    facts.rollbackAcknowledged()&&!facts.commitAcknowledged()&&!facts.boundaryCompleted()&&error.cleanupFact()==CleanupFact.COMPLETE?Status.REFUSED:Status.OUTCOME_UNKNOWN;
                return new StaffUnderwritingWriteFailure(status);
            });
    });}
    public <T> Mono<T> read(StaffUnderwritingAdmission.Permit permit,java.util.function.Supplier<Mono<T>> work){return Mono.defer(()->{
        permit.remaining(clock,nanos);
        var budget=R2dbcOperationBudget.observed(permit.observedAt(),permit.notAfter(),permit.observedNanos(),Duration.ofSeconds(5),clock,nanos);
        var definition=new DefaultTransactionDefinition();definition.setReadOnly(true);
        definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return root.execute(budget,definition,scope->resources.factory().requireBoundScope(resources,scope)
                .then(Mono.defer(work)).switchIfEmpty(Mono.error(invalid())).timeout(scope.remaining()))
            .map(completed->{var facts=completed.transactionFacts();if(!facts.commitAcknowledged() || !facts.boundaryCompleted()
                    || facts.rollbackAcknowledged() || completed.cleanupFact()!=CleanupFact.COMPLETE)throw invalid();
                permit.remaining(clock,nanos);return completed.value();});
    });}
    private static IllegalStateException invalid(){return new IllegalStateException("Exact canonical underwriting audit append required");}
}
