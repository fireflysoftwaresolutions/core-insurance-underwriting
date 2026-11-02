package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.Objects;
import java.util.UUID;

@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"status","actionId","intentDigest"})
public record GovernedUnderwritingCommandResponse(Status status,UUID actionId,String intentDigest,GovernedUnderwritingReceipt receipt) {
    public enum Status {COMMITTED,COMMITTED_RESPONSE_UNAVAILABLE,OUTCOME_UNKNOWN,REFUSED}
    public GovernedUnderwritingCommandResponse {
        Objects.requireNonNull(status);UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.digest(intentDigest);
        if((status==Status.COMMITTED)!=(receipt!=null) || receipt!=null && (!actionId.equals(receipt.actionId()) || !intentDigest.equals(receipt.intentDigest())))throw new IllegalArgumentException("Exact owner outcome and original identity required");
    }
    @Override public String toString(){return "GovernedUnderwritingCommandResponse["+status+"]";}
}
