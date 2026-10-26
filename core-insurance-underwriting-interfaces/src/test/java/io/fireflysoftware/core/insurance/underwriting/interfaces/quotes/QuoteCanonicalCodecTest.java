package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuoteCanonicalCodecTest {
    private static UUID id(int value) { return new UUID(0, value); }
    private static QuoteOfferScope scope() { return new QuoteOfferScope(id(1), id(2), id(3), id(4)); }
    private static QuoteOfferVersion offer() {
        var terms = new QuoteOfferTerms("USD",new BigDecimal("10.00"),new BigDecimal("1.00"),new BigDecimal("0.00"),
                Map.of("building", "Covered"),id(5),"a".repeat(64),"b".repeat(64),"sandbox-1","rules-1");
        return new QuoteOfferVersion(scope(),id(6),1,0,QuoteOfferVersion.State.OFFERED,terms,
                Instant.parse("2026-10-26T00:00:00Z"),Instant.parse("2026-10-26T00:20:00Z"));
    }
    private static QuoteAcceptanceIntent intent() { return new QuoteAcceptanceIntent(scope(),id(6),1,0,offer().termsDigest(),id(7)); }
    private static QuoteActionEnvelope action() { return QuoteActionEnvelope.acceptance("https://issuer.example", "owner", intent()); }
    @Test void originalTermsEncodingAndDigestAreIdentical() throws Exception {
        var v=offer();var fields=List.of("FIREFLY_INSURANCE_QUOTE_TERMS_1",id(1).toString(),id(2).toString(),id(3).toString(),id(4).toString(),id(6).toString(),"1",v.issuedAt().toString(),v.expiresAt().toString(),"USD","10.00","1.00","0.00",id(5).toString(),"a".repeat(64),"b".repeat(64),"sandbox-1","rules-1","1","building","Covered");
        var bytes=new java.io.ByteArrayOutputStream();var writer=new java.io.DataOutputStream(bytes);writer.writeInt(fields.size());
        for(var s:fields){var data=s.getBytes(StandardCharsets.UTF_8);writer.writeInt(data.length);writer.write(data);}
        assertArrayEquals(bytes.toByteArray(),QuoteCanonicalCodec.terms(v));
        assertEquals(v.termsDigest(),QuoteCanonicalCodec.digest(QuoteCanonicalCodec.terms(v)));
    }
    @Test void termsRoundTripDoesNotInventMutableState() {
        var bytes=QuoteCanonicalCodec.terms(offer());var decoded=QuoteCanonicalCodec.readTerms(bytes);
        assertEquals(offer().scope(),decoded.scope());assertEquals(offer().terms(),decoded.terms());
        assertArrayEquals(bytes,QuoteCanonicalCodec.terms(decoded));
        var accepted=new QuoteOfferVersion(scope(),id(6),1,1,QuoteOfferVersion.State.ACCEPTED,offer().terms(),offer().issuedAt(),offer().expiresAt());
        assertArrayEquals(bytes,QuoteCanonicalCodec.terms(accepted));
    }
    @Test void intentAndReceiptRoundTrip() {
        var bytes=QuoteCanonicalCodec.intent(intent());assertEquals(intent(),QuoteCanonicalCodec.readIntent(bytes));
        assertEquals(intent().intentDigest(),QuoteCanonicalCodec.digest(bytes));
        var receipt=new QuoteAcceptanceReceipt(intent(),offer().issuedAt(),1);
        assertEquals(receipt,QuoteCanonicalCodec.readReceipt(QuoteCanonicalCodec.receipt(receipt)));
    }
    @Test void trailingAndTruncatedFieldsRefuse() {
        var bytes=QuoteCanonicalCodec.intent(intent());
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readIntent(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readIntent(Arrays.copyOf(bytes,bytes.length-1)));
    }
    @Test void countAndSizeBoundsRefuseBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readIntent(ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array()));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readIntent(new byte[65537]));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.encodeFields(Collections.nCopies(129,"a")));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.encodeFields(List.of("a".repeat(32769))));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.encodeFields(List.of("a".repeat(32768),"b".repeat(32768))));
    }
    @Test void malformedUnicodeAndWrongDomainRefuse() {
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.decodeFields(new byte[]{0,0,0,1,0,0,0,1,(byte)0xff}));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.encodeFields(List.of("\uD800")));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readIntent(QuoteCanonicalCodec.terms(offer())));
    }
    @Test void nonCanonicalNumbersAndTimeRefuse() {
        var fields=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.terms(offer())));
        fields.set(10,"10.0");assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(fields)));
        fields.set(10,"10.001");assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(fields)));
        fields.set(10,"10.00");fields.set(7,"2026-10-26T00:00:00.000000001Z");
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(fields)));
    }
    @Test void duplicateCoverageAndWrongCountRefuse() {
        var fields=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.terms(offer())));
        fields.set(18,"2");fields.add("building");fields.add("Again");
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(fields)));
        fields.set(18,"1");assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(fields)));
    }
    @Test void actionBindsOriginalOwnerAndTypedIntent() {
        var a=action();assertEquals(scope(),a.scope());assertEquals(intent().actionId(),a.actionId());assertEquals(intent().intentDigest(),a.intentDigest());
        assertEquals(a,QuoteCanonicalCodec.readAction(QuoteCanonicalCodec.action(a)));
        assertTrue(a.matches(intent()));assertFalse(a.matches(new QuoteAcceptanceIntent(scope(),id(6),1,0,intent().termsDigest(),id(8))));
        assertFalse(a.matches(new QuoteAcceptanceIntent(new QuoteOfferScope(id(9),id(2),id(3),id(4)),id(6),1,0,intent().termsDigest(),id(7))));
    }
    @Test void resultBindsActionIdsGenerationAndDecisionTime() {
        var result=new QuoteResultEnvelope(action(),id(9),id(10),id(11),offer().issuedAt(),1);
        assertEquals(result,QuoteCanonicalCodec.readResult(QuoteCanonicalCodec.result(result)));
        assertThrows(IllegalArgumentException.class,()->new QuoteResultEnvelope(action(),id(9),id(10),null,offer().issuedAt(),1));
        assertThrows(IllegalArgumentException.class,()->new QuoteResultEnvelope(action(),id(9),id(10),id(11),offer().issuedAt(),0));
        assertThrows(IllegalArgumentException.class,()->new QuoteResultEnvelope(action(),id(9),id(10),id(11),offer().issuedAt().plusNanos(1),1));
    }
    @Test void offerResultHasNoAcceptanceOrGenerationAdvance() {
        var a=new QuoteActionEnvelope(scope(),"https://issuer.example","owner",QuoteActionEnvelope.Operation.OFFER,id(6),1,0,id(7),"c".repeat(64));
        var result=new QuoteResultEnvelope(a,id(9),id(10),null,offer().issuedAt(),0);
        assertEquals(result,QuoteCanonicalCodec.readResult(QuoteCanonicalCodec.result(result)));
        assertThrows(IllegalArgumentException.class,()->new QuoteResultEnvelope(a,id(9),id(10),id(11),offer().issuedAt(),0));
    }
    @Test void exactOwnerBoundsAndNoAuthorityFields() {
        assertThrows(IllegalArgumentException.class,()->new QuoteActionEnvelope(scope()," ","owner",QuoteActionEnvelope.Operation.OFFER,id(6),1,0,id(7),"c".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->QuoteActionEnvelope.acceptance("x".repeat(513),"owner",intent()));
        assertThrows(IllegalArgumentException.class,()->QuoteActionEnvelope.acceptance("issuer","x".repeat(256),intent()));
        assertEquals(9,QuoteActionEnvelope.class.getRecordComponents().length);
        assertEquals(6,QuoteResultEnvelope.class.getRecordComponents().length);
    }
    @Test void decoderPreservesExactOwnerIdentityBytes() {
        var fields=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.action(action())));fields.set(6,"e\u0301");
        var decoded=QuoteCanonicalCodec.readAction(QuoteCanonicalCodec.encodeFields(fields));
        assertEquals("e\u0301",decoded.ownerSubject());
        assertArrayEquals(QuoteCanonicalCodec.encodeFields(fields),QuoteCanonicalCodec.action(decoded));
    }
    @Test void ownerIdentityNeverNormalizesCodepointsOrCase() {
        var original="e\u0301";
        var value=QuoteActionEnvelope.acceptance("https://Issuer.Example",original,intent());
        assertEquals(original,value.ownerSubject());assertEquals("https://Issuer.Example",value.ownerIssuer());
        assertNotEquals(QuoteActionEnvelope.acceptance("https://Issuer.Example","é",intent()),value);
    }
    @Test void malformedFieldLengthsAndDomainVersionsRefuse() {
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.decodeFields(new byte[]{0,0,0,1,-1,-1,-1,-1}));
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.decodeFields(new byte[]{0,0,0,0}));
        var f=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.intent(intent())));
        f.set(0,"FIREFLY_INSURANCE_QUOTE_ACCEPT_INTENT_2");
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readIntent(QuoteCanonicalCodec.encodeFields(f)));
    }
    @Test void canonicalMoneyMaximumAndTotalRemainExact() {
        var f=new ArrayList<>(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.terms(offer())));
        f.set(10,"99999999999999999.99");f.set(11,"0.00");
        assertEquals(new BigDecimal("99999999999999999.99"),QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(f)).terms().totalPremium());
        f.set(11,"0.01");
        assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(f)));
        f.set(10,"1E+20");assertThrows(IllegalArgumentException.class,()->QuoteCanonicalCodec.readTerms(QuoteCanonicalCodec.encodeFields(f)));
    }
    @Test void everyResultLinkChangesCanonicalDigest() {
        var v=new QuoteResultEnvelope(action(),id(9),id(10),id(11),offer().issuedAt(),1);
        var expected=QuoteCanonicalCodec.digest(QuoteCanonicalCodec.result(v));
        var changed=new QuoteResultEnvelope(action(),id(12),id(10),id(11),offer().issuedAt(),1);
        assertNotEquals(expected,QuoteCanonicalCodec.digest(QuoteCanonicalCodec.result(changed)));
        changed=new QuoteResultEnvelope(action(),id(9),id(12),id(11),offer().issuedAt(),1);
        assertNotEquals(expected,QuoteCanonicalCodec.digest(QuoteCanonicalCodec.result(changed)));
        changed=new QuoteResultEnvelope(action(),id(9),id(10),id(12),offer().issuedAt(),1);
        assertNotEquals(expected,QuoteCanonicalCodec.digest(QuoteCanonicalCodec.result(changed)));
        changed=new QuoteResultEnvelope(action(),id(9),id(10),id(11),offer().issuedAt().plusNanos(1000),1);
        assertNotEquals(expected,QuoteCanonicalCodec.digest(QuoteCanonicalCodec.result(changed)));
    }
    @Test void legacyDigestHelperDelegatesToCanonicalBytes() {
        var fields=QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.terms(offer()));
        assertEquals(offer().termsDigest(),QuoteDigests.digest(fields));
        assertEquals(intent().intentDigest(),QuoteDigests.digest(QuoteCanonicalCodec.decodeFields(QuoteCanonicalCodec.intent(intent()))));
    }
    @Test void returnedFieldsAndBytesAreIndependent() {
        var bytes=QuoteCanonicalCodec.intent(intent());var fields=QuoteCanonicalCodec.decodeFields(bytes);
        assertThrows(UnsupportedOperationException.class,()->fields.add("changed"));bytes[0]=99;
        assertEquals(intent(),QuoteCanonicalCodec.readIntent(QuoteCanonicalCodec.intent(intent())));
    }
}
