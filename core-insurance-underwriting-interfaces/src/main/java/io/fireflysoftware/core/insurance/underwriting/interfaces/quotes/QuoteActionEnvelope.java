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

/** Immutable owner data; supplied fields and digests do not establish caller authority. */
public record QuoteActionEnvelope(QuoteOfferScope scope, String ownerIssuer, String ownerSubject,
        Operation operation, UUID offerId, long offerVersion, long expectedControlGeneration,
        UUID actionId, String intentDigest) {
    public enum Operation { OFFER, ACCEPT }

    public QuoteActionEnvelope {
        Objects.requireNonNull(scope, "scope");
        ownerIssuer = identity(ownerIssuer, 512);
        ownerSubject = identity(ownerSubject, 255);
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(actionId, "actionId");
        intentDigest = QuoteValues.digest(intentDigest);
        if (offerVersion <= 0 || expectedControlGeneration < 0 || expectedControlGeneration == Long.MAX_VALUE
                || operation == Operation.OFFER && expectedControlGeneration != 0) {
            throw new IllegalArgumentException("Invalid action version or generation");
        }
    }

    private static String identity(String value, int maxBytes) {
        Objects.requireNonNull(value, "owner identity");
        if (value.isBlank() || value.indexOf(0) >= 0 || value.length() > maxBytes
                || QuoteCanonicalCodec.encodeFields(java.util.List.of(value)).length - 8 > maxBytes) {
            throw new IllegalArgumentException("Invalid bounded owner identity");
        }
        return value;
    }

    public static QuoteActionEnvelope acceptance(String issuer, String subject, QuoteAcceptanceIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return new QuoteActionEnvelope(intent.scope(), issuer, subject, Operation.ACCEPT, intent.offerId(),
                intent.offerVersion(), intent.expectedControlGeneration(), intent.actionId(), intent.intentDigest());
    }

    public boolean matches(QuoteAcceptanceIntent intent) {
        return intent != null && operation == Operation.ACCEPT && scope.equals(intent.scope())
                && offerId.equals(intent.offerId()) && offerVersion == intent.offerVersion()
                && expectedControlGeneration == intent.expectedControlGeneration()
                && actionId.equals(intent.actionId()) && intentDigest.equals(intent.intentDigest());
    }
}
