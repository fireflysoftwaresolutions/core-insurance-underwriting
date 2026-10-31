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

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.impl.*;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.*;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;
import io.r2dbc.spi.*;
import org.fireflyframework.security.api.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Real service/store composition with unavailable connections, never a database or owner-authentication claim. */
class QuoteGovernedServiceUnavailableTest {
    private final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    private final QuoteOfferScope scope=new QuoteOfferScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),new UUID(0,4));
    private final UUID offer=new UUID(0,5),action=new UUID(0,6),revision=new UUID(0,7);
    private final AtomicInteger commandCalls=new AtomicInteger(),readCalls=new AtomicInteger(),configCalls=new AtomicInteger();
    private ConnectionFactory absent(AtomicInteger calls) {return new ConnectionFactory() {
        public Mono<? extends Connection> create(){calls.incrementAndGet();return Mono.error(new IllegalStateException("Unavailable local database fixture"));}
        public ConnectionFactoryMetadata getMetadata(){return ()->"PostgreSQL";}
    };}
    private QuoteGovernedService service(GenericApplicationContext context,Set<String> scopes) {
        var p=new SecurityPrincipal("human","https://issuer.example",scope.tenantId().toString(),Set.of(),scopes,
                Map.of("tenant_id",scope.tenantId().toString(),"party_id",scope.partyId().toString(),"principal_kind","HUMAN","exp",now.plusSeconds(60),"nbf",now.minusSeconds(1),"aud",List.of("core-quote"),"azp","domain-client"),now,null,Set.of(),Map.of());
        var gate=new QuoteAuthorityGate(()->Mono.just(p),(principal,a,r,c)->Mono.just(Decision.permit()),
                new QuoteAuthorityGate.Profile("https://issuer.example","core-quote","tenant_id","azp","domain-client"),Clock.fixed(now,ZoneOffset.UTC),System::nanoTime);
        var persistence=new QuotePersistence(absent(commandCalls),absent(readCalls),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
        var store=new QuoteMappedStore(persistence,Clock.fixed(now,ZoneOffset.UTC),System::nanoTime,UUID::randomUUID);
        QuoteConfigurationPort configurations=(s,id,digest)->{configCalls.incrementAndGet();return Mono.error(new AssertionError("No configuration request may follow unavailable storage"));};
        return new QuoteGovernedServiceImpl(gate,store,configurations,new SandboxPropertyQuoteRatingImpl(),Clock.fixed(now,ZoneOffset.UTC),System::nanoTime);
    }
    @Test void offerCannotRateOrInventAReceiptWhenNativePreparationIsUnavailable() {
        try(var context=new GenericApplicationContext()){context.refresh();var service=service(context,Set.of("insurance:quotes:offer"));
            var request=new QuoteOfferRequestIntent(scope,action,LocalDate.of(2026,11,1),LocalDate.of(2027,11,1),revision,"a".repeat(64));
            assertThrows(RuntimeException.class,()->service.offer(request).block(Duration.ofSeconds(2)));
            assertEquals(1,commandCalls.get());assertEquals(0,readCalls.get());assertEquals(0,configCalls.get());}
    }
    @Test void acceptPrepareUsesOnlyReadPoolAndDoesNotRequireAnOrdinaryReadGrant() {
        try(var context=new GenericApplicationContext()){context.refresh();var service=service(context,Set.of("insurance:quotes:accept"));
            var intent=new QuoteAcceptanceIntent(scope,offer,1,0,"a".repeat(64),action);
            assertThrows(RuntimeException.class,()->service.accept(intent).block(Duration.ofSeconds(2)));
            assertEquals(0,commandCalls.get());assertEquals(1,readCalls.get());assertEquals(0,configCalls.get());}
    }
    @Test void acceptScopeCannotBeSelectedForOrdinaryCurrentRead() {
        try(var context=new GenericApplicationContext()){context.refresh();var service=service(context,Set.of("insurance:quotes:accept"));
            assertThrows(IllegalStateException.class,()->service.current(scope,offer).block(Duration.ofSeconds(2)));
            assertEquals(0,commandCalls.get());assertEquals(0,readCalls.get());assertEquals(0,configCalls.get());}
    }
    @Test void missingHistoryDatabaseNeverEmitsEmptyHistoryAsSuccess() {
        try(var context=new GenericApplicationContext()){context.refresh();var service=service(context,Set.of("insurance:quotes:history:read"));
            assertThrows(RuntimeException.class,()->service.history(scope).block(Duration.ofSeconds(2)));
            assertEquals(0,commandCalls.get());assertEquals(1,readCalls.get());assertEquals(0,configCalls.get());}
    }
    @Test void recoveryRequiresItsCurrentReadPermissionBeforeStorage() {
        try(var context=new GenericApplicationContext()){context.refresh();var service=service(context,Set.of("insurance:quotes:recover"));
            assertThrows(IllegalStateException.class,()->service.recover(scope,action,"a".repeat(64)).block(Duration.ofSeconds(2)));
            assertEquals(0,readCalls.get());assertEquals(0,configCalls.get());}
    }
    @Test void writeOutcomeCannotContainAProtectedResultUnlessAuthorizedCommitted() {
        assertThrows(IllegalArgumentException.class,()->new QuoteGovernedService.WriteOutcome(QuoteGovernedService.Status.COMMITTED,action,Optional.empty()));
        var unknown=new QuoteGovernedService.WriteOutcome(QuoteGovernedService.Status.OUTCOME_UNKNOWN,action,Optional.empty());assertTrue(unknown.result().isEmpty());
    }
}
