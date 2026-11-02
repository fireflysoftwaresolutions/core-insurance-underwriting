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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QuoteOfferVersion(QuoteOfferScope scope, UUID offerId, long offerVersion, long controlGeneration,
        State state, QuoteOfferTerms terms, Instant issuedAt, Instant expiresAt) {
    public enum State { OFFERED, ACCEPTED, WITHDRAWN }

    public QuoteOfferVersion {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(terms, "terms");
        if (offerVersion <= 0 || controlGeneration < 0 || controlGeneration == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Positive version and incrementable control generation required");
        }
        issuedAt = QuoteValues.instant(issuedAt);
        expiresAt = QuoteValues.instant(expiresAt);
        if (!expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Offer validity must be positive and at most 24 hours");
        }
    }

    public String termsDigest() {
        return QuoteCanonicalCodec.digest(QuoteCanonicalCodec.terms(this));
    }
}
