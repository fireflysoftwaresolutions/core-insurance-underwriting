package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.Objects;
import java.util.UUID;

/** Selectors and original intent only; no tenant mapping, staff role, authority reference or approval is body supplied. */
@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"operation","actionId","submissionId","riskId","expectedReferralVersion"})
public record GovernedUnderwritingIntent(Operation operation,UUID actionId,UUID submissionId,UUID riskId,UUID referralId,
        long expectedReferralVersion,String expectedReceiptDigest,String reason) {
    public enum Operation {REFERRAL_CREATE,REFERRAL_APPROVE,DECISION_RECORD}
    public GovernedUnderwritingIntent {
        Objects.requireNonNull(operation);UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.id(submissionId);UnderwritingGovernanceValues.id(riskId);
        if(operation==Operation.REFERRAL_CREATE){if(referralId!=null || expectedReferralVersion!=0 || expectedReceiptDigest!=null)throw invalid();UnderwritingGovernanceValues.text(reason,500);}
        else {UnderwritingGovernanceValues.id(referralId);UnderwritingGovernanceValues.digest(expectedReceiptDigest);if(reason!=null || expectedReferralVersion!=(operation==Operation.REFERRAL_APPROVE?0:1))throw invalid();}
    }
    public String intentDigest(UUID resourceTenantId){return UnderwritingGovernanceValues.hash("CORE_UNDERWRITING_ORIGINAL_INTENT_V1",UnderwritingGovernanceValues.id(resourceTenantId).toString(),operation.name(),actionId.toString(),submissionId.toString(),riskId.toString(),
            referralId==null?"":referralId.toString(),Long.toString(expectedReferralVersion),expectedReceiptDigest==null?"":expectedReceiptDigest,reason==null?"":reason);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact operation-specific original underwriting intent required");}
    @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Unknown underwriting request member");}
    @Override public String toString(){return "GovernedUnderwritingIntent["+operation+"]";}
}
