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


package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteAcceptanceIntent;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteAcceptanceReceipt;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferVersion;
import java.time.Instant;
import java.util.Objects;

/** Local invariant checks only; authenticated current authorization and persistence remain required. */
public final class QuoteOfferAcceptance {
    private QuoteOfferAcceptance() {}

    public static void requireNewAcceptance(QuoteOfferVersion offer, QuoteAcceptanceIntent intent,
            Instant databaseNow, Instant validatedWriteHorizon) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(databaseNow, "databaseNow");
        Objects.requireNonNull(validatedWriteHorizon, "validatedWriteHorizon");
        require(offer.scope().equals(intent.scope()), "SCOPE_MISMATCH");
        require(offer.offerId().equals(intent.offerId()), "OFFER_MISMATCH");
        require(offer.offerVersion() == intent.offerVersion(), "STALE_VERSION");
        require(offer.controlGeneration() == intent.expectedControlGeneration(), "STALE_CONTROL");
        require(offer.termsDigest().equals(intent.termsDigest()), "TERMS_MISMATCH");
        require(offer.state() == QuoteOfferVersion.State.OFFERED, "NOT_OFFERED");
        require(!databaseNow.isBefore(offer.issuedAt()), "CLOCK_BEFORE_ISSUE");
        require(databaseNow.isBefore(offer.expiresAt()), "OFFER_EXPIRED");
        require(databaseNow.isBefore(validatedWriteHorizon), "AUTHORITY_EXPIRED");
    }

    public static QuoteAcceptanceReceipt recover(QuoteAcceptanceReceipt retained, QuoteAcceptanceIntent intent,
            Instant databaseNow, Instant validatedReadHorizon) {
        Objects.requireNonNull(retained, "retained");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(databaseNow, "databaseNow");
        Objects.requireNonNull(validatedReadHorizon, "validatedReadHorizon");
        require(retained.intent().equals(intent), "INTENT_CONFLICT");
        require(!databaseNow.isBefore(retained.acceptedAt()), "CLOCK_BEFORE_ACCEPTANCE");
        require(databaseNow.isBefore(validatedReadHorizon), "AUTHORITY_EXPIRED");
        return retained;
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException(code);
    }
}
