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


package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Retained result data; the owner must obtain recordedAt from its admitted database decision. */
public record QuoteResultEnvelope(QuoteActionEnvelope action, UUID resultId, UUID offerVersionId,
        UUID acceptanceId, Instant recordedAt, long resultingControlGeneration) {
    public QuoteResultEnvelope {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(offerVersionId, "offerVersionId");
        recordedAt = QuoteValues.instant(recordedAt);
        if (action.operation() == QuoteActionEnvelope.Operation.OFFER) {
            if (acceptanceId != null || resultingControlGeneration != 0) {
                throw new IllegalArgumentException("Offer result must retain generation zero without acceptance");
            }
        } else if (acceptanceId == null || resultingControlGeneration != action.expectedControlGeneration() + 1) {
            throw new IllegalArgumentException("Acceptance result must bind one generation advance and its ID");
        }
    }
}
