package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteRequestRiskTest {
    private static UUID id(int n) { return new UUID(0,n); }
    private static QuoteOfferScope scope() { return new QuoteOfferScope(id(1),id(2),id(3),id(4)); }
    private static QuoteOfferRequestIntent request() {
        return new QuoteOfferRequestIntent(scope(),id(5),LocalDate.of(2026,10,26),LocalDate.of(2027,10,26),id(6),"a".repeat(64));
    }
    private static QuoteRiskSnapshot.Submission parent() { return new QuoteRiskSnapshot.Submission(scope(),SubmissionStatus.RECEIVED,LineOfBusiness.PROPERTY,"USD"); }
    private static QuoteRiskSnapshot.Risk risk() { return new QuoteRiskSnapshot.Risk(id(7),id(2),InsuredObjectType.PROPERTY,id(3)); }
    private static QuoteRiskSnapshot.Value value() { return new QuoteRiskSnapshot.Value(id(8),id(2),1,new BigDecimal("100000.0000"),"USD"); }
    private static QuoteRiskSnapshot snapshot() { return QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(value())); }
    @Test void offerRequestRoundTripBindsAllSuppliedFields() {
        var v=request();var bytes=QuoteCanonicalCodec.offerRequest(v);
        assertEquals(v,QuoteCanonicalCodec.readOfferRequest(bytes));assertEquals(v.intentDigest(),QuoteCanonicalCodec.digest(bytes));
        assertEquals("becf7dfa710d28fcc86b4f7cda92f6a8b292d7ba7498db97fcdf2b2390b18f22",v.intentDigest());
        assertEquals(List.of("FIREFLY_INSURANCE_QUOTE_OFFER_REQUEST_1",id(1).toString(),id(2).toString(),id(3).toString(),id(4).toString(),id(5).toString(),"2026-10-26","2027-10-26",id(6).toString(),"a".repeat(64)),QuoteCanonicalCodec.decodeFields(bytes));
    }
    @Test void offerActionDerivesExactRequestBinding() {
        var action=QuoteActionEnvelope.offer("issuer","owner",id(9),request());
        assertTrue(action.matchesOffer(request()));assertEquals(QuoteActionEnvelope.Operation.OFFER,action.operation());
        assertEquals(0,action.expectedControlGeneration());assertEquals(1,action.offerVersion());assertEquals(request().intentDigest(),action.intentDigest());
        assertFalse(action.matchesOffer(new QuoteOfferRequestIntent(scope(),id(10),request().coverageStart(),request().coverageEnd(),id(6),"a".repeat(64))));
    }
    @Test void scopeAndExpectedRevisionChangesDoNotMatchOffer() {
        var action=QuoteActionEnvelope.offer("issuer","owner",id(9),request());
        assertFalse(action.matchesOffer(new QuoteOfferRequestIntent(new QuoteOfferScope(id(11),id(2),id(3),id(4)),id(5),request().coverageStart(),request().coverageEnd(),id(6),"a".repeat(64))));
        assertFalse(action.matchesOffer(new QuoteOfferRequestIntent(scope(),id(5),request().coverageStart(),request().coverageEnd(),id(11),"a".repeat(64))));
        assertFalse(action.matchesOffer(null));
    }
    @Test void coveragePeriodsUseActualSandboxDateBounds() {
        var start=LocalDate.of(2024,1,1);
        assertDoesNotThrow(()->new QuoteOfferRequestIntent(scope(),id(5),start,start.plusDays(366),id(6),"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new QuoteOfferRequestIntent(scope(),id(5),start,start,id(6),"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new QuoteOfferRequestIntent(scope(),id(5),start,start.plusDays(367),id(6),"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new QuoteOfferRequestIntent(scope(),id(5),LocalDate.of(0,12,31),LocalDate.of(1,1,1),id(6),"a".repeat(64)));
    }
    @Test void unsupportedRequestRevisionOrDigestRefuses() {
        assertThrows(NullPointerException.class,()->new QuoteOfferRequestIntent(scope(),id(5),request().coverageStart(),request().coverageEnd(),null,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new QuoteOfferRequestIntent(scope(),id(5),request().coverageStart(),request().coverageEnd(),id(6),"A".repeat(64)));
    }
    @Test void oneActualRiskAndValueRoundTrip() {
        var v=snapshot();var bytes=QuoteCanonicalCodec.risk(v);
        assertEquals(v,QuoteCanonicalCodec.readRisk(bytes));assertEquals(new BigDecimal("100000.00"),v.declaredValue());
        assertEquals("732b964f08c6f07464943fbf4e4ba2cd0dc81e98c03f05a5784b41503565b64a",v.riskDigest());
        assertEquals(v.riskDigest(),QuoteCanonicalCodec.digest(bytes));assertEquals(17,QuoteCanonicalCodec.decodeFields(bytes).size());
    }
    @Test void missingOrAmbiguousRowsNeverProduceSnapshot() {
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(),List.of(value())));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk(),risk()),List.of(value())));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of()));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(value(),value())));
    }
    @Test void childParentAndPartyMustMatchExactScope() {
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(new QuoteRiskSnapshot.Risk(id(7),id(20),InsuredObjectType.PROPERTY,id(3))),List.of(value())));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(new QuoteRiskSnapshot.Risk(id(7),id(2),InsuredObjectType.PROPERTY,id(20))),List.of(value())));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(new QuoteRiskSnapshot.Value(id(8),id(20),1,new BigDecimal("1.00"),"USD"))));
        assertThrows(NullPointerException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(new QuoteRiskSnapshot.Risk(id(7),id(2),InsuredObjectType.PROPERTY,null)),List.of(value())));
    }
    @Test void onlyExplicitQuotableStatusesSupported() {
        for(var status:SubmissionStatus.values()) {
            var row=new QuoteRiskSnapshot.Submission(scope(),status,LineOfBusiness.PROPERTY,"USD");
            if(status==SubmissionStatus.RECEIVED||status==SubmissionStatus.QUOTED)assertDoesNotThrow(()->QuoteRiskSnapshot.fromRows(row,List.of(risk()),List.of(value())));
            else assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(row,List.of(risk()),List.of(value())));
        }
    }
    @Test void propertyAndCurrencyShapeNeverInferred() {
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(new QuoteRiskSnapshot.Submission(scope(),SubmissionStatus.RECEIVED,LineOfBusiness.MOTOR,"USD"),List.of(risk()),List.of(value())));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(new QuoteRiskSnapshot.Risk(id(7),id(2),InsuredObjectType.OTHER,id(3))),List.of(value())));
        assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(new QuoteRiskSnapshot.Value(id(8),id(2),1,new BigDecimal("1.00"),"EUR"))));
    }
    @Test void riskValueUsesExactUsdAndOriginalNumericColumnBound() {
        for(String invalid:List.of("0","-1","1.001","1000000000000000.00")) {
            assertThrows(IllegalArgumentException.class,()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(new QuoteRiskSnapshot.Value(id(8),id(2),1,new BigDecimal(invalid),"USD"))));
        }
        assertDoesNotThrow(()->QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(new QuoteRiskSnapshot.Value(id(8),id(2),Integer.MIN_VALUE,new BigDecimal("999999999999999.99"),"USD"))));
    }
    @Test void riskContentChangesDigestWithoutChangingHistoricalSnapshot() {
        var old=snapshot();var bytes=QuoteCanonicalCodec.risk(old);
        var changed=QuoteRiskSnapshot.fromRows(parent(),List.of(risk()),List.of(new QuoteRiskSnapshot.Value(id(8),id(2),1,new BigDecimal("90000.00"),"USD")));
        assertNotEquals(old.riskDigest(),changed.riskDigest());assertEquals(old,QuoteCanonicalCodec.readRisk(bytes));
    }
    @Test void riskDecoderRejectsNoncanonicalMoneyAndExtraFields() {
        var f=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.risk(snapshot())));f.set(15,"100000.0");
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readRisk(QuoteCanonicalCodec.encodeFields(f)));
        f.set(15,"100000.00");f.add("extra");assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readRisk(QuoteCanonicalCodec.encodeFields(f)));
    }
    @Test void offerDecoderRejectsTimeInsteadOfDateAndWrongVersion() {
        var f=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.offerRequest(request())));f.set(6,"2026-10-26T00:00:00Z");
        assertThrows(RuntimeException.class,()->QuoteCanonicalCodec.readOfferRequest(QuoteCanonicalCodec.encodeFields(f)));
        f.set(6,"2026-10-26");f.set(0,"FIREFLY_INSURANCE_QUOTE_OFFER_REQUEST_2");assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readOfferRequest(QuoteCanonicalCodec.encodeFields(f)));
    }
    @Test void domainsAndTrailingBytesRemainStrict() {
        var b=QuoteCanonicalCodec.risk(snapshot());assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readRisk(Arrays.copyOf(b,b.length+1)));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readOfferRequest(b));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readRisk(QuoteCanonicalCodec.offerRequest(request())));
    }
}
