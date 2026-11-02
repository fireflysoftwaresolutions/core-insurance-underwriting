/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.SandboxPropertyQuoteRating;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferTerms;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;

public final class SandboxPropertyQuoteRatingImpl implements SandboxPropertyQuoteRating {
    @Override
    public QuoteOfferTerms rate(Request request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.scope(), "scope");
        Objects.requireNonNull(request.coverageStart(), "coverageStart");
        Objects.requireNonNull(request.coverageEnd(), "coverageEnd");
        if (!"USD".equals(request.currency())) throw new IllegalArgumentException("Sandbox property rating supports USD only");
        BigDecimal limit = amount(request.insuredLimit());
        BigDecimal deductible = amount(request.deductible());
        if (limit.signum() <= 0 || limit.compareTo(new BigDecimal("1000000.00")) > 0
                || deductible.signum() < 0 || deductible.compareTo(limit) > 0) {
            throw new IllegalArgumentException("Unsupported sandbox property risk range");
        }
        long days = ChronoUnit.DAYS.between(request.coverageStart(), request.coverageEnd());
        if (days <= 0 || days > 366 || request.coverageStart().getYear() < 1 || request.coverageEnd().getYear() > 9999) {
            throw new IllegalArgumentException("Sandbox property period must be one to 366 days");
        }
        BigDecimal base = limit.multiply(new BigDecimal("0.01")).max(new BigDecimal("25.00")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal tax = base.multiply(new BigDecimal("0.05")).setScale(2, RoundingMode.HALF_UP);
        var coverage = Map.of(
                "coverageCode", "SANDBOX_PROPERTY_DAMAGE",
                "wording", "Illustrative sandbox property-damage offer; acceptance does not bind insurance coverage.",
                "exclusions", "Illustrative only: flood, war, intentional loss, and liability are excluded.",
                "insuredLimit", limit.toPlainString(),
                "deductible", deductible.toPlainString(),
                "coverageStart", request.coverageStart().toString(),
                "coverageEnd", request.coverageEnd().toString());
        return new QuoteOfferTerms("USD", base, tax, new BigDecimal("10.00"), coverage,
                request.productRevisionId(), request.productDigest(), request.riskDigest(),
                "firefly-sandbox-property-1", "illustrative-property-1");
    }
    private static BigDecimal amount(BigDecimal value) {
        Objects.requireNonNull(value, "amount");
        if (value.precision() > 38 || value.scale() < -19 || value.scale() > 19) {
            throw new IllegalArgumentException("Decimal input exceeds compact bounds");
        }
        try {
            BigDecimal normalized = value.setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.precision() > 19) throw new IllegalArgumentException("Sandbox amount exceeds precision");
            return normalized;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Sandbox amount exceeds USD precision", error);
        }
    }
}
