package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.Objects;
import java.util.UUID;

@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"state","actionId","intentDigest"})
public record GovernedUnderwritingRecovery(State state,UUID actionId,String intentDigest,GovernedUnderwritingReceipt receipt) {
    public enum State {FOUND,NOT_OBSERVED_AT_THIS_READ,INTENT_CONFLICT}
    public GovernedUnderwritingRecovery {
        Objects.requireNonNull(state);UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.digest(intentDigest);
        if((state==State.FOUND)!=(receipt!=null) || receipt!=null && (!actionId.equals(receipt.actionId()) || !intentDigest.equals(receipt.intentDigest())))throw new IllegalArgumentException("Exact read-only owner recovery identity required");
    }
    @Override public String toString(){return "GovernedUnderwritingRecovery["+state+"]";}
}
