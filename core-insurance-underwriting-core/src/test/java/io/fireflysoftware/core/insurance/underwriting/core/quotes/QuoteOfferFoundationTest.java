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

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteOfferFoundationTest {
    static final UUID TENANT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID SUBMISSION = UUID.fromString("10000000-0000-4000-8000-000000000002");
    static final UUID PARTY = UUID.fromString("10000000-0000-4000-8000-000000000003");
    static final UUID PRODUCT = UUID.fromString("10000000-0000-4000-8000-000000000004");
    static final UUID OFFER = UUID.fromString("10000000-0000-4000-8000-000000000005");
    static final UUID ACTION = UUID.fromString("10000000-0000-4000-8000-000000000006");
    static final UUID PRODUCT_REVISION = UUID.fromString("10000000-0000-4000-8000-000000000007");
    static final Instant ISSUED = Instant.parse("2026-10-25T12:00:00Z");
    static final Instant EXPIRES = ISSUED.plusSeconds(1200);
    static final QuoteOfferScope SCOPE = new QuoteOfferScope(TENANT, SUBMISSION, PARTY, PRODUCT);
    static QuoteOfferTerms terms(Map<String,String> wording) {
        return new QuoteOfferTerms("USD", new BigDecimal("100.00"), new BigDecimal("5.00"),
                new BigDecimal("10.00"), wording, PRODUCT_REVISION, "a".repeat(64), "b".repeat(64),
                "sandbox-property-1", "illustrative-property-1");
    }
    static QuoteOfferVersion offer() { return offer(QuoteOfferVersion.State.OFFERED, 0); }
    static QuoteOfferVersion offer(QuoteOfferVersion.State state, long generation) {
        return new QuoteOfferVersion(SCOPE,OFFER,1,generation,state,terms(Map.of("coverage","Sandbox property damage")),ISSUED,EXPIRES);
    }
    static QuoteAcceptanceIntent intent() { return intent(SCOPE,OFFER,1,0,offer().termsDigest(),ACTION); }
    static QuoteAcceptanceIntent intent(QuoteOfferScope scope, UUID id, long version,long generation,String digest,UUID action) {
        return new QuoteAcceptanceIntent(scope,id,version,generation,digest,action);
    }
    static void refused(String code, org.junit.jupiter.api.function.Executable operation) {
        var ex=assertThrows(IllegalStateException.class,operation);
        assertEquals(code,ex.getMessage());
    }
    @Test void exactCurrentUnexpiredOfferPassesLocalChecks() {
        assertDoesNotThrow(() -> QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(),ISSUED.plusSeconds(1),EXPIRES));
    }
    @Test void preciseExpiryEqualityCannotNewlyAccept() {
        refused("OFFER_EXPIRED",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(),EXPIRES,EXPIRES.plusSeconds(1)));
    }
    @Test void oneMicrosecondBeforeExpiryCanAccept() {
        assertDoesNotThrow(()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(),EXPIRES.minusNanos(1000),EXPIRES));
    }
    @Test void writeAuthorityExpiryEqualityRefuses() {
        refused("AUTHORITY_EXPIRED",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(),ISSUED.plusSeconds(1),ISSUED.plusSeconds(1)));
    }
    @Test void acceptanceBeforeIssueRefuses() {
        refused("CLOCK_BEFORE_ISSUE",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(),ISSUED.minusSeconds(1),EXPIRES));
    }
    @Test void anotherPartyCannotUseTheOffer() {
        var other=new QuoteOfferScope(TENANT,SUBMISSION,UUID.randomUUID(),PRODUCT);
        refused("SCOPE_MISMATCH",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(other,OFFER,1,0,offer().termsDigest(),ACTION),ISSUED,EXPIRES));
    }
    @Test void anotherOfferCannotUseSameTermsDigest() {
        refused("OFFER_MISMATCH",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(SCOPE,UUID.randomUUID(),1,0,offer().termsDigest(),ACTION),ISSUED,EXPIRES));
    }
    @Test void staleVersionRefuses() {
        refused("STALE_VERSION",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(SCOPE,OFFER,2,0,offer().termsDigest(),ACTION),ISSUED,EXPIRES));
    }
    @Test void staleControlRefuses() {
        refused("STALE_CONTROL",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(SCOPE,OFFER,1,1,offer().termsDigest(),ACTION),ISSUED,EXPIRES));
    }
    @Test void changedTermsDigestRefuses() {
        refused("TERMS_MISMATCH",()->QuoteOfferAcceptance.requireNewAcceptance(offer(),intent(SCOPE,OFFER,1,0,"c".repeat(64),ACTION),ISSUED,EXPIRES));
    }
    @Test void acceptedOfferCannotNewlyAccept() {
        refused("NOT_OFFERED",()->QuoteOfferAcceptance.requireNewAcceptance(offer(QuoteOfferVersion.State.ACCEPTED,0),intent(),ISSUED,EXPIRES));
    }
    @Test void withdrawnOfferCannotNewlyAccept() {
        refused("NOT_OFFERED",()->QuoteOfferAcceptance.requireNewAcceptance(offer(QuoteOfferVersion.State.WITHDRAWN,0),intent(),ISSUED,EXPIRES));
    }
    @Test void retainedReceiptRecoversAfterOriginalOfferExpiryWithFreshRead() {
        var receipt=new QuoteAcceptanceReceipt(intent(),ISSUED.plusSeconds(10),1);
        assertSame(receipt,QuoteOfferAcceptance.recover(receipt,intent(),EXPIRES.plusSeconds(10),EXPIRES.plusSeconds(60)));
    }
    @Test void recoveryRequiresCurrentReadHorizon() {
        var receipt=new QuoteAcceptanceReceipt(intent(),ISSUED.plusSeconds(10),1);
        refused("AUTHORITY_EXPIRED",()->QuoteOfferAcceptance.recover(receipt,intent(),EXPIRES,EXPIRES));
    }
    @Test void recoveryRejectsAnotherAction() {
        var receipt=new QuoteAcceptanceReceipt(intent(),ISSUED.plusSeconds(10),1);
        var changed=intent(SCOPE,OFFER,1,0,offer().termsDigest(),UUID.randomUUID());
        refused("INTENT_CONFLICT",()->QuoteOfferAcceptance.recover(receipt,changed,EXPIRES,EXPIRES.plusSeconds(60)));
    }
    @Test void recoveryRejectsChangedControlIntent() {
        var receipt=new QuoteAcceptanceReceipt(intent(),ISSUED.plusSeconds(10),1);
        var changed=intent(SCOPE,OFFER,1,1,offer().termsDigest(),ACTION);
        refused("INTENT_CONFLICT",()->QuoteOfferAcceptance.recover(receipt,changed,EXPIRES,EXPIRES.plusSeconds(60)));
    }
    @Test void retainedReceiptRejectsWrongResultingGeneration() {
        assertThrows(IllegalArgumentException.class,()->new QuoteAcceptanceReceipt(intent(),ISSUED,2));
    }
    @Test void canonicalDigestIgnoresMapInsertionOrderAndDecimalScale() {
        var first=new LinkedHashMap<String,String>();first.put("wording","Fire damage");first.put("exclusion","Flood");
        var second=new LinkedHashMap<String,String>();second.put("exclusion","Flood");second.put("wording","Fire damage");
        var a=new QuoteOfferVersion(SCOPE,OFFER,1,0,QuoteOfferVersion.State.OFFERED,terms(first),ISSUED,EXPIRES);
        var raw=terms(second);var normalized=new QuoteOfferTerms("USD",new BigDecimal("100"),new BigDecimal("5.0"),new BigDecimal("10.000"),second,PRODUCT_REVISION,raw.productDigest(),raw.riskDigest(),raw.providerVersion(),raw.ruleSetVersion());
        var c=new QuoteOfferVersion(SCOPE,OFFER,1,4,QuoteOfferVersion.State.WITHDRAWN,normalized,ISSUED,EXPIRES);
        assertEquals(a.termsDigest(),c.termsDigest());assertEquals(64,a.termsDigest().length());
    }
    @Test void fullWordingChangesDigest() {
        var changed=new QuoteOfferVersion(SCOPE,OFFER,1,0,QuoteOfferVersion.State.OFFERED,terms(Map.of("coverage","Changed cover")),ISSUED,EXPIRES);
        assertNotEquals(offer().termsDigest(),changed.termsDigest());
    }
    @Test void scopeAndOfferVersionAreDigestBound() {
        var a=offer();var changed=new QuoteOfferVersion(SCOPE,OFFER,2,0,a.state(),a.terms(),ISSUED,EXPIRES);
        assertNotEquals(a.termsDigest(),changed.termsDigest());
        var other=new QuoteOfferScope(UUID.randomUUID(),SUBMISSION,PARTY,PRODUCT);
        assertNotEquals(a.termsDigest(),new QuoteOfferVersion(other,OFFER,1,0,a.state(),a.terms(),ISSUED,EXPIRES).termsDigest());
    }
    @Test void expiryIsDigestBound() {
        var a=offer();assertNotEquals(a.termsDigest(),new QuoteOfferVersion(SCOPE,OFFER,1,0,a.state(),a.terms(),ISSUED,EXPIRES.plusSeconds(1)).termsDigest());
    }
    @Test void wordingIsDefensivelyCopiedAndImmutable() {
        var map=new HashMap<String,String>();map.put("coverage","Fire");var terms=terms(map);map.put("coverage","Flood");
        assertEquals("Fire",terms.coverageTerms().get("coverage"));assertThrows(UnsupportedOperationException.class,()->terms.coverageTerms().put("other","x"));
    }
    @Test void unicodeCanonicalEquivalenceHasOneDigest() {
        var a=new QuoteOfferVersion(SCOPE,OFFER,1,0,QuoteOfferVersion.State.OFFERED,terms(Map.of("wording","Café")),ISSUED,EXPIRES);
        var c=new QuoteOfferVersion(SCOPE,OFFER,1,0,QuoteOfferVersion.State.OFFERED,terms(Map.of("wording","Cafe\u0301")),ISSUED,EXPIRES);
        assertEquals(a.termsDigest(),c.termsDigest());
    }
    @Test void invalidSurrogateCannotEnterCanonicalTerms() {
        assertThrows(IllegalArgumentException.class,()->terms(Map.of("coverage","bad\uD800")));
    }
    @Test void overlongWordingIsRejected() {assertThrows(IllegalArgumentException.class,()->terms(Map.of("coverage","a".repeat(4097))));}
    @Test void unknownCurrencyIsRejected() {assertThrows(IllegalArgumentException.class,()->new QuoteOfferTerms("ZZZ",BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1"));}
    @Test void unsupportedCurrencyPrecisionIsRejected() {assertThrows(IllegalArgumentException.class,()->new QuoteOfferTerms("USD",new BigDecimal("1.001"),BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1"));}
    @Test void negativePremiumIsRejected() {assertThrows(IllegalArgumentException.class,()->new QuoteOfferTerms("USD",BigDecimal.ONE.negate(),BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1"));}
    @Test void totalPremiumIncludesTaxAndFeesExactly() {assertEquals(new BigDecimal("115.00"),offer().terms().totalPremium());}
    @Test void unboundedOfferHorizonIsRejected() {var a=offer();assertThrows(IllegalArgumentException.class,()->new QuoteOfferVersion(SCOPE,OFFER,1,0,a.state(),a.terms(),ISSUED,ISSUED.plusSeconds(86401)));}
    @Test void nanosecondsBeyondDatabasePrecisionAreRejected() {var a=offer();assertThrows(IllegalArgumentException.class,()->new QuoteOfferVersion(SCOPE,OFFER,1,0,a.state(),a.terms(),ISSUED,EXPIRES.plusNanos(1)));}
    @Test void missingScopeCannotBeRepaired() {assertThrows(NullPointerException.class,()->new QuoteOfferScope(null,SUBMISSION,PARTY,PRODUCT));}
    @Test void actionDigestBindsExactAcceptedIntent() {assertEquals(intent().intentDigest(),intent().intentDigest());assertNotEquals(intent().intentDigest(),intent(SCOPE,OFFER,1,0,offer().termsDigest(),UUID.randomUUID()).intentDigest());}
    @Test void canonicalEncodingMatchesIndependentCrossLanguageVector() {
        assertEquals("fbe5bf682c9f7c428ad271835d62bf2aa4c1aa83cd401f73fa20eb21e55f4b0a",offer().termsDigest());
    }
    @Test void excessiveCompactScaleIsRejectedBeforeNormalization() {
        assertThrows(IllegalArgumentException.class,()->new QuoteOfferTerms("USD",new BigDecimal("1.00").setScale(20),BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1"));
    }
    @Test void compactZeroExponentOutsideBoundIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new QuoteOfferTerms("USD",BigDecimal.ONE,new BigDecimal("0E+20"),BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1"));
    }
    @Test void coefficientPrecisionUsesCompactInputRefusal() {
        var error=assertThrows(IllegalArgumentException.class,()->new QuoteOfferTerms("USD",new BigDecimal("123456789012345678901234567890123456789"),BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1"));
        assertEquals("Decimal input exceeds compact bounds",error.getMessage());
    }
    @Test void maximumCanonicalMoneyAndOrdinaryTrailingZerosRemainValid() {
        var terms=new QuoteOfferTerms("USD",new BigDecimal("99999999999999999.99"),BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1");
        assertEquals(new BigDecimal("99999999999999999.99"),terms.totalPremium());
        assertEquals(new BigDecimal("100.00"),new QuoteOfferTerms("USD",new BigDecimal("1E+2"),BigDecimal.ZERO,BigDecimal.ZERO,Map.of("coverage","x"),PRODUCT_REVISION,"a".repeat(64),"b".repeat(64),"sandbox-1","rule-1").basePremium());
    }
}
