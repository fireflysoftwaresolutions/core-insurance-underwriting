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


package io.fireflysoftware.core.insurance.underwriting.models.quotes;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Backend-supplied current evidence, not an authenticated capability or customer input. */
public record QuoteAdmission(UUID nonce, Mode mode, UUID tenantId, UUID submissionId, UUID partyId,
        UUID productId, UUID offerId, UUID actionId, String issuer, String subject,
        Instant actorUntil, Instant permitUntil, Instant configUntil, String evidenceDigest, QuoteAuthorityBudget authorityBudget) {
    public QuoteAdmission(UUID nonce,Mode mode,UUID tenantId,UUID submissionId,UUID partyId,UUID productId,UUID offerId,UUID actionId,
            String issuer,String subject,Instant actorUntil,Instant permitUntil,Instant configUntil,String evidenceDigest) {
        this(nonce,mode,tenantId,submissionId,partyId,productId,offerId,actionId,issuer,subject,actorUntil,permitUntil,configUntil,evidenceDigest,null);
    }
    public QuoteAdmission withBudget(QuoteAuthorityBudget original) {
        return new QuoteAdmission(nonce,mode,tenantId,submissionId,partyId,productId,offerId,actionId,issuer,subject,actorUntil,permitUntil,configUntil,evidenceDigest,Objects.requireNonNull(original));
    }
    public enum Mode { OFFER_PREPARE, OFFER, ACCEPT, READ, HISTORY, RECOVER }
    public QuoteAdmission {
        Objects.requireNonNull(nonce, "nonce");Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(tenantId, "tenantId");Objects.requireNonNull(submissionId, "submissionId");Objects.requireNonNull(partyId, "partyId");
        if (productId == null && mode != Mode.OFFER_PREPARE) throw invalid();
        if ((mode == Mode.OFFER || mode == Mode.ACCEPT || mode == Mode.READ) && offerId == null) throw invalid();
        if ((mode == Mode.OFFER_PREPARE || mode == Mode.OFFER || mode == Mode.ACCEPT || mode == Mode.RECOVER) && actionId == null) throw invalid();
        if (mode == Mode.HISTORY && (offerId != null || actionId != null)) throw invalid();
        identity(issuer, 512);identity(subject, 255);
        for (Instant until : new Instant[] {actorUntil, permitUntil, configUntil}) {
            if (until == null || until.toEpochMilli() < 1 || until.toEpochMilli() > 253402300799999L) throw invalid();
        }
        if (evidenceDigest == null || !evidenceDigest.matches("[0-9a-f]{64}")) throw invalid();
    }
    public QuoteOfferScope scope() {
        if (productId == null) throw invalid();
        return new QuoteOfferScope(tenantId, submissionId, partyId, productId);
    }
    public QuoteAdmission bindProduct(UUID actualProduct) {
        Objects.requireNonNull(actualProduct, "actualProduct");
        if (productId != null && !productId.equals(actualProduct)) throw invalid();
        return new QuoteAdmission(nonce,mode,tenantId,submissionId,partyId,actualProduct,offerId,actionId,issuer,subject,
                actorUntil,permitUntil,configUntil,evidenceDigest,authorityBudget);
    }
    public QuoteAdmission narrowUntil(Instant previousNativeUntil) {
        Objects.requireNonNull(previousNativeUntil,"previousNativeUntil");
        return new QuoteAdmission(nonce,mode,tenantId,submissionId,partyId,productId,offerId,actionId,issuer,subject,
                actorUntil,permitUntil,configUntil.isBefore(previousNativeUntil)?configUntil:previousNativeUntil,evidenceDigest,authorityBudget);
    }
    public QuoteNativeWindow window(QuoteRows.NativeStamp stamp, long beforeAdmissionNanos) {
        Objects.requireNonNull(stamp, "stamp");
        if (!nonce.equals(stamp.contextNonce()) || (productId != null && !productId.equals(stamp.productId()))
                || (offerId != null && !offerId.equals(stamp.offerId()))
                || stamp.effectiveUntilMs() > Math.min(actorUntil.toEpochMilli(),Math.min(permitUntil.toEpochMilli(),configUntil.toEpochMilli()))) throw invalid();
        return QuoteNativeWindow.from(stamp.dbRecordedAt().toInstant(),stamp.dbNowMs(),stamp.effectiveUntilMs(),beforeAdmissionNanos);
    }
    private static void identity(String value, int max) {
        if (value == null || value.isBlank() || value.indexOf(0) >= 0) throw invalid();
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max || !value.equals(new String(bytes,StandardCharsets.UTF_8))) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid bound quote admission"); }
    @Override public String toString() { return "QuoteAdmission"; }
}
