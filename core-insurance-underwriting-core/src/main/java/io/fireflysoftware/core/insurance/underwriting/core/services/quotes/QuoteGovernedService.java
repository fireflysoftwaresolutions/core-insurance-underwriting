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


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import reactor.core.publisher.Mono;

/** Internal default-off sandbox quote service; acceptance is neither insurance binding nor payment. */
public interface QuoteGovernedService {
    enum Status { COMMITTED, COMMITTED_RESPONSE_UNAVAILABLE, OUTCOME_UNKNOWN, REFUSED }
    record WriteOutcome(Status status,UUID actionId,Optional<QuoteResultEnvelope> result) {
        public WriteOutcome {
            Objects.requireNonNull(status);Objects.requireNonNull(actionId);Objects.requireNonNull(result);
            if ((status==Status.COMMITTED)!=result.isPresent() || result.isPresent() && !result.orElseThrow().action().actionId().equals(actionId)) throw new IllegalArgumentException("Invalid quote write outcome");
        }
        @Override public String toString() {return "QuoteWriteOutcome["+status+"]";}
    }
    record ReadView(QuoteOfferVersion offer,Optional<QuoteAcceptanceReceipt> receipt) {
        public ReadView {Objects.requireNonNull(offer);Objects.requireNonNull(receipt);}
        @Override public String toString() {return "QuoteReadView";}
    }
    enum RecoveryState { FOUND, NOT_OBSERVED_AT_THIS_READ, INTENT_CONFLICT }
    record Recovery(RecoveryState state,UUID actionId,Optional<QuoteResultEnvelope> result) {
        public Recovery {
            Objects.requireNonNull(state);Objects.requireNonNull(actionId);Objects.requireNonNull(result);
            if ((state==RecoveryState.FOUND)!=result.isPresent() || result.isPresent() && !result.orElseThrow().action().actionId().equals(actionId)) throw new IllegalArgumentException("Invalid quote recovery outcome");
        }
        @Override public String toString() {return "QuoteRecovery["+state+"]";}
    }
    Mono<WriteOutcome> offer(QuoteOfferRequestIntent request);
    Mono<WriteOutcome> accept(QuoteAcceptanceIntent intent);
    Mono<Optional<ReadView>> current(QuoteOfferScope scope,UUID offerId);
    Mono<List<ReadView>> history(QuoteOfferScope scope);
    Mono<Recovery> recover(QuoteOfferScope scope,UUID actionId,String expectedIntentDigest);
}
