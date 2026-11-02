package io.fireflysoftware.core.insurance.underwriting.interfaces.governance;

import java.util.Objects;

/** The immutable original digest must be retained before the first mutation. */
@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"intent","originalIntentDigest"})
public record GovernedUnderwritingCommandRequest(GovernedUnderwritingIntent intent,String originalIntentDigest){
    public GovernedUnderwritingCommandRequest {Objects.requireNonNull(intent);UnderwritingGovernanceValues.digest(originalIntentDigest);}
    @com.fasterxml.jackson.annotation.JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Unknown underwriting request member");}
    @Override public String toString(){return "GovernedUnderwritingCommandRequest[redacted]";}
}
