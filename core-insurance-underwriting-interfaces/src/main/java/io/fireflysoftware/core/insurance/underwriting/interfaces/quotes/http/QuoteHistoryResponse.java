/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.http;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

public record QuoteHistoryResponse(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) List<QuoteReadResponse.Entry> entries) {
    public QuoteHistoryResponse {entries=List.copyOf(Objects.requireNonNull(entries));}
    @Override public String toString() {return "QuoteHistoryResponse[redacted]";}
}
