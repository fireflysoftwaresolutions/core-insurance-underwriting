/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.core.insurance.underwriting.models.quotes.impl;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;

import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/** Mapped local storage only; the service must independently enforce current authority before/after it. */
public final class QuoteMappedStoreImpl {
    public record Observation<T>(QuoteNativeWindow window,T value) {
        public Observation {Objects.requireNonNull(window);Objects.requireNonNull(value);}
        @Override public String toString() {return "QuoteStorageObservation";}
    }
    public enum Completion { NOT_STARTED, ACTIVE, COMMITTED, ROLLED_BACK, UNKNOWN }
    public enum RecoveryState { FOUND, NOT_OBSERVED_AT_THIS_READ, INTENT_CONFLICT }
    public record RecoveryData(RecoveryState state,Optional<QuoteResultEnvelope> result) {
        public RecoveryData {
            Objects.requireNonNull(state);Objects.requireNonNull(result);
            if ((state==RecoveryState.FOUND)!=result.isPresent()) throw invalid();
        }
        @Override public String toString() {return "QuoteRecoveryData";}
    }
    public static final class WriteFailure extends RuntimeException {
        private final UUID actionId;
        private final Completion completion;
        WriteFailure(UUID actionId,Completion completion,Throwable cause) {
            super("QUOTE_WRITE_"+completion,cause);this.actionId=actionId;this.completion=completion;
        }
        public UUID actionId() {return actionId;}
        public Completion completion() {return completion;}
    }
    private record Opened(QuoteRows.NativeStamp stamp,QuoteNativeWindow window) {}
    public record AcceptancePrerequisite(QuoteRows.AcceptMetadata metadata,String providerVersion,String ruleSetVersion) {
        public AcceptancePrerequisite {
            Objects.requireNonNull(metadata);
            if (providerVersion==null || ruleSetVersion==null || !providerVersion.matches("[A-Za-z0-9._:-]{1,100}")
                    || !ruleSetVersion.matches("[A-Za-z0-9._:-]{1,100}")) throw invalid();
        }
        private void requireMatch(QuoteAdmission a,QuoteRows.Version version,QuoteAcceptanceIntent intent) {
            QuoteRetainedGraph.scoped(a,metadata,a.offerId());
            if (metadata.offerVersion()!=1 || !Objects.equals(metadata.offerVersionId(),version.offerVersionId())
                    || !Objects.equals(metadata.productRevisionId(),version.productRevisionId()) || !Objects.equals(metadata.productDigest(),version.productDigest())
                    || !Objects.equals(metadata.termsDigest(),version.termsDigest()) || !Objects.equals(metadata.termsDigest(),intent.termsDigest())
                    || !Objects.equals(providerVersion,version.providerVersion()) || !Objects.equals(ruleSetVersion,version.ruleSetVersion())) throw invalid();
        }
        @Override public String toString() {return "QuoteAcceptancePrerequisite";}
    }
    private final QuotePersistenceImpl persistence;
    private final Clock clock;
    private final LongSupplier nanos;
    private final Supplier<UUID> ids;

    public QuoteMappedStoreImpl(QuotePersistenceImpl persistence,Clock clock,LongSupplier nanos,Supplier<UUID> ids) {
        this.persistence=Objects.requireNonNull(persistence);this.clock=Objects.requireNonNull(clock);
        this.nanos=Objects.requireNonNull(nanos);this.ids=Objects.requireNonNull(ids);
    }

    public Mono<Observation<QuoteRiskSnapshot>> prepare(QuoteAdmission a) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.OFFER_PREPARE);
            return persistence.command(a,null,budget->openCommand(a,budget).flatMap(open->{
                var bound=a.bindProduct(open.stamp().productId());
                return risk(persistence.commands,bound).flatMap(value->finish(persistence.commands,a,open.window(),value));
            }));
        });
    }

    public Mono<Observation<QuoteResultEnvelope>> offer(QuoteAdmission a,QuoteOfferRequestIntent request,
            QuoteRiskSnapshot expectedRisk,QuoteOfferTerms terms,Duration lifetime,QuoteNativeWindow prior) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.OFFER);Objects.requireNonNull(prior);prior.requireLive(clock.instant(),nanos.getAsLong());
            if (!a.scope().equals(request.scope()) || !a.actionId().equals(request.actionId())) throw invalid();
            var bound=a.narrowUntil(prior.until());
            return write(bound,prior,budget->openCommand(bound,budget).flatMap(open->{
                var window=prior.intersect(open.window());window.requireLive(clock.instant(),nanos.getAsLong());
                return risk(persistence.commands,a).flatMap(current->{
                    if (!current.equals(expectedRisk)) return Mono.error(invalid());
                    var rows=QuoteRowAssembly.offer(a,open.stamp(),request,current,terms,lifetime,ids.get(),ids.get());
                    var operations=persistence.commands.operations;
                    return operations.insert(rows.control()).then(operations.insert(rows.version()))
                            .then(operations.insert(rows.action())).then(valid(persistence.commands.nativeContext.validateComplete(a.nonce())))
                            .thenReturn(new Observation<>(window,rows.result()));
                });
            }));
        });
    }

    public Mono<Observation<QuoteResultEnvelope>> accept(QuoteAdmission a,QuoteAcceptanceIntent intent,AcceptancePrerequisite prerequisite,QuoteNativeWindow prior) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.ACCEPT);Objects.requireNonNull(prerequisite);Objects.requireNonNull(prior);prior.requireLive(clock.instant(),nanos.getAsLong());
            if (!a.scope().equals(intent.scope()) || !a.offerId().equals(intent.offerId()) || !a.actionId().equals(intent.actionId())) throw invalid();
            var bound=a.narrowUntil(prior.until());
            return write(bound,prior,budget->openCommand(bound,budget).flatMap(open->{
                var window=prior.intersect(open.window());window.requireLive(clock.instant(),nanos.getAsLong());
                var s=persistence.commands;
                return risk(s,a).flatMap(current->graph(s,a,a.offerId()).flatMap(graph->
                    s.controls.findByOfferId(a.offerId()).switchIfEmpty(Mono.error(invalid())).flatMap(control->
                    s.versions.findByOfferId(a.offerId()).switchIfEmpty(Mono.error(invalid())).flatMap(version->{
                        if (!graph.offer().termsDigest().equals(intent.termsDigest())) return Mono.error(invalid());
                        prerequisite.requireMatch(a,version,intent);
                        var rows=QuoteRowAssembly.acceptance(a,open.stamp(),control,version,intent,current,ids.get(),ids.get());
                        return s.controls.accept(a.tenantId(),a.partyId(),a.submissionId(),a.productId(),a.offerId(),a.issuer(),a.subject(),
                                rows.acceptance().acceptanceId(),open.stamp().dbRecordedAt())
                            .flatMap(count->{if (count!=1) return Mono.error(new IllegalStateException("QUOTE_CONTROL_CONFLICT"));return Mono.just(count);})
                            .switchIfEmpty(Mono.error(invalid())).then(s.operations.insert(rows.acceptance()))
                            .then(s.operations.insert(rows.action())).then(valid(s.nativeContext.validateComplete(a.nonce())))
                            .thenReturn(new Observation<>(window,rows.result()));
                    }))));
            }));
        });
    }

    public Mono<Observation<Optional<QuoteRetainedGraph>>> current(QuoteAdmission a) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.READ);
            return persistence.read(a,null,budget->openRead(a,budget).flatMap(open->
                persistence.reads.controls.findByOfferId(a.offerId())
                    .flatMap(control->graph(persistence.reads,a,control).map(Optional::of)).defaultIfEmpty(Optional.empty())
                    .flatMap(value->finish(persistence.reads,a,open.window(),value))));
        });
    }

    public Mono<Observation<Optional<QuoteRows.AcceptMetadata>>> acceptMetadata(QuoteAdmission a,QuoteAcceptanceIntent intent) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.READ);
            if (!a.scope().equals(intent.scope()) || !a.offerId().equals(intent.offerId()) || !Objects.equals(a.actionId(),intent.actionId())
                    || intent.offerVersion()!=1 || intent.expectedControlGeneration()!=0) throw invalid();
            return persistence.read(a,null,budget->openRead(a,budget).flatMap(open->
                persistence.reads.acceptMetadata.findByOfferId(a.offerId()).map(row->{
                    QuoteRetainedGraph.scoped(a,row,a.offerId());
                    if (row.offerVersion()!=1 || !Objects.equals(row.termsDigest(),intent.termsDigest())) throw invalid();
                    return Optional.of(row);
                }).defaultIfEmpty(Optional.empty()).flatMap(value->finish(persistence.reads,a,open.window(),value))));
        });
    }

    public Mono<Observation<List<QuoteRetainedGraph>>> history(QuoteAdmission a) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.HISTORY);
            return persistence.read(a,null,budget->openRead(a,budget).flatMap(open->{
                var s=persistence.reads;
                return s.controls.findFirst20ByTenantIdAndPartyIdAndSubmissionIdAndProductIdAndOwnerIssuerAndOwnerSubjectOrderByCreatedAtDescOfferIdDesc(
                        a.tenantId(),a.partyId(),a.submissionId(),a.productId(),a.issuer(),a.subject())
                    .concatMap(control->graph(s,a,control)).collectList().flatMap(values->{
                        if (values.size()>20 || values.stream().mapToLong(QuoteRetainedGraph::canonicalBytes).sum()>4*1024*1024) return Mono.error(invalid());
                        return finish(s,a,open.window(),List.copyOf(values));
                    });
            }));
        });
    }

    public Mono<Observation<RecoveryData>> recover(QuoteAdmission a,String expectedIntentDigest) {
        return Mono.defer(()->{
            mode(a,QuoteAdmission.Mode.RECOVER);
            if (expectedIntentDigest==null || !expectedIntentDigest.matches("[0-9a-f]{64}")) throw invalid();
            return persistence.read(a,null,budget->openRead(a,budget).flatMap(open->{
                var s=persistence.reads;
                return s.actions.findByTenantIdAndPartyIdAndSubmissionIdAndProductIdAndActionIdAndOwnerIssuerAndOwnerSubject(
                        a.tenantId(),a.partyId(),a.submissionId(),a.productId(),a.actionId(),a.issuer(),a.subject())
                    .flatMap(selected->{
                        if (!Objects.equals(selected.offerId(),open.stamp().offerId()) || !Objects.equals(selected.actionId(),a.actionId())) return Mono.error(invalid());
                        return graph(s,a,selected.offerId()).map(graph->new RecoveryData(RecoveryState.FOUND,Optional.of(graph.resultFor(a.actionId(),expectedIntentDigest).orElseThrow(QuoteMappedStoreImpl::invalid))))
                            .onErrorResume(QuoteRetainedGraph.IntentConflict.class,error->Mono.just(new RecoveryData(RecoveryState.INTENT_CONFLICT,Optional.empty())));
                    }).switchIfEmpty(Mono.defer(()->open.stamp().offerId()==null?Mono.just(new RecoveryData(RecoveryState.NOT_OBSERVED_AT_THIS_READ,Optional.empty())):Mono.error(invalid())))
                    .flatMap(value->finish(s,a,open.window(),value));
            }));
        });
    }

    private <T> Mono<Observation<T>> write(QuoteAdmission a,QuoteNativeWindow prior,java.util.function.Function<QuotePersistenceImpl.Invocation,Mono<Observation<T>>> work) {
        return Mono.defer(()->{
            var completion=new AtomicReference<>(Completion.NOT_STARTED);
            var window=new AtomicReference<QuoteNativeWindow>();
            return persistence.command(a,prior,budget->TransactionSynchronizationManager.forCurrentTransaction().flatMap(manager->{
                manager.registerSynchronization(new TransactionSynchronization() {
                    @Override public Mono<Void> beforeCommit(boolean readOnly) {
                        return Mono.fromRunnable(()->Objects.requireNonNull(window.get(),"Completed quote window required")
                                .requireLive(clock.instant(),nanos.getAsLong()));
                    }
                    @Override public Mono<Void> afterCompletion(int status) {
                        return Mono.fromRunnable(()->completion.set(status==STATUS_COMMITTED?Completion.COMMITTED:status==STATUS_ROLLED_BACK?Completion.ROLLED_BACK:Completion.UNKNOWN));
                    }
                });
                completion.set(Completion.ACTIVE);return Mono.defer(()->work.apply(budget)).doOnNext(value->window.set(value.window()));
            })).flatMap(value->completion.get()==Completion.COMMITTED?Mono.just(value):Mono.error(new WriteFailure(a.actionId(),completion.get(),null)))
              .onErrorMap(error->{
                  if(error instanceof WriteFailure)return error;
                  Completion observed=completion.get();
                  if(error instanceof QuotePersistenceImpl.QuoteTransactionUncertain timeout)observed=timeout.commitKnown()||observed==Completion.COMMITTED?Completion.COMMITTED:Completion.UNKNOWN;
                  return new WriteFailure(a.actionId(),observed,error);
              });
        });
    }

    private Mono<Opened> openCommand(QuoteAdmission a,QuotePersistenceImpl.Invocation budget) {
        return Mono.defer(()->{
            long before=nanos.getAsLong();var n=persistence.commands.nativeContext;
            return n.beginCommand(a.nonce(),a.mode().name(),a.tenantId(),a.submissionId(),a.partyId(),a.productId(),a.offerId(),a.actionId(),
                    a.issuer(),a.subject(),a.actorUntil().toEpochMilli(),a.permitUntil().toEpochMilli(),a.configUntil().toEpochMilli(),a.evidenceDigest())
                .flatMap(nonce->a.nonce().equals(nonce)?n.lockSubmission(a.nonce()):Mono.error(invalid()))
                .switchIfEmpty(Mono.error(invalid())).map(stamp->{var window=a.window(stamp,before);budget.observeNative(window);return new Opened(stamp,window);});
        });
    }
    private Mono<Opened> openRead(QuoteAdmission a,QuotePersistenceImpl.Invocation budget) {
        return Mono.defer(()->{
            long before=nanos.getAsLong();
            return persistence.reads.nativeContext.beginRead(a.nonce(),a.mode().name(),a.tenantId(),a.submissionId(),a.partyId(),a.productId(),a.offerId(),a.actionId(),
                    a.issuer(),a.subject(),a.actorUntil().toEpochMilli(),a.permitUntil().toEpochMilli(),a.configUntil().toEpochMilli(),a.evidenceDigest())
                .switchIfEmpty(Mono.error(invalid())).map(stamp->{var window=a.window(stamp,before);budget.observeNative(window);return new Opened(stamp,window);});
        });
    }
    private <T> Mono<Observation<T>> finish(QuotePersistenceImpl.Session s,QuoteAdmission a,QuoteNativeWindow window,T value) {
        return valid(s.nativeContext.contextCurrent(a.nonce())).then(Mono.fromSupplier(()->{
            window.requireLive(clock.instant(),nanos.getAsLong());return new Observation<>(window,value);
        }));
    }
    private static Mono<Void> valid(Mono<Boolean> result) {
        return result.filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(invalid())).then();
    }

    private Mono<QuoteRiskSnapshot> risk(QuotePersistenceImpl.Session s,QuoteAdmission a) {
        return s.submissions.findBySubmissionId(a.submissionId()).switchIfEmpty(Mono.error(invalid())).flatMap(parent->{
            if (!Objects.equals(parent.submissionId(),a.submissionId()) || !Objects.equals(parent.tenantId(),a.tenantId()) || !Objects.equals(parent.partyId(),a.partyId()) || !Objects.equals(parent.productId(),a.productId())) return Mono.error(invalid());
            var source=new QuoteRiskSnapshot.Submission(a.scope(),SubmissionStatus.valueOf(parent.submissionStatus()),LineOfBusiness.valueOf(parent.lineOfBusiness()),parent.currency());
            return s.risks.findFirst2BySubmissionId(a.submissionId()).collectList().flatMap(risks->
                s.values.findFirst2BySubmissionId(a.submissionId()).collectList().map(values->{
                    for (var row:risks) if (!Objects.equals(row.tenantId(),a.tenantId()) || !Objects.equals(row.productId(),a.productId()) || !Objects.equals(row.submissionPartyId(),a.partyId())) throw invalid();
                    for (var row:values) if (!Objects.equals(row.tenantId(),a.tenantId()) || !Objects.equals(row.productId(),a.productId()) || !Objects.equals(row.submissionPartyId(),a.partyId())) throw invalid();
                    return QuoteRiskSnapshot.fromRows(source,risks.stream().map(row->new QuoteRiskSnapshot.Risk(row.submissionRiskId(),row.submissionId(),InsuredObjectType.valueOf(row.insuredObjectType()),row.partyId())).toList(),
                        values.stream().map(row->new QuoteRiskSnapshot.Value(row.statementOfValueId(),row.submissionId(),row.lineNumber(),row.declaredValue(),row.currency())).toList());
                }));
        });
    }
    private Mono<QuoteRetainedGraph> graph(QuotePersistenceImpl.Session s,QuoteAdmission a,UUID offerId) {
        return s.controls.findByOfferId(offerId).switchIfEmpty(Mono.error(invalid())).flatMap(control->graph(s,a,control));
    }
    private Mono<QuoteRetainedGraph> graph(QuotePersistenceImpl.Session s,QuoteAdmission a,QuoteRows.Control control) {
        return s.versions.findByOfferId(control.offerId()).switchIfEmpty(Mono.error(invalid())).flatMap(version->
            s.actions.findFirst3ByOfferId(control.offerId()).collectList().flatMap(actions->{
                if (control.acceptanceId()==null) return Mono.just(QuoteRetainedGraph.verify(a,control,version,actions,null));
                return s.acceptances.findByAcceptanceId(control.acceptanceId()).switchIfEmpty(Mono.error(invalid()))
                    .map(acceptance->QuoteRetainedGraph.verify(a,control,version,actions,acceptance));
            }));
    }
    private static void mode(QuoteAdmission a,QuoteAdmission.Mode expected) {
        if (a==null || a.mode()!=expected) throw invalid();
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("QUOTE_STORAGE_SCOPE_OR_GRAPH_INVALID");}
    @Override public String toString() {return "QuoteMappedStore";}
}
