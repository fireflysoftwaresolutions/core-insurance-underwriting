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

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteAuthorityGate;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;
import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Supplied principal/PDP API facts only; no JWT, signature, HTTP or live-policy acceptance. */
class QuoteAuthorityGateTest {
    private final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    private final UUID tenant=new UUID(0,1),party=new UUID(0,2),submission=new UUID(0,3),product=new UUID(0,4),offer=new UUID(0,5);
    private final String issuer="https://issuer.example",audience="core-insurance-underwriting-quote";
    private final AtomicLong nanos=new AtomicLong();
    private SecurityPrincipal principal(String subject,Map<String,Object> changed) {
        var claims=new HashMap<String,Object>(Map.of("tenant_id",tenant.toString(),"party_id",party.toString(),"principal_kind","HUMAN",
                "exp",now.plusSeconds(60),"nbf",now.minusSeconds(1),"aud",List.of(audience),"azp","domain-quote-client"));claims.putAll(changed);
        return new SecurityPrincipal(subject,issuer,tenant.toString(),Set.of(),Set.of("insurance:quotes:read","insurance:quotes:recover"),claims,now,null,Set.of(),Map.of());
    }
    private QuoteAuthorityGate.Request request() {return new QuoteAuthorityGate.Request(QuoteAdmission.Mode.READ,QuoteAuthorityGate.Phase.READ,
            tenant,submission,party,product,offer,null,null,Map.of());}
    private QuoteAuthorityGate gate(SecurityContextPort context,PolicyDecisionPort pdp) {return new QuoteAuthorityGate(context,pdp,
            new QuoteAuthorityGate.Profile(issuer,audience,"tenant_id","azp","domain-quote-client"),Clock.fixed(now,ZoneOffset.UTC),nanos::get);}
    @Test void actualSharedApiProducesOnlyBoundedLocalEvidence() {
        var calls=new AtomicInteger();var g=gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->{
            calls.incrementAndGet();assertEquals("insurance.quote.read",a);assertEquals(party.toString(),c.get("partyId"));
            assertEquals(product.toString(),c.get("productId"));assertFalse(c.containsKey("claims"));assertFalse(c.containsKey("token"));return Mono.just(Decision.permit());});
        var permit=g.authorize(request()).block();assertNotNull(permit);assertEquals(now.plusSeconds(5),permit.until());
        assertEquals(1,calls.get());assertTrue(permit.evidenceDigest().matches("[0-9a-f]{64}"));assertEquals("QuoteAuthorityPermit",permit.toString());
    }
    @Test void missingPrincipalOrEmptyDecisionIsUnavailable() {
        assertThrows(IllegalStateException.class,()->gate(Mono::empty,(p,a,r,c)->Mono.just(Decision.permit())).authorize(request()).block());
        assertThrows(IllegalStateException.class,()->gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->Mono.empty()).authorize(request()).block());
    }
    @Test void unsupportedScopeKindTenantPartyAndAudienceRefuseBeforePdp() {
        var calls=new AtomicInteger();
        for(var wrong:List.of(Map.<String,Object>of("principal_kind","MACHINE"),Map.<String,Object>of("tenant_id",new UUID(0,99).toString()),
                Map.<String,Object>of("party_id",new UUID(0,99).toString()),Map.<String,Object>of("aud",List.of("other-owner")),Map.<String,Object>of("azp","other-client"))) {
            var g=gate(()->Mono.just(principal("human",wrong)),(p,a,r,c)->{calls.incrementAndGet();return Mono.just(Decision.permit());});
            assertThrows(IllegalStateException.class,()->g.authorize(request()).block());
        }assertEquals(0,calls.get());
    }
    @Test void expiryNbfAndClaimCoercionRefuse() {
        for(var wrong:List.of(Map.<String,Object>of("exp",now),Map.<String,Object>of("nbf",now.plusSeconds(1)),Map.<String,Object>of("exp",now.plusSeconds(60).getEpochSecond()),Map.<String,Object>of("party_id",party)))
            assertThrows(IllegalStateException.class,()->gate(()->Mono.just(principal("human",wrong)),(p,a,r,c)->Mono.just(Decision.permit())).authorize(request()).block());
    }
    @Test void currentDecisionAndUnsupportedObligationsNeverPermit() {
        for(var decision:List.of(Decision.deny("local denial"),Decision.indeterminate("local unavailable"),Decision.permit(List.of(Obligation.of("mask",Map.of())))))
            assertThrows(IllegalStateException.class,()->gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->Mono.just(decision)).authorize(request()).block());
    }
    @Test void changingActorDuringPdpRefuses() {
        var current=new AtomicReference<>(principal("human",Map.of()));var g=gate(()->Mono.just(current.get()),(p,a,r,c)->{current.set(principal("other-human",Map.of()));return Mono.just(Decision.permit());});
        assertThrows(IllegalStateException.class,()->g.authorize(request()).block());
    }
    @Test void finalCurrentDenialWithholdsProjection() {
        var allow=new AtomicBoolean(true);var built=new AtomicInteger();var g=gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->Mono.just(allow.get()?Decision.permit():Decision.deny("current withdrawal")));
        var original=g.authorize(request()).block();allow.set(false);var w=QuoteNativeWindow.from(now,now.toEpochMilli(),now.toEpochMilli()+1000,0);
        assertThrows(IllegalStateException.class,()->g.project(original,w,()->{built.incrementAndGet();return "protected";}).block());assertEquals(0,built.get());
    }
    @Test void freshPermitDoesNotRenewNativeWindowDuringProjection() {
        var g=gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->Mono.just(Decision.permit()));var original=g.authorize(request()).block();
        var w=QuoteNativeWindow.from(now,now.toEpochMilli(),now.toEpochMilli()+1000,0);
        assertThrows(IllegalStateException.class,()->g.project(original,w,()->{nanos.set(999_000_000);return "protected";}).block());
    }
    @Test void tokenRefreshCannotReplaceOriginalCredentialWithinOperation() {
        var current=new AtomicReference<>(principal("human",Map.of()));var g=gate(()->Mono.just(current.get()),(p,a,r,c)->Mono.just(Decision.permit()));var original=g.authorize(request()).block();
        current.set(principal("human",Map.of("exp",now.plusSeconds(120))));var w=QuoteNativeWindow.from(now,now.toEpochMilli(),now.toEpochMilli()+1000,0);
        assertThrows(IllegalStateException.class,()->g.project(original,w,()->"protected").block());
        assertNotNull(g.authorize(request()).block());
    }
    @Test void fullValidatedProjectionChangesDuringPdpRefuse() {
        var original=principal("human",Map.of());var current=new AtomicReference<>(original);
        var g=gate(()->Mono.just(current.get()),(p,a,r,c)->{
            current.set(new SecurityPrincipal(p.subject(),p.issuer(),p.tenantId(),p.authorities(),p.scopes(),p.claims(),p.authTime(),p.acr(),p.amr(),Map.of("relationshipRevision","changed")));
            return Mono.just(Decision.permit());
        });assertThrows(IllegalStateException.class,()->g.authorize(request()).block());
    }
    @Test void acceptPrepareOnlyIssuesNarrowMetadataAdmission() {
        var p=principal("human",Map.of());var accept=new SecurityPrincipal(p.subject(),p.issuer(),p.tenantId(),p.authorities(),Set.of("insurance:quotes:accept"),p.claims(),p.authTime(),p.acr(),p.amr(),p.attributes());
        var g=gate(()->Mono.just(accept),(who,a,r,c)->{assertEquals("insurance.quote.accept",a);assertEquals("PREPARE",c.get("phase"));return Mono.just(Decision.permit());});
        var req=new QuoteAuthorityGate.Request(QuoteAdmission.Mode.ACCEPT,QuoteAuthorityGate.Phase.PREPARE,tenant,submission,party,product,offer,new UUID(0,6),"a".repeat(64),Map.of());
        var permit=g.authorize(req).block();assertEquals(QuoteAdmission.Mode.READ,permit.acceptanceMetadataAdmission().mode());
        assertEquals(offer,permit.acceptanceMetadataAdmission().offerId());assertThrows(IllegalStateException.class,()->permit.admission(permit.until()));
    }
    @Test void ordinaryReadCannotSelectAcceptPrerequisiteShortcut() {
        var g=gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->Mono.just(Decision.permit()));var permit=g.authorize(request()).block();
        assertThrows(IllegalStateException.class,permit::acceptanceMetadataAdmission);
    }
    @Test void recoveryRunsBothCurrentReadAndRecoverPdpActions() {
        var actions=new ArrayList<String>();var g=gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->{actions.add(a);return Mono.just(Decision.permit());});
        var req=new QuoteAuthorityGate.Request(QuoteAdmission.Mode.RECOVER,QuoteAuthorityGate.Phase.READ,tenant,submission,party,product,null,new UUID(0,6),"a".repeat(64),Map.of());
        assertNotNull(g.authorize(req).block());assertEquals(List.of("insurance.quote.read","insurance.quote.recover"),actions);
    }
    @Test void acceptanceScopeCannotReplaceOrdinaryReadPermission() {
        var p=principal("human",Map.of());var accept=new SecurityPrincipal(p.subject(),p.issuer(),p.tenantId(),p.authorities(),Set.of("insurance:quotes:accept"),p.claims(),p.authTime(),p.acr(),p.amr(),p.attributes());
        assertThrows(IllegalStateException.class,()->gate(()->Mono.just(accept),(who,a,r,c)->Mono.just(Decision.permit())).authorize(request()).block());
    }
    @Test void intentDetailsCannotOverridePolicyScopeOrActor() {
        assertThrows(IllegalStateException.class,()->new QuoteAuthorityGate.Request(QuoteAdmission.Mode.READ,QuoteAuthorityGate.Phase.READ,tenant,submission,party,product,offer,null,null,Map.of("partyId",new UUID(0,99).toString())));
    }
    @Test void oldLocalPdpObservationCannotStartAnotherNativeContext() {
        var g=gate(()->Mono.just(principal("human",Map.of())),(p,a,r,c)->Mono.just(Decision.permit()));var permit=g.authorize(request()).block();
        nanos.set(5_000_000_000L);assertThrows(IllegalStateException.class,()->permit.admission(permit.until()));
    }
    @Test void prepareCannotNameAGovernedOffer() {
        assertThrows(IllegalStateException.class,()->new QuoteAuthorityGate.Request(QuoteAdmission.Mode.OFFER_PREPARE,QuoteAuthorityGate.Phase.PREPARE,
                tenant,submission,party,null,offer,new UUID(0,6),"a".repeat(64),Map.of()));
    }
}
