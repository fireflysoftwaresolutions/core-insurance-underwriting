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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record QuoteOfferTerms(String currency, BigDecimal basePremium, BigDecimal tax, BigDecimal fees,
        Map<String, String> coverageTerms, UUID productRevisionId, String productDigest, String riskDigest,
        String providerVersion, String ruleSetVersion) {
    public QuoteOfferTerms {
        var unit = QuoteValues.currency(currency);
        basePremium = QuoteValues.money(basePremium, unit, true);
        tax = QuoteValues.money(tax, unit, false);
        fees = QuoteValues.money(fees, unit, false);
        QuoteValues.money(basePremium.add(tax).add(fees), unit, true);
        Objects.requireNonNull(productRevisionId, "productRevisionId");
        productDigest = QuoteValues.digest(productDigest);
        riskDigest = QuoteValues.digest(riskDigest);
        providerVersion = QuoteValues.version(providerVersion);
        ruleSetVersion = QuoteValues.version(ruleSetVersion);
        Objects.requireNonNull(coverageTerms, "coverageTerms");
        if (coverageTerms.isEmpty() || coverageTerms.size() > 32) throw new IllegalArgumentException("Bounded coverage terms required");
        var copy = new TreeMap<String, String>();
        int bytes = 0;
        for (var entry : coverageTerms.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.matches("[a-z][a-zA-Z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid coverage term key");
            String value = QuoteValues.text(entry.getValue(), 4096);
            bytes += key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
            copy.put(key, value);
        }
        if (bytes > 32768) throw new IllegalArgumentException("Coverage terms exceed total size bound");
        coverageTerms = Collections.unmodifiableMap(copy);
    }

    public BigDecimal totalPremium() { return basePremium.add(tax).add(fees); }
}
