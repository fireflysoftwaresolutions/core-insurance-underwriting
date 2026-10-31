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


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;

/** No routes or permissive producer fallback. Every emitted protected value passes final current authority. */
public final class QuoteGovernedServiceImpl implements QuoteGovernedService {
    private record Configured(QuoteConfigurationPort.Observation value,QuoteNativeWindow window) {}
    private final QuoteAuthorityGate authority;
    private final QuoteMappedStore store;
    private final QuoteConfigurationPort configurations;
    private final SandboxPropertyQuoteRating rating;
    private final Clock clock;
    private final LongSupplier nanos;

    public QuoteGovernedServiceImpl(QuoteAuthorityGate authority,QuoteMappedStore store,QuoteConfigurationPort configurations,
            SandboxPropertyQuoteRating rating,Clock clock,LongSupplier nanos) {
        this.authority=Objects.requireNonNull(authority);this.store=Objects.requireNonNull(store);this.configurations=Objects.requireNonNull(configurations);
        this.rating=Objects.requireNonNull(rating);this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);
    }

    @Override public Mono<WriteOutcome> offer(QuoteOfferRequestIntent request) {
        return Mono.defer(()->{
            Objects.requireNonNull(request);UUID offerId=UUID.randomUUID();
            return authority.authorize(offerRequest(request,QuoteAdmission.Mode.OFFER_PREPARE,QuoteAuthorityGate.Phase.PREPARE,null))
                .flatMap(prepared->store.prepare(prepared.admission(prepared.until())).flatMap(source->{
                    if (!source.value().scope().equals(request.scope())) return Mono.error(unavailable());
                    return configured(request.scope(),request.expectedProductRevisionId(),request.expectedProductDigest(),source.window()).flatMap(configured->{
                        var config=configured.value();var risk=source.value();
                        var terms=rating.rate(new SandboxPropertyQuoteRating.Request(risk.scope(),config.revisionId(),config.productDigest(),risk.riskDigest(),
                                risk.currency(),risk.declaredValue(),config.deductible(),request.coverageStart(),request.coverageEnd()));
                        if (!config.providerVersion().equals(terms.providerVersion()) || !config.ruleSetVersion().equals(terms.ruleSetVersion())) return Mono.error(unavailable());
                        return authority.authorize(offerRequest(request,QuoteAdmission.Mode.OFFER,QuoteAuthorityGate.Phase.COMMIT,offerId))
                            .timeout(remaining(configured.window())).flatMap(committing->{
                                authority.requireSameActor(prepared,committing);
                                return protectWrite(committing,config,store.offer(admission(committing,config),request,risk,terms,config.offerLifetime(),configured.window()));
                            });
                    });
                }));
        }).onErrorMap(error->unavailable());
    }

    @Override public Mono<WriteOutcome> accept(QuoteAcceptanceIntent intent) {
        return Mono.defer(()->{
            Objects.requireNonNull(intent);
            if (intent.offerVersion()!=1 || intent.expectedControlGeneration()!=0) return Mono.error(unavailable());
            return authority.authorize(acceptRequest(intent,QuoteAuthorityGate.Phase.PREPARE,null))
                .flatMap(prepared->store.acceptMetadata(prepared.acceptanceMetadataAdmission(),intent).flatMap(observed->{
                    if (observed.value().isEmpty()) return authority.project(prepared,observed.window(),()->new WriteOutcome(Status.REFUSED,intent.actionId(),Optional.empty()));
                    var metadata=observed.value().orElseThrow();
                    return configured(intent.scope(),metadata.productRevisionId(),metadata.productDigest(),observed.window()).flatMap(configured->{
                        var config=configured.value();
                        return authority.authorize(acceptRequest(intent,QuoteAuthorityGate.Phase.COMMIT,metadata))
                            .timeout(remaining(configured.window())).flatMap(committing->{
                                authority.requireSameActor(prepared,committing);
                                var prerequisite=new QuoteMappedStore.AcceptancePrerequisite(metadata,config.providerVersion(),config.ruleSetVersion());
                                return protectWrite(committing,config,store.accept(admission(committing,config),intent,prerequisite,configured.window()));
                            });
                    });
                }));
        }).onErrorMap(error->unavailable());
    }

    @Override public Mono<Optional<ReadView>> current(QuoteOfferScope scope,UUID offerId) {
        return Mono.defer(()->authority.authorize(readRequest(scope,offerId,null,null,QuoteAdmission.Mode.READ))
            .flatMap(permit->store.current(permit.admission(permit.until()))
                .flatMap(observed->authority.project(permit,observed.window(),()->observed.value().map(QuoteGovernedServiceImpl::view)))))
            .onErrorMap(error->unavailable());
    }
    @Override public Mono<List<ReadView>> history(QuoteOfferScope scope) {
        return Mono.defer(()->authority.authorize(readRequest(scope,null,null,null,QuoteAdmission.Mode.HISTORY))
            .flatMap(permit->store.history(permit.admission(permit.until()))
                .flatMap(observed->authority.project(permit,observed.window(),()->observed.value().stream().map(QuoteGovernedServiceImpl::view).toList()))))
            .onErrorMap(error->unavailable());
    }
    @Override public Mono<Recovery> recover(QuoteOfferScope scope,UUID actionId,String expectedIntentDigest) {
        return Mono.defer(()->authority.authorize(readRequest(scope,null,actionId,expectedIntentDigest,QuoteAdmission.Mode.RECOVER))
            .flatMap(permit->store.recover(permit.admission(permit.until()),expectedIntentDigest)
                .flatMap(observed->authority.project(permit,observed.window(),()->new Recovery(
                        RecoveryState.valueOf(observed.value().state().name()),actionId,observed.value().result())))))
            .onErrorMap(error->unavailable());
    }

    private Mono<WriteOutcome> protectWrite(QuoteAuthorityGate.Permit permit,QuoteConfigurationPort.Observation config,
            Mono<QuoteMappedStore.Observation<QuoteResultEnvelope>> writing) {
        UUID actionId=permit.request().actionId();
        return writing.flatMap(committed->
            configured(committed.value().action().scope(),config.revisionId(),config.productDigest(),committed.window())
                .flatMap(current->{
                    if (!config.sameDefinition(current.value())) return Mono.error(unavailable());
                    return authority.project(permit,current.window(),()->new WriteOutcome(Status.COMMITTED,actionId,Optional.of(committed.value())));
                }).onErrorResume(error->Mono.just(new WriteOutcome(Status.COMMITTED_RESPONSE_UNAVAILABLE,actionId,Optional.empty()))))
            .onErrorResume(QuoteMappedStore.WriteFailure.class,error->{
                Status status=switch(error.completion()) {
                    case COMMITTED -> Status.COMMITTED_RESPONSE_UNAVAILABLE;
                    case ACTIVE,UNKNOWN -> Status.OUTCOME_UNKNOWN;
                    case NOT_STARTED,ROLLED_BACK -> Status.REFUSED;
                };
                return Mono.just(new WriteOutcome(status,actionId,Optional.empty()));
            });
    }

    private Mono<Configured> configured(QuoteOfferScope scope,UUID revision,String digest,QuoteNativeWindow prior) {
        return Mono.defer(()->{
            prior.requireLive(clock.instant(),nanos.getAsLong());long before=nanos.getAsLong();Instant started=clock.instant();
            return Mono.defer(()->configurations.observe(scope,revision,digest)).timeout(remaining(prior)).switchIfEmpty(Mono.error(unavailable()))
                .map(value->{
                    value.requireMatch(scope,revision,digest);
                    if (value.observedAt().isBefore(started.minusSeconds(5)) || value.observedAt().isAfter(clock.instant().plusSeconds(1))) throw unavailable();
                    var bounded=prior.intersectEvidence(value.observedAt(),value.effectiveUntil(),before);
                    bounded.requireLive(clock.instant(),nanos.getAsLong());return new Configured(value,bounded);
                });
        });
    }
    private Duration remaining(QuoteNativeWindow window) {
        var remaining=window.remaining(clock.instant(),nanos.getAsLong());return remaining.compareTo(Duration.ofSeconds(2))<0?remaining:Duration.ofSeconds(2);
    }
    private static QuoteAdmission admission(QuoteAuthorityGate.Permit permit,QuoteConfigurationPort.Observation config) {
        var a=permit.admission(config.effectiveUntil());
        String digest=QuoteCanonicalCodec.digest(QuoteCanonicalCodec.encodeFields(List.of("QUOTE_BACKEND_DECISION_EVIDENCE_1",permit.evidenceDigest(),config.factsDigest())));
        return new QuoteAdmission(a.nonce(),a.mode(),a.tenantId(),a.submissionId(),a.partyId(),a.productId(),a.offerId(),a.actionId(),a.issuer(),a.subject(),
                a.actorUntil(),a.permitUntil(),a.configUntil(),digest,a.authorityBudget());
    }
    private static QuoteAuthorityGate.Request offerRequest(QuoteOfferRequestIntent r,QuoteAdmission.Mode mode,QuoteAuthorityGate.Phase phase,UUID offerId) {
        return new QuoteAuthorityGate.Request(mode,phase,r.scope().tenantId(),r.scope().submissionId(),r.scope().partyId(),mode==QuoteAdmission.Mode.OFFER_PREPARE?null:r.scope().productId(),
                offerId,r.actionId(),r.intentDigest(),Map.of("expectedProductRevisionId",r.expectedProductRevisionId().toString(),"expectedProductDigest",r.expectedProductDigest(),
                        "coverageStart",r.coverageStart().toString(),"coverageEnd",r.coverageEnd().toString()));
    }
    private static QuoteAuthorityGate.Request acceptRequest(QuoteAcceptanceIntent r,QuoteAuthorityGate.Phase phase,QuoteRows.AcceptMetadata metadata) {
        var details=new HashMap<String,String>(Map.of("expectedOfferVersion",Long.toString(r.offerVersion()),"expectedControlGeneration",Long.toString(r.expectedControlGeneration()),"termsDigest",r.termsDigest()));
        if (metadata!=null) {details.put("expectedProductRevisionId",metadata.productRevisionId().toString());details.put("expectedProductDigest",metadata.productDigest());}
        return new QuoteAuthorityGate.Request(QuoteAdmission.Mode.ACCEPT,phase,r.scope().tenantId(),r.scope().submissionId(),r.scope().partyId(),r.scope().productId(),r.offerId(),r.actionId(),r.intentDigest(),details);
    }
    private static QuoteAuthorityGate.Request readRequest(QuoteOfferScope scope,UUID offerId,UUID actionId,String digest,QuoteAdmission.Mode mode) {
        return new QuoteAuthorityGate.Request(mode,QuoteAuthorityGate.Phase.READ,scope.tenantId(),scope.submissionId(),scope.partyId(),scope.productId(),offerId,actionId,digest,Map.of());
    }
    private static ReadView view(QuoteRetainedGraph graph) {return new ReadView(graph.offer(),graph.receipt());}
    private static IllegalStateException unavailable() {return new IllegalStateException("QUOTE_ADMISSION_UNAVAILABLE");}
    @Override public String toString() {return "QuoteGovernedService";}
}
