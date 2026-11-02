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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** Exact mapped storage/projection columns; repositories grant no generic CRUD surface. */
public final class QuoteRows {
    private QuoteRows() {}
    public sealed interface Scoped permits Control, Version, Acceptance, Action, AcceptMetadata {
        UUID tenantId(); UUID partyId(); UUID submissionId(); UUID productId(); UUID offerId();
        String ownerIssuer(); String ownerSubject();
    }

    @Table(name="offer_control", schema="insurance_quote")
    public record Control(
            @Column("created_action_id") UUID createdActionId,
            @Column("current_offer_version") long currentOfferVersion,
            @Column("control_generation") long controlGeneration,
            @Column("state") String state,
            @Column("acceptance_id") UUID acceptanceId,
            @Column("created_at") OffsetDateTime createdAt,
            @Column("updated_at") OffsetDateTime updatedAt,
            @Column("tenant_id") UUID tenantId,
            @Column("party_id") UUID partyId,
            @Column("submission_id") UUID submissionId,
            @Column("product_id") UUID productId,
            @Id @Column("offer_id") UUID offerId,
            @Column("owner_issuer") String ownerIssuer,
            @Column("owner_subject") String ownerSubject) implements Scoped {
        @Override public String toString() { return "QuoteControlRow"; }
    }

    @Table(name="offer_version", schema="insurance_quote")
    public record Version(
            @Column("offer_version") long offerVersion,
            @Column("canonical_format") String canonicalFormat,
            @Column("canonical_terms") byte[] canonicalTerms,
            @Column("terms_digest") String termsDigest,
            @Column("risk_snapshot") byte[] riskSnapshot,
            @Column("risk_digest") String riskDigest,
            @Column("product_revision_id") UUID productRevisionId,
            @Column("product_digest") String productDigest,
            @Column("provider_version") String providerVersion,
            @Column("rule_set_version") String ruleSetVersion,
            @Column("currency") String currency,
            @Column("tenant_id") UUID tenantId,
            @Column("party_id") UUID partyId,
            @Column("submission_id") UUID submissionId,
            @Column("product_id") UUID productId,
            @Column("offer_id") UUID offerId,
            @Column("base_premium") BigDecimal basePremium,
            @Column("tax") BigDecimal tax,
            @Column("fees") BigDecimal fees,
            @Column("issued_at") OffsetDateTime issuedAt,
            @Column("expires_at") OffsetDateTime expiresAt,
            @Id @Column("offer_version_id") UUID offerVersionId,
            @Column("owner_issuer") String ownerIssuer,
            @Column("owner_subject") String ownerSubject) implements Scoped {
        public Version {
            canonicalTerms = Objects.requireNonNull(canonicalTerms, "canonicalTerms").clone();
            riskSnapshot = Objects.requireNonNull(riskSnapshot, "riskSnapshot").clone();
        }
        @Override public byte[] canonicalTerms() { return canonicalTerms.clone(); }
        @Override public byte[] riskSnapshot() { return riskSnapshot.clone(); }
        @Override public String toString() { return "QuoteVersionRow"; }
    }

    @Table(name="acceptance", schema="insurance_quote")
    public record Acceptance(
            @Column("expected_control_generation") long expectedControlGeneration,
            @Column("resulting_control_generation") long resultingControlGeneration,
            @Column("accepted_at") OffsetDateTime acceptedAt,
            @Column("tenant_id") UUID tenantId,
            @Column("party_id") UUID partyId,
            @Column("submission_id") UUID submissionId,
            @Column("product_id") UUID productId,
            @Column("offer_id") UUID offerId,
            @Column("offer_version_id") UUID offerVersionId,
            @Column("offer_version") long offerVersion,
            @Column("action_id") UUID actionId,
            @Column("terms_digest") String termsDigest,
            @Column("intent_digest") String intentDigest,
            @Column("actor_issuer") String actorIssuer,
            @Column("actor_subject") String actorSubject,
            @Column("authority_evidence_digest") String authorityEvidenceDigest,
            @Column("result_canonical") byte[] resultCanonical,
            @Column("result_digest") String resultDigest,
            @Id @Column("acceptance_id") UUID acceptanceId,
            @Column("owner_issuer") String ownerIssuer,
            @Column("owner_subject") String ownerSubject) implements Scoped {
        public Acceptance {
            resultCanonical = Objects.requireNonNull(resultCanonical, "resultCanonical").clone();
        }
        @Override public byte[] resultCanonical() { return resultCanonical.clone(); }
        @Override public String toString() { return "QuoteAcceptanceRow"; }
    }

    @Table(name="action_result", schema="insurance_quote")
    public record Action(
            @Column("operation") String operation,
            @Column("actor_issuer") String actorIssuer,
            @Column("actor_subject") String actorSubject,
            @Column("acceptance_id") UUID acceptanceId,
            @Column("recorded_at") OffsetDateTime recordedAt,
            @Column("tenant_id") UUID tenantId,
            @Column("party_id") UUID partyId,
            @Column("submission_id") UUID submissionId,
            @Column("product_id") UUID productId,
            @Column("offer_id") UUID offerId,
            @Column("offer_version_id") UUID offerVersionId,
            @Column("offer_version") long offerVersion,
            @Column("action_id") UUID actionId,
            @Column("request_canonical") byte[] requestCanonical,
            @Column("request_digest") String requestDigest,
            @Column("result_canonical") byte[] resultCanonical,
            @Column("result_digest") String resultDigest,
            @Column("authority_evidence_digest") String authorityEvidenceDigest,
            @Id @Column("action_result_id") UUID actionResultId,
            @Column("owner_issuer") String ownerIssuer,
            @Column("owner_subject") String ownerSubject) implements Scoped {
        public Action {
            requestCanonical = Objects.requireNonNull(requestCanonical, "requestCanonical").clone();
            resultCanonical = Objects.requireNonNull(resultCanonical, "resultCanonical").clone();
        }
        @Override public byte[] requestCanonical() { return requestCanonical.clone(); }
        @Override public byte[] resultCanonical() { return resultCanonical.clone(); }
        @Override public String toString() { return "QuoteActionRow"; }
    }

    @Table(name="submission_source", schema="insurance_quote")
    public record SubmissionSource(
            @Id @Column("submission_id") UUID submissionId,
            @Column("tenant_id") UUID tenantId,
            @Column("party_id") UUID partyId,
            @Column("product_id") UUID productId,
            @Column("submission_status") String submissionStatus,
            @Column("line_of_business") String lineOfBusiness,
            @Column("currency") String currency) {
        @Override public String toString() { return "QuoteSubmissionSourceRow"; }
    }

    @Table(name="risk_source", schema="insurance_quote")
    public record RiskSource(
            @Id @Column("submission_risk_id") UUID submissionRiskId,
            @Column("submission_id") UUID submissionId,
            @Column("insured_object_type") String insuredObjectType,
            @Column("party_id") UUID partyId,
            @Column("tenant_id") UUID tenantId,
            @Column("product_id") UUID productId,
            @Column("submission_party_id") UUID submissionPartyId) {
        @Override public String toString() { return "QuoteRiskSourceRow"; }
    }

    @Table(name="value_source", schema="insurance_quote")
    public record ValueSource(
            @Id @Column("statement_of_value_id") UUID statementOfValueId,
            @Column("submission_id") UUID submissionId,
            @Column("line_number") Integer lineNumber,
            @Column("declared_value") BigDecimal declaredValue,
            @Column("currency") String currency,
            @Column("tenant_id") UUID tenantId,
            @Column("product_id") UUID productId,
            @Column("submission_party_id") UUID submissionPartyId) {
        @Override public String toString() { return "QuoteValueSourceRow"; }
    }


    @Table(name="offer_version",schema="insurance_quote")
    public record AcceptMetadata(
            @Id @Column("offer_version_id") UUID offerVersionId,
            @Column("offer_id") UUID offerId,
            @Column("offer_version") long offerVersion,
            @Column("tenant_id") UUID tenantId,
            @Column("party_id") UUID partyId,
            @Column("submission_id") UUID submissionId,
            @Column("product_id") UUID productId,
            @Column("owner_issuer") String ownerIssuer,
            @Column("owner_subject") String ownerSubject,
            @Column("product_revision_id") UUID productRevisionId,
            @Column("product_digest") String productDigest,
            @Column("terms_digest") String termsDigest) implements Scoped {
        @Override public String toString() {return "QuoteAcceptMetadata";}
    }

    public record NativeStamp(UUID contextNonce, OffsetDateTime dbRecordedAt, long dbNowMs, long effectiveUntilMs, UUID productId, UUID offerId) {}
}
