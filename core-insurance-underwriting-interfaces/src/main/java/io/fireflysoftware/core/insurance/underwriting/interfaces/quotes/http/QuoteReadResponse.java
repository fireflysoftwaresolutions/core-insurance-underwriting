/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteReadResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) State state,@Schema(nullable=true) Entry entry) {
    public enum State {FOUND,NOT_OBSERVED_AT_THIS_READ}
    public QuoteReadResponse {
        Objects.requireNonNull(state);
        if((state==State.FOUND)!=(entry!=null))throw new IllegalArgumentException("Exact quote read observation required");
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) QuoteOfferVersion offer,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED) String termsDigest,@Schema(nullable=true) QuoteAcceptanceReceipt receipt) {
        public Entry {
            Objects.requireNonNull(offer);
            if(!offer.termsDigest().equals(termsDigest))throw new IllegalArgumentException("Exact owner quote terms digest required");
        }
        @Override public String toString() {return "QuoteReadEntry[redacted]";}
    }
    @Override public String toString() {return "QuoteReadResponse["+state+"]";}
}
