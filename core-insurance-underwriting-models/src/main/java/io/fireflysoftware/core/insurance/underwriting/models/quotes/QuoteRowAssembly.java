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

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** Local row assembly/integrity only; current caller, configuration and native source checks are separate. */
public final class QuoteRowAssembly {
    private QuoteRowAssembly() {}
    public record Offer(QuoteRows.Control control, QuoteRows.Version version, QuoteRows.Action action, QuoteResultEnvelope result) {}

    public static Offer offer(QuoteAdmission a, QuoteRows.NativeStamp stamp, QuoteOfferRequestIntent request,
            QuoteRiskSnapshot risk, QuoteOfferTerms terms, Duration lifetime, UUID versionId, UUID resultId) {
        Objects.requireNonNull(a);Objects.requireNonNull(stamp);Objects.requireNonNull(request);Objects.requireNonNull(risk);
        Objects.requireNonNull(terms);Objects.requireNonNull(lifetime);Objects.requireNonNull(versionId);Objects.requireNonNull(resultId);
        a.window(stamp,0);
        if (a.mode()!=QuoteAdmission.Mode.OFFER || !a.scope().equals(request.scope()) || !a.scope().equals(risk.scope())
                || !a.actionId().equals(request.actionId()) || !request.expectedProductRevisionId().equals(terms.productRevisionId())
                || !request.expectedProductDigest().equals(terms.productDigest()) || !risk.riskDigest().equals(terms.riskDigest())
                || !request.coverageStart().toString().equals(terms.coverageTerms().get("coverageStart"))
                || !request.coverageEnd().toString().equals(terms.coverageTerms().get("coverageEnd"))
                || lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofHours(24))>0
                || lifetime.getNano()%1000!=0) throw new IllegalArgumentException("Quote offer facts do not match current admission");
        var value=new QuoteOfferVersion(a.scope(),a.offerId(),1,0,QuoteOfferVersion.State.OFFERED,
                terms,stamp.dbRecordedAt().toInstant(),stamp.dbRecordedAt().toInstant().plus(lifetime));
        byte[] canonicalTerms=QuoteCanonicalCodec.terms(value),requestBytes=QuoteCanonicalCodec.offerRequest(request);
        String requestDigest=QuoteCanonicalCodec.digest(requestBytes);
        var envelope=QuoteActionEnvelope.offer(a.issuer(),a.subject(),a.offerId(),request);
        var result=new QuoteResultEnvelope(envelope,resultId,versionId,null,stamp.dbRecordedAt().toInstant(),0);
        byte[] resultBytes=QuoteCanonicalCodec.result(result);
        return new Offer(new QuoteRows.Control(
                a.actionId(),
                1,
                0,
                "OFFERED",
                null,
                stamp.dbRecordedAt(),
                stamp.dbRecordedAt(),
                a.tenantId(),
                a.partyId(),
                a.submissionId(),
                a.productId(),
                a.offerId(),
                a.issuer(),
                a.subject()),new QuoteRows.Version(
                1,
                "FIREFLY_INSURANCE_QUOTE_TERMS_1",
                canonicalTerms,
                QuoteCanonicalCodec.digest(canonicalTerms),
                QuoteCanonicalCodec.risk(risk),
                risk.riskDigest(),
                terms.productRevisionId(),
                terms.productDigest(),
                terms.providerVersion(),
                terms.ruleSetVersion(),
                terms.currency(),
                a.tenantId(),
                a.partyId(),
                a.submissionId(),
                a.productId(),
                a.offerId(),
                terms.basePremium(),
                terms.tax(),
                terms.fees(),
                stamp.dbRecordedAt(),
                value.expiresAt().atOffset(ZoneOffset.UTC),
                versionId,
                a.issuer(),
                a.subject()),new QuoteRows.Action(
                "OFFER",
                a.issuer(),
                a.subject(),
                null,
                stamp.dbRecordedAt(),
                a.tenantId(),
                a.partyId(),
                a.submissionId(),
                a.productId(),
                a.offerId(),
                versionId,
                1,
                a.actionId(),
                requestBytes,
                requestDigest,
                resultBytes,
                QuoteCanonicalCodec.digest(resultBytes),
                a.evidenceDigest(),
                resultId,
                a.issuer(),
                a.subject()),result);
    }

    public record Acceptance(QuoteRows.Acceptance acceptance, QuoteRows.Action action, QuoteResultEnvelope result) {}

    public static Acceptance acceptance(QuoteAdmission a, QuoteRows.NativeStamp stamp, QuoteRows.Control c,
            QuoteRows.Version v, QuoteAcceptanceIntent intent, QuoteRiskSnapshot currentRisk,
            UUID acceptanceId, UUID resultId) {
        Objects.requireNonNull(intent);Objects.requireNonNull(currentRisk);Objects.requireNonNull(acceptanceId);Objects.requireNonNull(resultId);
        var window=a.window(stamp,0);var offer=QuoteRetainedGraph.version(a,c,v);
        if (a.mode()!=QuoteAdmission.Mode.ACCEPT || offer.state()!=QuoteOfferVersion.State.OFFERED || offer.controlGeneration()!=0
                || !a.scope().equals(intent.scope()) || !a.scope().equals(currentRisk.scope()) || !a.actionId().equals(intent.actionId())
                || !a.offerId().equals(intent.offerId()) || intent.offerVersion()!=1 || intent.expectedControlGeneration()!=0
                || !intent.termsDigest().equals(v.termsDigest()) || !currentRisk.riskDigest().equals(v.riskDigest())
                || stamp.dbRecordedAt().toInstant().isBefore(offer.issuedAt()) || !stamp.dbRecordedAt().toInstant().isBefore(offer.expiresAt())
                || window.until().isAfter(offer.expiresAt())) throw new IllegalArgumentException("Current quote cannot be accepted");
        byte[] requestBytes=QuoteCanonicalCodec.intent(intent);String requestDigest=intent.intentDigest();
        var envelope=QuoteActionEnvelope.acceptance(a.issuer(),a.subject(),intent);
        var result=new QuoteResultEnvelope(envelope,resultId,v.offerVersionId(),acceptanceId,stamp.dbRecordedAt().toInstant(),1);
        byte[] resultBytes=QuoteCanonicalCodec.result(result);
        return new Acceptance(new QuoteRows.Acceptance(
                0,
                1,
                stamp.dbRecordedAt(),
                a.tenantId(),
                a.partyId(),
                a.submissionId(),
                a.productId(),
                a.offerId(),
                v.offerVersionId(),
                1,
                a.actionId(),
                intent.termsDigest(),
                intent.intentDigest(),
                a.issuer(),
                a.subject(),
                a.evidenceDigest(),
                resultBytes,
                QuoteCanonicalCodec.digest(resultBytes),
                acceptanceId,
                a.issuer(),
                a.subject()),new QuoteRows.Action(
                "ACCEPT",
                a.issuer(),
                a.subject(),
                acceptanceId,
                stamp.dbRecordedAt(),
                a.tenantId(),
                a.partyId(),
                a.submissionId(),
                a.productId(),
                a.offerId(),
                v.offerVersionId(),
                1,
                a.actionId(),
                requestBytes,
                requestDigest,
                resultBytes,
                QuoteCanonicalCodec.digest(resultBytes),
                a.evidenceDigest(),
                resultId,
                a.issuer(),
                a.subject()),result);
    }
}
