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


package io.fireflysoftware.core.insurance.underwriting.models.quotes;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.Parameter;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class QuoteControlMappedCasTest {
    private static final UUID TENANT = id(1), PARTY = id(2), SUBMISSION = id(3), PRODUCT = id(4),
            OFFER = id(5), ACCEPTANCE = id(6);
    private static final OffsetDateTime RECORDED = OffsetDateTime.parse("2026-10-31T10:11:12.123456Z");

    @Test
    void acceptanceUsesMappedFragmentRatherThanAnOrdinarySqlQuery() {
        var accept = Arrays.stream(QuoteRepositories.Controls.class.getMethods())
                .filter(method -> method.getName().equals("accept")).findFirst().orElseThrow();
        assertNull(accept.getAnnotation(Query.class));
        assertNull(accept.getAnnotation(Modifying.class));
    }

    public static void assertMappedScopeAndColumns() {
        var backend = new Backend(Mono.just(1L));
        assertEquals(1, accept(backend.session(), TENANT).block(Duration.ofSeconds(2)));
        assertEquals(1, backend.statements.size());
        var statement = backend.statements.getFirst();
        String sql = statement.sql.replace("\"", "").replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.startsWith("update insurance_quote.offer_control set "), sql);
        String changes = sql.substring(sql.indexOf(" set ") + 5, sql.indexOf(" where "));
        assertEquals(java.util.Set.of("state", "control_generation", "acceptance_id", "updated_at"),
                new HashSet<>(Arrays.stream(changes.split(","))
                        .map(value -> value.trim().split("=")[0].trim()).toList()));
        String where = sql.substring(sql.indexOf(" where "));
        for (String field : List.of("tenant_id", "party_id", "submission_id", "product_id", "offer_id",
                "owner_issuer", "owner_subject", "current_offer_version", "state", "control_generation")) {
            assertTrue(where.matches(".*\\b" + field + " = \\$[0-9]+.*"), field + " in " + where);
        }
        assertTrue(where.contains("acceptance_id is null"));
        assertEquals(14, statement.bindings.size());
        var values = new ArrayList<>(statement.bindings.values());
        assertTrue(values.containsAll(List.of(TENANT, PARTY, SUBMISSION, PRODUCT, OFFER, ACCEPTANCE,
                "issuer", "subject", "ACCEPTED", "OFFERED", RECORDED, 0L, 1L)));
        assertEquals(2, values.stream().filter(Long.valueOf(1L)::equals).count());
        assertEquals(1, backend.acquired);
        assertEquals(1, backend.closed);
    }

    @Test
    void actualSessionFactoryBindsTheFragmentAndEachSubscriptionAcquiresAndReleases() {
        var backend = new Backend(Mono.just(1L));
        var result = accept(backend.session(), TENANT);
        assertEquals(0, backend.acquired);
        assertEquals(1, result.block(Duration.ofSeconds(2)));
        assertEquals(1, result.block(Duration.ofSeconds(2)));
        assertEquals(2, backend.acquired);
        assertEquals(2, backend.closed);
    }

    @Test
    void actualAffectedCountsRemainVisibleAndMissingCountCannotBecomeOne() {
        for (long count : new long[]{0, 1, 2, -1}) {
            var backend = new Backend(Mono.just(count));
            assertEquals((int) count, accept(backend.session(), TENANT).block(Duration.ofSeconds(2)));
            assertEquals(1, backend.closed);
        }
        var missing = new Backend(Mono.empty());
        assertNotEquals(1, accept(missing.session(), TENANT).block(Duration.ofSeconds(2)));
        assertEquals(1, missing.closed);
        var overflow = new Backend(Mono.just((long) Integer.MAX_VALUE + 1));
        assertThrows(ArithmeticException.class,
                () -> accept(overflow.session(), TENANT).block(Duration.ofSeconds(2)));
        assertEquals(1, overflow.closed);
    }

    @Test
    void eachInvocationBindsItsOwnScopeAndDoesNotInterpolateCallerValues() {
        var backend = new Backend(Mono.just(1L));
        var session = backend.session();
        assertEquals(1, accept(session, TENANT).block(Duration.ofSeconds(2)));
        assertEquals(1, accept(session, id(99)).block(Duration.ofSeconds(2)));
        assertEquals(backend.statements.get(0).sql, backend.statements.get(1).sql);
        assertTrue(backend.statements.get(0).bindings.containsValue(TENANT));
        assertFalse(backend.statements.get(0).bindings.containsValue(id(99)));
        assertTrue(backend.statements.get(1).bindings.containsValue(id(99)));
        assertFalse(backend.statements.get(1).bindings.containsValue(TENANT));
        assertFalse(backend.statements.getFirst().sql.contains(TENANT.toString()));
        assertEquals(2, backend.closed);
    }

    private static Mono<Integer> accept(QuotePersistence.Session session, UUID tenant) {
        return session.controls.accept(tenant, PARTY, SUBMISSION, PRODUCT, OFFER,
                "issuer", "subject", ACCEPTANCE, RECORDED);
    }

    private static UUID id(long value) { return new UUID(0, value); }

    private record Captured(String sql, Map<Integer, Object> bindings) {}

    private static final class Backend {
        private final Mono<Long> count;
        private final List<Captured> statements = new ArrayList<>();
        private int acquired, closed;

        private Backend(Mono<Long> count) { this.count = count; }

        private QuotePersistence.Session session() {
            ConnectionFactory factory = new ConnectionFactory() {
                @Override public Mono<? extends Connection> create() {
                    return Mono.fromSupplier(() -> {
                        acquired++;
                        return proxy(Connection.class, (self, method, args) -> switch (method.getName()) {
                            case "createStatement" -> statement((String) args[0]);
                            case "close" -> Mono.fromRunnable(() -> closed++);
                            case "isAutoCommit" -> true;
                            case "toString" -> "SyntheticQuoteConnection";
                            default -> throw new AssertionError(method.getName());
                        });
                    });
                }
                @Override public ConnectionFactoryMetadata getMetadata() { return () -> "PostgreSQL"; }
            };
            var strategy = new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE);
            return new QuotePersistence.Session(factory, strategy.getConverter(), new StaticApplicationContext(), true);
        }

        private Statement statement(String sql) {
            Map<Integer, Object> values = new TreeMap<>();
            statements.add(new Captured(sql, values));
            Result result = proxy(Result.class, (self, method, args) -> switch (method.getName()) {
                case "getRowsUpdated" -> count;
                default -> throw new AssertionError(method.getName());
            });
            return proxy(Statement.class, (self, method, args) -> switch (method.getName()) {
                case "bind" -> {
                    Object value = args[1] instanceof Parameter parameter ? parameter.getValue() : args[1];
                    values.put((Integer) args[0], value);
                    yield self;
                }
                case "bindNull" -> { values.put((Integer) args[0], null); yield self; }
                case "execute" -> Flux.just(result);
                case "add", "fetchSize", "returnGeneratedValues" -> self;
                default -> throw new AssertionError(method.getName());
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> {
                    if (method.getName().equals("equals")) return self == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                    return handler.invoke(self, method, args);
                }));
    }
}
