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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteRetainedGraph;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteRowAssembly;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteRowAssemblyTest {
    private final UUID tenant=id(1),submission=id(2),party=id(3),product=id(4),offer=id(5),action=id(6),revision=id(7),nonce=id(8),version=id(9),result=id(10);
    private final QuoteOfferScope scope=new QuoteOfferScope(tenant,submission,party,product);
    private final Instant now=Instant.parse("2026-10-26T12:00:00.000999Z");
    private final String digest="a".repeat(64),evidence="b".repeat(64);
    private static UUID id(int n) { return new UUID(0,n); }
    private QuoteAdmission admission(QuoteAdmission.Mode mode) {
        return new QuoteAdmission(nonce,mode,tenant,submission,party,product,offer,action,"https://issuer.example","human",now.plusSeconds(50),now.plusSeconds(30),now.plusSeconds(20),evidence);
    }
    private QuoteRows.NativeStamp stamp() {return new QuoteRows.NativeStamp(nonce,now.atOffset(ZoneOffset.UTC),now.toEpochMilli(),now.toEpochMilli()+5000,product,offer);}
    private QuoteOfferRequestIntent request() {return new QuoteOfferRequestIntent(scope,action,LocalDate.of(2026,11,1),LocalDate.of(2027,11,1),revision,digest);}
    private QuoteRiskSnapshot risk() {return new QuoteRiskSnapshot(scope,SubmissionStatus.RECEIVED,LineOfBusiness.PROPERTY,"USD",id(11),submission,InsuredObjectType.PROPERTY,party,id(12),submission,1,new BigDecimal("1000.00"),"USD");}
    private QuoteOfferTerms terms() {return new QuoteOfferTerms("USD",new BigDecimal("25.00"),new BigDecimal("1.25"),new BigDecimal("10.00"),Map.of("coverageStart","2026-11-01","coverageEnd","2027-11-01"),revision,digest,risk().riskDigest(),"firefly-sandbox-property-1","illustrative-property-1");}
    private QuoteRowAssembly.Offer assemble() {return QuoteRowAssembly.offer(admission(QuoteAdmission.Mode.OFFER),stamp(),request(),risk(),terms(),Duration.ofHours(1),version,result);}
    @Test void offerHasExactlyThreeLinkedRowsWithNativeTimestampAndOwner() {
        var rows=assemble();assertEquals(offer,rows.control().offerId());assertEquals(action,rows.control().createdActionId());
        assertEquals(0,rows.control().controlGeneration());assertEquals("OFFERED",rows.control().state());assertNull(rows.control().acceptanceId());
        assertEquals(version,rows.version().offerVersionId());assertEquals(result,rows.action().actionResultId());
        assertEquals(now,rows.version().issuedAt().toInstant());assertEquals(now,rows.control().createdAt().toInstant());
        assertEquals(now,rows.action().recordedAt().toInstant());assertEquals("https://issuer.example",rows.control().ownerIssuer());
        assertEquals(rows.control().ownerSubject(),rows.version().ownerSubject());assertEquals(rows.version().ownerSubject(),rows.action().ownerSubject());
    }
    @Test void canonicalBytesDelegateToExactExistingCodec() {
        var rows=assemble();assertEquals(request(),QuoteCanonicalCodec.readOfferRequest(rows.action().requestCanonical()));
        assertEquals(rows.result(),QuoteCanonicalCodec.readResult(rows.action().resultCanonical()));
        assertEquals(rows.result().action().intentDigest(),rows.action().requestDigest());
        assertEquals(risk(),QuoteCanonicalCodec.readRisk(rows.version().riskSnapshot()));
        assertEquals(terms(),QuoteCanonicalCodec.readTerms(rows.version().canonicalTerms()).terms());
        assertEquals(QuoteCanonicalCodec.digest(rows.version().canonicalTerms()),rows.version().termsDigest());
    }
    @Test void arraysAreDefensiveAndRowDiagnosticsOpaque() {
        var rows=assemble();byte[] bytes=rows.version().canonicalTerms();bytes[0]=99;
        assertEquals(terms(),QuoteCanonicalCodec.readTerms(rows.version().canonicalTerms()).terms());
        assertFalse(rows.version().toString().contains("human"));assertFalse(rows.action().toString().contains(evidence));
    }
    @Test void productRevisionMismatchRefuses() {
        var wrong=new QuoteOfferRequestIntent(scope,action,request().coverageStart(),request().coverageEnd(),id(90),digest);
        assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.offer(admission(QuoteAdmission.Mode.OFFER),stamp(),wrong,risk(),terms(),Duration.ofHours(1),version,result));
    }
    @Test void contextNonceMismatchRefuses() {
        var wrong=new QuoteRows.NativeStamp(id(91),stamp().dbRecordedAt(),stamp().dbNowMs(),stamp().effectiveUntilMs(),product,offer);
        assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.offer(admission(QuoteAdmission.Mode.OFFER),wrong,request(),risk(),terms(),Duration.ofHours(1),version,result));
    }
    @Test void currentSourceRiskDigestMismatchRefuses() {
        var t=terms();var wrong=new QuoteOfferTerms(t.currency(),t.basePremium(),t.tax(),t.fees(),t.coverageTerms(),revision,digest,"c".repeat(64),t.providerVersion(),t.ruleSetVersion());
        assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.offer(admission(QuoteAdmission.Mode.OFFER),stamp(),request(),risk(),wrong,Duration.ofHours(1),version,result));
    }
    @Test void noPrepareOrReadModeCanAssembleOfferWrites() {
        for(var mode:List.of(QuoteAdmission.Mode.OFFER_PREPARE,QuoteAdmission.Mode.READ,QuoteAdmission.Mode.HISTORY,QuoteAdmission.Mode.RECOVER))
            assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.offer(admission(mode),stamp(),request(),risk(),terms(),Duration.ofHours(1),version,result));
    }
    @Test void finiteSupportedOfferLifetimeRequired() {
        for(var duration:List.of(Duration.ZERO,Duration.ofHours(25),Duration.ofNanos(1)))
            assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.offer(admission(QuoteAdmission.Mode.OFFER),stamp(),request(),risk(),terms(),duration,version,result));
    }
    @Test void admissionRejectsMissingScopeAndUnboundedIdentity() {
        assertThrows(IllegalArgumentException.class,()->new QuoteAdmission(nonce,QuoteAdmission.Mode.OFFER,tenant,submission,party,null,offer,action,"issuer","human",now,now,now,evidence));
        assertThrows(IllegalArgumentException.class,()->new QuoteAdmission(nonce,QuoteAdmission.Mode.OFFER,tenant,submission,party,product,offer,action,"issuer"," ",now,now,now,evidence));
    }
    @Test void prepareProductBindingCannotReplaceKnownProductOrOtherFacts() {
        var a=new QuoteAdmission(nonce,QuoteAdmission.Mode.OFFER_PREPARE,tenant,submission,party,null,null,action,"issuer","human",now,now,now,evidence);
        var bound=a.bindProduct(product);assertEquals(scope,bound.scope());assertEquals(a.actorUntil(),bound.actorUntil());assertEquals(a.permitUntil(),bound.permitUntil());
        assertThrows(IllegalArgumentException.class,()->bound.bindProduct(id(99)));
    }
    @Test void continuedAdmissionCanOnlyNarrowTheNativeAbsoluteHorizon() {
        var a=admission(QuoteAdmission.Mode.OFFER);var until=now.plusSeconds(2);var bounded=a.narrowUntil(until);
        assertEquals(until,bounded.configUntil());assertEquals(a.actorUntil(),bounded.actorUntil());
        assertEquals(a.permitUntil(),bounded.permitUntil());assertEquals(a.evidenceDigest(),bounded.evidenceDigest());
        assertEquals(a.nonce(),bounded.nonce());assertEquals(a.scope(),bounded.scope());
        assertEquals(a.actionId(),bounded.actionId());assertEquals(a.offerId(),bounded.offerId());
    }
    @Test void aLaterObservationCannotExtendExistingConfigurationBound() {
        var a=admission(QuoteAdmission.Mode.OFFER);
        assertEquals(a.configUntil(),a.narrowUntil(now.plusSeconds(200)).configUntil());
    }
    @Test void retainedOfferVerifiesCanonicalGraphWithoutReratingHistoricalRisk() {
        var rows=assemble();var view=QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),rows.control(),rows.version(),List.of(rows.action()),null);
        assertEquals(terms(),view.offer().terms());assertEquals(rows.result(),view.offerResult());assertTrue(view.receipt().isEmpty());
    }
    @Test void missingOrExtraCreatorActionRefuses() {
        var rows=assemble();
        assertThrows(IllegalArgumentException.class,()->QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),rows.control(),rows.version(),List.of(),null));
        assertThrows(IllegalArgumentException.class,()->QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),rows.control(),rows.version(),List.of(rows.action(),rows.action()),null));
    }
    @Test void alteredRetainedColumnsRefuseEvenWhenCanonicalBytesRemainUnchanged() {
        var rows=assemble();
        for(var patch:List.of(Map.of("termsDigest","c".repeat(64)),Map.of("productDigest","c".repeat(64)),Map.of("basePremium",new BigDecimal("99.00")),Map.of("ownerSubject","another-human"))) {
            var changed=copy(rows.version(),patch);
            assertThrows(IllegalArgumentException.class,()->QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),rows.control(),changed,List.of(rows.action()),null));
        }
    }
    @Test void retainedResultIdOrOperationMismatchRefuses() {
        var rows=assemble();var wrong=copy(rows.action(),Map.of("actionResultId",id(92)));
        assertThrows(IllegalArgumentException.class,()->QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),rows.control(),rows.version(),List.of(wrong),null));
    }
    @Test void otherOfferRefusesWithoutActionLookupWidening() {
        var rows=assemble();var wrong=copy(rows.control(),Map.of("offerId",id(93)));
        assertThrows(IllegalArgumentException.class,()->QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),wrong,rows.version(),List.of(rows.action()),null));
    }
    @Test void acceptanceHasTwoImmutableRowsAndOneLinkedFourColumnTransition() {
        var rows=assemble();UUID acceptAction=id(70),acceptance=id(71),acceptResult=id(72);
        var a=copy(admission(QuoteAdmission.Mode.ACCEPT),Map.of("actionId",acceptAction));
        var intent=new QuoteAcceptanceIntent(scope,offer,1,0,rows.version().termsDigest(),acceptAction);
        var accepted=QuoteRowAssembly.acceptance(a,stamp(),rows.control(),rows.version(),intent,risk(),acceptance,acceptResult);
        var transitioned=copy(rows.control(),Map.of("state","ACCEPTED","controlGeneration",1L,"acceptanceId",acceptance,"updatedAt",stamp().dbRecordedAt()));
        var view=QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),transitioned,rows.version(),List.of(rows.action(),accepted.action()),accepted.acceptance());
        assertEquals(intent,view.receipt().orElseThrow().intent());assertEquals(accepted.result(),view.acceptanceResult().orElseThrow());
        assertEquals(rows.result(),view.offerResult());assertEquals(1,view.offer().controlGeneration());
        assertEquals(Optional.of(accepted.result()),view.resultFor(acceptAction,intent.intentDigest()));
        assertTrue(view.resultFor(id(999),intent.intentDigest()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->view.resultFor(acceptAction,"c".repeat(64)));
    }
    @Test void sourceChangeOrStaleGenerationRefusesNewAcceptance() {
        var rows=assemble();var intent=new QuoteAcceptanceIntent(scope,offer,1,0,rows.version().termsDigest(),action);
        var wrong=new QuoteRiskSnapshot(scope,SubmissionStatus.QUOTED,LineOfBusiness.PROPERTY,"USD",risk().riskId(),submission,InsuredObjectType.PROPERTY,party,risk().valueId(),submission,1,risk().declaredValue(),"USD");
        assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.acceptance(admission(QuoteAdmission.Mode.ACCEPT),stamp(),rows.control(),rows.version(),intent,wrong,id(71),id(72)));
        var stale=new QuoteAcceptanceIntent(scope,offer,1,1,rows.version().termsDigest(),action);
        assertThrows(IllegalArgumentException.class,()->QuoteRowAssembly.acceptance(admission(QuoteAdmission.Mode.ACCEPT),stamp(),rows.control(),rows.version(),stale,risk(),id(71),id(72)));
    }
    @Test void acceptedControlWithoutReceiptIsNotComplete() {
        var rows=assemble();var transitioned=copy(rows.control(),Map.of("state","ACCEPTED","controlGeneration",1L,"acceptanceId",id(71)));
        assertThrows(IllegalArgumentException.class,()->QuoteRetainedGraph.verify(admission(QuoteAdmission.Mode.READ),transitioned,rows.version(),List.of(rows.action()),null));
    }
    @SuppressWarnings("unchecked")
    private static <T> T copy(T value,Map<String,?> changes) {
        try {
            var components=value.getClass().getRecordComponents();var types=new Class<?>[components.length];var args=new Object[components.length];
            for(int i=0;i<components.length;i++){types[i]=components[i].getType();args[i]=changes.containsKey(components[i].getName())?changes.get(components[i].getName()):components[i].getAccessor().invoke(value);}
            return (T)value.getClass().getConstructor(types).newInstance(args);
        } catch(ReflectiveOperationException error) {throw new AssertionError("Invalid local stored-row fixture",error);}
    }
}
