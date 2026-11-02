/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteResultEnvelope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteRecoveryResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) State state,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String intentDigest,
        @Schema(nullable=true) QuoteResultEnvelope result) {
    public enum State {FOUND,NOT_OBSERVED_AT_THIS_READ,INTENT_CONFLICT}
    public QuoteRecoveryResponse {
        Objects.requireNonNull(state);Objects.requireNonNull(actionId);
        if(intentDigest==null || !intentDigest.matches("[0-9a-f]{64}") || (state==State.FOUND)!=(result!=null)
                || result!=null && (!actionId.equals(result.action().actionId()) || !intentDigest.equals(result.action().intentDigest()))) {
            throw new IllegalArgumentException("Exact quote recovery identity required");
        }
    }
    @Override public String toString() {return "QuoteRecoveryResponse["+state+"]";}
}
