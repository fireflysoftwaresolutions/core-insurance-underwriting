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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteMappedStoreImpl;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuotePersistenceImpl;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;
import io.r2dbc.spi.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class QuoteMappedStoreUnavailableTest {
    private final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    private QuoteAdmission a(QuoteAdmission.Mode mode) {
        return new QuoteAdmission(UUID.randomUUID(),mode,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                mode==QuoteAdmission.Mode.HISTORY?null:UUID.randomUUID(), mode==QuoteAdmission.Mode.HISTORY?null:UUID.randomUUID(),
                "https://issuer.example","human",now.plusSeconds(30),now.plusSeconds(20),now.plusSeconds(10),"a".repeat(64))
                .withBudget(QuoteAuthorityBudget.observe(now,now.plusSeconds(5),System.nanoTime(),Clock.fixed(now,ZoneOffset.UTC),System::nanoTime));
    }
    private ConnectionFactory absent(AtomicInteger calls) {return new ConnectionFactory() {
        public Mono<? extends Connection> create() {calls.incrementAndGet();return Mono.error(new IllegalStateException("Local database unavailable fixture"));}
        public ConnectionFactoryMetadata getMetadata() {return ()->"PostgreSQL";}
    };}
    @Test void missingWriteDatabaseDoesNotFabricateNativeSourceOrClock() {
        var calls=new AtomicInteger();try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(absent(calls),absent(new AtomicInteger()),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            var store=new QuoteMappedStoreImpl(p,Clock.fixed(now,ZoneOffset.UTC),System::nanoTime,UUID::randomUUID);
            assertThrows(RuntimeException.class,()->store.prepare(a(QuoteAdmission.Mode.OFFER_PREPARE)).block(Duration.ofSeconds(2)));
            assertEquals(1,calls.get());
        }
    }
    @Test void missingReadDatabaseNeverReturnsNotObservedOrRetainedData() {
        var calls=new AtomicInteger();try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(absent(new AtomicInteger()),absent(calls),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            var store=new QuoteMappedStoreImpl(p,Clock.fixed(now,ZoneOffset.UTC),System::nanoTime,UUID::randomUUID);
            assertThrows(RuntimeException.class,()->store.current(a(QuoteAdmission.Mode.READ)).block(Duration.ofSeconds(2)));
            assertEquals(1,calls.get());
        }
    }
    @Test void invalidModesRefuseBeforeConnectionAcquisition() {
        var calls=new AtomicInteger();try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(absent(calls),absent(calls),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            var store=new QuoteMappedStoreImpl(p,Clock.fixed(now,ZoneOffset.UTC),System::nanoTime,UUID::randomUUID);
            assertThrows(IllegalArgumentException.class,()->store.prepare(a(QuoteAdmission.Mode.READ)).block(Duration.ofSeconds(2)));
            assertThrows(IllegalArgumentException.class,()->store.current(a(QuoteAdmission.Mode.OFFER)).block(Duration.ofSeconds(2)));
            assertThrows(IllegalArgumentException.class,()->store.history(a(QuoteAdmission.Mode.READ)).block(Duration.ofSeconds(2)));
            assertEquals(0,calls.get());
        }
    }
    @Test void missingRecoveryDatabaseDoesNotImplyNoEffect() {
        var calls=new AtomicInteger();try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(absent(new AtomicInteger()),absent(calls),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            var store=new QuoteMappedStoreImpl(p,Clock.fixed(now,ZoneOffset.UTC),System::nanoTime,UUID::randomUUID);
            assertThrows(RuntimeException.class,()->store.recover(a(QuoteAdmission.Mode.RECOVER),"b".repeat(64)).block(Duration.ofSeconds(2)));
            assertEquals(1,calls.get());
        }
    }
}
