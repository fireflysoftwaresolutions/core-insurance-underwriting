/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteResultEnvelope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteCommandResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) Status status,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID actionId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String intentDigest,
        @Schema(nullable=true) QuoteResultEnvelope result) {
    public enum Status {COMMITTED,COMMITTED_RESPONSE_UNAVAILABLE,OUTCOME_UNKNOWN,REFUSED}
    public QuoteCommandResponse {
        Objects.requireNonNull(status);Objects.requireNonNull(actionId);
        if(intentDigest==null || !intentDigest.matches("[0-9a-f]{64}") || (status==Status.COMMITTED)!=(result!=null)
                || result!=null && (!actionId.equals(result.action().actionId()) || !intentDigest.equals(result.action().intentDigest()))) {
            throw new IllegalArgumentException("Exact captured quote outcome identity required");
        }
    }
    @Override public String toString() {return "QuoteCommandResponse["+status+"]";}
}
