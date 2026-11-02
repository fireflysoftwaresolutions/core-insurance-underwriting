package io.fireflysoftware.core.insurance.underwriting.models.governance;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

public final class UnderwritingGovernanceRows {
    private UnderwritingGovernanceRows(){}
    public record Source(@Column("tenant_id") UUID tenantId,@Column("submission_id") UUID submissionId,@Column("party_id") UUID partyId,@Column("product_id") UUID productId,
            @Column("submission_risk_id") UUID riskId,@Column("risk_party_id") UUID riskPartyId,@Column("insured_object_type") String objectType,@Column("submission_status") String status,@Column("line_of_business") String lineOfBusiness,@Column("currency") String currency,@Column("statement_of_value_id") UUID valueId,@Column("line_number") int lineNumber,@Column("declared_value") BigDecimal declaredValue,@Column("value_currency") String valueCurrency,@Column("risk_updated_at") OffsetDateTime riskUpdatedAt,@Column("value_updated_at") OffsetDateTime valueUpdatedAt){
        @Override public String toString(){return "UnderwritingGovernanceSource[redacted]";}
    }
    @Table(name="governed_referral",schema="insurance_quote")
    public record ReferralControl(@Id @Column("referral_id") UUID referralId,@Column("tenant_id") UUID tenantId,@Column("submission_id") UUID submissionId,@Column("party_id") UUID partyId,@Column("product_id") UUID productId,@Column("risk_id") UUID riskId,
            @Column("version") long version,@Column("state") String state,@Column("authority_level") String authorityLevel,
            @Column("creator_issuer") String creatorIssuer,@Column("creator_subject") String creatorSubject,@Column("creator_staff_realm") String creatorStaffRealm,@Column("creator_staff_id") UUID creatorStaffId,@Column("approver_issuer") String approverIssuer,@Column("approver_subject") String approverSubject,@Column("approver_staff_realm") String approverStaffRealm,@Column("approver_staff_id") UUID approverStaffId,
            @Column("created_action_id") UUID createdActionId,@Column("current_action_id") UUID currentActionId,@Column("current_event_id") UUID currentEventId,@Column("current_receipt_digest") String currentReceiptDigest,@Column("approved_source_digest") String approvedSourceDigest,@Column("current_updated_at") OffsetDateTime currentUpdatedAt){
        @Override public String toString(){return "GovernedReferralControl[redacted]";}
    }
    @Table(name="governed_underwriting_decision",schema="insurance_quote")
    public record DecisionControl(@Id @Column("decision_id") UUID decisionId,@Column("tenant_id") UUID tenantId,@Column("submission_id") UUID submissionId,@Column("party_id") UUID partyId,@Column("product_id") UUID productId,@Column("risk_id") UUID riskId,
            @Column("referral_id") UUID referralId,@Column("approval_action_id") UUID approvalActionId,@Column("approval_receipt_digest") String approvalReceiptDigest,@Column("action_id") UUID actionId,@Column("event_id") UUID eventId,@Column("receipt_digest") String receiptDigest){
        @Override public String toString(){return "GovernedUnderwritingDecisionControl[redacted]";}
    }
    @Table(name="underwriting_action",schema="insurance_quote")
    public record Action(@Id @Column("operation_id") UUID operationId,@Column("tenant_id") UUID tenantId,@Column("owner_issuer") String ownerIssuer,@Column("owner_subject") String ownerSubject,@Column("client_id") String clientId,@Column("platform_tenant") String platformTenant,@Column("staff_realm") String staffRealm,@Column("staff_id") UUID staffId,
            @Column("action_id") UUID actionId,@Column("operation") String operation,@Column("intent_digest") String intentDigest,@Column("intent_json") String intentJson,@Column("submission_id") UUID submissionId,@Column("party_id") UUID partyId,@Column("product_id") UUID productId,@Column("risk_id") UUID riskId,@Column("source_json") String sourceJson,@Column("source_digest") String sourceDigest,@Column("authority_level") String authorityLevel,
            @Column("referral_id") UUID referralId,@Column("decision_id") UUID decisionId,@Column("result_json") String resultJson,@Column("receipt_digest") String receiptDigest,
            @Column("event_id") UUID eventId,@Column("event_record_hash") String eventRecordHash,@Column("evidence_digest") String evidenceDigest,
            @Column("binding_id") UUID bindingId,@Column("binding_version") long bindingVersion,@Column("binding_digest") String bindingDigest,
            @Column("authorization_policy_revision") String authorizationPolicyRevision,@Column("authorization_decision_id") String authorizationDecisionId,@Column("admission_policy_revision") String admissionPolicyRevision,@Column("admission_decision_id") String admissionDecisionId,@Column("decision_evidence_digest") String decisionEvidenceDigest,
            @Column("process_id") UUID processId,@Column("correlation_id") UUID correlationId){
        @Override public String toString(){return "GovernedUnderwritingAction[redacted]";}
    }
}
