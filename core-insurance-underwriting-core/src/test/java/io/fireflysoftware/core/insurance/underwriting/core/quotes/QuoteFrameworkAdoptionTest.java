/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.fireflysoftware.core.insurance.underwriting.models.quotes;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.fireflyframework.core.transaction.OwnedLeaseConnectionFactory;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class QuoteFrameworkAdoptionTest {
    @Test void originalAbsoluteExpiryCannotMoveDuringAnAdjustableClockSnapshot() {
        assertAbsoluteExpiryRetained(100, 500, false);
    }
    @Test void additionalAbsoluteExpiryCannotMoveDuringAnAdjustableClockSnapshot() {
        assertAbsoluteExpiryRetained(500, 100, false);
    }
    @Test void nativeAbsoluteExpiryCannotMoveDuringAnAdjustableClockSnapshot() {
        assertAbsoluteExpiryRetained(500, 500, true);
    }
    private static void assertAbsoluteExpiryRetained(long originalMillis, long additionalMillis, boolean nativeCap) {
        long[] reads = nativeCap ? new long[]{90,20,20,110} : new long[]{90,20,110};
        var index = new java.util.concurrent.atomic.AtomicInteger();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() {
                return QuoteTransactionBoundaryTest.BASE.plusMillis(reads[Math.min(index.getAndIncrement(), reads.length-1)]);
            }
        };
        var budget = QuoteAuthorityBudget.observe(QuoteTransactionBoundaryTest.BASE,
                QuoteTransactionBoundaryTest.BASE.plusMillis(originalMillis), 0, clock, () -> 10_000_000L);
        var window = nativeCap ? QuoteNativeWindow.from(QuoteTransactionBoundaryTest.BASE,
                QuoteTransactionBoundaryTest.BASE.toEpochMilli(), QuoteTransactionBoundaryTest.BASE.toEpochMilli()+100, 0) : null;
        var shared = budget.boundedSnapshot(QuoteTransactionBoundaryTest.BASE.plusMillis(additionalMillis), window);
        assertThrows(IllegalStateException.class, shared::remaining);
    }

    @Test void actualQuoteResourcesUseTheSharedOwnedFactoryIdentity() {
        var command = new QuoteTransactionBoundaryTest.Driver();
        var read = new QuoteTransactionBoundaryTest.Driver();
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var persistence = new QuotePersistence(command, read,
                    new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(), context);
            assertInstanceOf(OwnedLeaseConnectionFactory.class,
                    persistence.commandOperations().getDatabaseClient().getConnectionFactory());
            assertInstanceOf(OwnedLeaseConnectionFactory.class,
                    persistence.readOperations().getDatabaseClient().getConnectionFactory());
            assertSame(persistence.commandManager().getConnectionFactory(),
                    persistence.commandOperations().getDatabaseClient().getConnectionFactory());
            assertSame(persistence.readManager().getConnectionFactory(),
                    persistence.readOperations().getDatabaseClient().getConnectionFactory());
            assertNotSame(persistence.commandManager().getConnectionFactory(),
                    persistence.readManager().getConnectionFactory());
        }
        assertEquals(0, command.creates); assertEquals(0, read.creates);
    }

    @Test void ordinaryTypedWorkRejectionSurvivesObservedRollbackAndCompleteCleanup() {
        var driver = new QuoteTransactionBoundaryTest.Driver();
        var rejection = new IllegalArgumentException("ordinary quote rule rejection");
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var persistence = QuoteTransactionBoundaryTest.persistence(driver, context);
            StepVerifier.withVirtualTime(() -> QuoteTransactionBoundaryTest.invoke(persistence,
                    QuoteTransactionBoundaryTest.admission(1000), scope -> Mono.error(rejection)))
                    .expectErrorSatisfies(error -> assertSame(rejection, error)).verify(Duration.ofSeconds(1));
        }
        assertEquals(1, driver.rollbacks); assertEquals(1, driver.closes); assertEquals(0, driver.commits);
    }

    @Test void expiryDuringCommittedCloseWithholdsTheValueAndRetainsKnownCommit() {
        var now = new AtomicReference<>(QuoteTransactionBoundaryTest.BASE);
        var nanos = new AtomicLong();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var driver = new QuoteTransactionBoundaryTest.Driver();
        ConnectionFactory closesAfterExpiry = new ConnectionFactory() {
            public Publisher<? extends Connection> create() {
                return Mono.from(driver.create()).map(connection -> (Connection) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                            try {
                                if (method.getName().equals("close")) return Mono.from(connection.close()).doOnSuccess(ignored -> {
                                    now.set(QuoteTransactionBoundaryTest.BASE.plusSeconds(2)); nanos.set(2_000_000_000L);
                                });
                                return method.invoke(connection, arguments);
                            } catch (InvocationTargetException error) { throw error.getCause(); }
                        }));
            }
            public ConnectionFactoryMetadata getMetadata() { return driver.getMetadata(); }
        };
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var persistence = new QuotePersistence(closesAfterExpiry, new QuoteTransactionBoundaryTest.Driver(),
                    new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(), context);
            StepVerifier.withVirtualTime(() -> {
                var admission = QuoteTransactionBoundaryTest.admission(1000).withBudget(QuoteAuthorityBudget.observe(
                        QuoteTransactionBoundaryTest.BASE, QuoteTransactionBoundaryTest.BASE.plusSeconds(1),
                        0, clock, nanos::get));
                return QuoteTransactionBoundaryTest.invoke(persistence, admission, scope -> Mono.just("protected-value"));
            }).expectErrorSatisfies(error -> {
                var uncertain = assertInstanceOf(QuotePersistence.QuoteTransactionUncertain.class, error);
                assertTrue(uncertain.commitKnown()); assertFalse(error.toString().contains("protected-value"));
            }).verify(Duration.ofSeconds(1));
        }
        assertEquals(1, driver.commits); assertEquals(1, driver.closes);
    }

    @Test void expiredPriorNativeObservationRemainsUncertainBeforeAnyAcquisition() {
        var driver = new QuoteTransactionBoundaryTest.Driver();
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var persistence = QuoteTransactionBoundaryTest.persistence(driver, context);
            StepVerifier.withVirtualTime(() -> {
                var admission = QuoteTransactionBoundaryTest.admission(1000);
                var prior = QuoteNativeWindow.from(QuoteTransactionBoundaryTest.BASE,
                        QuoteTransactionBoundaryTest.BASE.toEpochMilli(),
                        QuoteTransactionBoundaryTest.BASE.toEpochMilli()+10, 0);
                return Mono.delay(Duration.ofMillis(20)).then(Mono.defer(() ->
                        persistence.command(admission, prior, scope -> Mono.just("protected-value"))));
            }).thenAwait(Duration.ofMillis(30))
                    .expectError(QuotePersistence.QuoteTransactionUncertain.class).verify(Duration.ofSeconds(1));
        }
        assertEquals(0, driver.creates); assertEquals(0, driver.closes);
    }

    @Test void stalledAfterCommitRetainsAcknowledgmentWithoutCompletedBoundary() {
        var driver = new QuoteTransactionBoundaryTest.Driver();
        try (var context = new GenericApplicationContext()) {
            context.refresh();
            var persistence = QuoteTransactionBoundaryTest.persistence(driver, context);
            StepVerifier.withVirtualTime(() -> persistence.command(
                    QuoteTransactionBoundaryTest.admission(1000), null, scope ->
                    org.springframework.transaction.reactive.TransactionSynchronizationManager.forCurrentTransaction()
                            .flatMap(manager -> {
                                manager.registerSynchronization(new org.springframework.transaction.reactive.TransactionSynchronization() {
                                    @Override public Mono<Void> afterCommit() { return Mono.never(); }
                                });
                                return Mono.just("protected-value");
                            }))).thenAwait(Duration.ofSeconds(5)).expectErrorSatisfies(error -> {
                                var uncertain = assertInstanceOf(QuotePersistence.QuoteTransactionUncertain.class, error);
                                assertTrue(uncertain.commitKnown());
                            }).verify(Duration.ofSeconds(1));
        }
        assertEquals(1, driver.commits); assertEquals(0, driver.rollbacks); assertEquals(1, driver.closes);
    }
}
