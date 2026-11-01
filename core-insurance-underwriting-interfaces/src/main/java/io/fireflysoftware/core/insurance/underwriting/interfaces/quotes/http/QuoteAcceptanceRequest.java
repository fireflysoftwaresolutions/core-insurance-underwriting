/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown=true)
public record QuoteAcceptanceRequest(
        @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actionId,
        @NotNull @Min(1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long offerVersion,
        @NotNull @Min(0) @Max(Long.MAX_VALUE-1) @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Long expectedControlGeneration,
        @NotNull @Pattern(regexp="[0-9a-f]{64}") @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String termsDigest) {
    public QuoteAcceptanceIntent toIntent(QuoteOfferScope scope,UUID offerId) {
        return new QuoteAcceptanceIntent(scope,offerId,offerVersion,expectedControlGeneration,termsDigest,actionId);
    }
    @Override public String toString() {return "QuoteAcceptanceRequest[redacted]";}
}
