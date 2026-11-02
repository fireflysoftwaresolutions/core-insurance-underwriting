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


package io.fireflysoftware.core.insurance.underwriting.core.quotes;

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.SandboxPropertyQuoteRating;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.SandboxPropertyQuoteRatingImpl;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static io.fireflysoftware.core.insurance.underwriting.core.quotes.QuoteOfferFoundationTest.*;

class SandboxPropertyQuoteRatingTest {
    private final SandboxPropertyQuoteRating rating = new SandboxPropertyQuoteRatingImpl();
    private SandboxPropertyQuoteRating.Request request(String currency,BigDecimal amount,BigDecimal deductible) {
        return new SandboxPropertyQuoteRating.Request(SCOPE,PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),currency,amount,deductible,LocalDate.of(2026,11,1),LocalDate.of(2027,11,1));
    }
    @Test void samplePremiumAndTaxAreDeterministic() {
        var req=request("USD",new BigDecimal("10000.00"),new BigDecimal("500.00"));var a=rating.rate(req);var b=rating.rate(req);
        assertEquals(a,b);assertEquals(new BigDecimal("100.00"),a.basePremium());assertEquals(new BigDecimal("5.00"),a.tax());assertEquals(new BigDecimal("10.00"),a.fees());assertEquals(new BigDecimal("115.00"),a.totalPremium());
    }
    @Test void minimumSamplePremiumIsExplicit() {assertEquals(new BigDecimal("25.00"),rating.rate(request("USD",new BigDecimal("1000.00"),BigDecimal.ZERO)).basePremium());}
    @Test void ratingRetainsCoverageAndConfigurationPins() {
        var a=rating.rate(request("USD",new BigDecimal("10000.00"),new BigDecimal("500.00")));
        assertEquals(PRODUCT_REVISION,a.productRevisionId());assertEquals("a".repeat(64),a.productDigest());assertEquals("b".repeat(64),a.riskDigest());
        assertEquals("firefly-sandbox-property-1",a.providerVersion());assertEquals("illustrative-property-1",a.ruleSetVersion());
        assertEquals("10000.00",a.coverageTerms().get("insuredLimit"));assertEquals("500.00",a.coverageTerms().get("deductible"));
        assertTrue(a.coverageTerms().get("wording").contains("does not bind insurance coverage"));assertEquals("2026-11-01",a.coverageTerms().get("coverageStart"));
    }
    @Test void unsupportedCurrencyDoesNotFallback() {assertThrows(IllegalArgumentException.class,()->rating.rate(request("EUR",BigDecimal.TEN,BigDecimal.ZERO)));}
    @Test void unsupportedRiskRangeDoesNotFallback() {assertThrows(IllegalArgumentException.class,()->rating.rate(request("USD",new BigDecimal("1000000.01"),BigDecimal.ZERO)));}
    @Test void deductibleCannotExceedInsuredLimit() {assertThrows(IllegalArgumentException.class,()->rating.rate(request("USD",new BigDecimal("100.00"),new BigDecimal("101.00"))));}
    @Test void excessiveCompactScaleDoesNotNormalizeToAValidSandboxAmount() {
        assertThrows(IllegalArgumentException.class,()->rating.rate(request("USD",new BigDecimal("1000.00").setScale(20),BigDecimal.ZERO)));
    }
    @Test void compactZeroExponentDoesNotNormalizeToAValidDeductible() {
        assertThrows(IllegalArgumentException.class,()->rating.rate(request("USD",new BigDecimal("1000.00"),new BigDecimal("0E+20"))));
    }
    @Test void oversizedCompactCoefficientIsRefusedAtInputBoundary() {
        var error=assertThrows(IllegalArgumentException.class,()->rating.rate(request("USD",new BigDecimal("123456789012345678901234567890123456789"),BigDecimal.ZERO)));
        assertEquals("Decimal input exceeds compact bounds",error.getMessage());
    }
}
