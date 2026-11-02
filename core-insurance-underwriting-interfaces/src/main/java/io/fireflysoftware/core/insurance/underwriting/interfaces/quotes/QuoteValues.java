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


package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.CharBuffer;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

final class QuoteValues {
    private QuoteValues() {}

    static String digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256 digest required");
        return value;
    }

    static String version(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,100}")) throw new IllegalArgumentException("Bounded version required");
        return value;
    }

    static String text(String value, int maxBytes) {
        Objects.requireNonNull(value, "text");
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        try {
            var bytes = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(normalized));
            if (normalized.isBlank() || bytes.remaining() > maxBytes || normalized.indexOf(0) >= 0) {
                throw new IllegalArgumentException("Text is empty or exceeds its bound");
            }
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("Text must be valid Unicode", error);
        }
        return normalized;
    }

    static Currency currency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) throw new IllegalArgumentException("ISO currency required");
        Currency currency = Currency.getInstance(value);
        if (currency.getDefaultFractionDigits() < 0 || currency.getDefaultFractionDigits() > 4) {
            throw new IllegalArgumentException("Unsupported currency precision");
        }
        return currency;
    }

    static BigDecimal money(BigDecimal value, Currency currency, boolean positive) {
        Objects.requireNonNull(value, "money");
        if (value.precision() > 38 || value.scale() < -19 || value.scale() > 19) {
            throw new IllegalArgumentException("Decimal input exceeds compact bounds");
        }
        try {
            BigDecimal normalized = value.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
            if (normalized.signum() < 0 || (positive && normalized.signum() == 0) || normalized.precision() > 19) {
                throw new IllegalArgumentException("Money is outside its permitted range");
            }
            return normalized;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("Money exceeds currency precision", error);
        }
    }

    static Instant instant(Instant value) {
        Objects.requireNonNull(value, "UTC instant");
        if (value.getNano() % 1000 != 0 || value.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || !value.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
            throw new IllegalArgumentException("UTC instant must fit database microsecond precision");
        }
        return value;
    }
}
