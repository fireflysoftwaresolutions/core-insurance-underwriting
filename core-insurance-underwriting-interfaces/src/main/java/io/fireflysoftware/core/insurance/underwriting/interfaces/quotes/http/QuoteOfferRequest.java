/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown=true)
public record QuoteOfferRequest(
        @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actionId,
        @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LocalDate coverageStart,
        @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) LocalDate coverageEnd,
        @NotNull @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID expectedProductRevisionId,
        @NotNull @Pattern(regexp="[0-9a-f]{64}") @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String expectedProductDigest) {
    public QuoteOfferRequestIntent toIntent(QuoteOfferScope scope) {
        return new QuoteOfferRequestIntent(scope,actionId,coverageStart,coverageEnd,expectedProductRevisionId,expectedProductDigest);
    }
    @Override public String toString() {return "QuoteOfferRequest[redacted]";}
}
