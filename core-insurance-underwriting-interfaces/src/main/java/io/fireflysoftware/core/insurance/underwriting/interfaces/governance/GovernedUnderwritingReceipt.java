package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.Objects;
import java.util.UUID;

@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"actionId","intentDigest","operation","referralId","referralVersion","receiptDigest"})
public record GovernedUnderwritingReceipt(UUID actionId,String intentDigest,GovernedUnderwritingIntent.Operation operation,UUID referralId,
        long referralVersion,UUID decisionId,String receiptDigest) {
    public GovernedUnderwritingReceipt {
        UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.digest(intentDigest);Objects.requireNonNull(operation);UnderwritingGovernanceValues.id(referralId);UnderwritingGovernanceValues.digest(receiptDigest);
        if(referralVersion!=(operation==GovernedUnderwritingIntent.Operation.REFERRAL_CREATE?0:1))throw new IllegalArgumentException("Exact governed referral version required");
        if(operation==GovernedUnderwritingIntent.Operation.DECISION_RECORD)UnderwritingGovernanceValues.id(decisionId);else if(decisionId!=null)throw new IllegalArgumentException("Unexpected decision selector");
    }
    @Override public String toString(){return "GovernedUnderwritingReceipt[redacted]";}
}
