package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.UUID;
import java.util.Objects;

/** Read-only original intent; no reservation, permission or stored business effect. */
@io.swagger.v3.oas.annotations.media.Schema(requiredProperties={"operation","actionId","intentDigest"})
public record GovernedUnderwritingPreparedIntent(GovernedUnderwritingIntent.Operation operation,UUID actionId,String intentDigest){
    public GovernedUnderwritingPreparedIntent {Objects.requireNonNull(operation);UnderwritingGovernanceValues.id(actionId);UnderwritingGovernanceValues.digest(intentDigest);}
    @Override public String toString(){return "GovernedUnderwritingPreparedIntent[redacted]";}
}
