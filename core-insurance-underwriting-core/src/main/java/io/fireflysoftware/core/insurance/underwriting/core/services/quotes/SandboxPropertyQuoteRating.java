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


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferScope;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferTerms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Explicit sample calculator; production provider authority is outside this contract. */
public interface SandboxPropertyQuoteRating {
    record Request(QuoteOfferScope scope, UUID productRevisionId, String productDigest, String riskDigest,
            String currency, BigDecimal insuredLimit, BigDecimal deductible, LocalDate coverageStart, LocalDate coverageEnd) {}

    QuoteOfferTerms rate(Request request);
}
