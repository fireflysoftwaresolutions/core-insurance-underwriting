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

import java.util.Objects;
import java.util.UUID;

public record QuoteAcceptanceIntent(QuoteOfferScope scope, UUID offerId, long offerVersion,
        long expectedControlGeneration, String termsDigest, UUID actionId) {
    public QuoteAcceptanceIntent {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(actionId, "actionId");
        if (offerVersion <= 0 || expectedControlGeneration < 0 || expectedControlGeneration == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Positive version and incrementable control generation required");
        }
        termsDigest = QuoteValues.digest(termsDigest);
    }

    public String intentDigest() {
        return QuoteCanonicalCodec.digest(QuoteCanonicalCodec.intent(this));
    }
}
