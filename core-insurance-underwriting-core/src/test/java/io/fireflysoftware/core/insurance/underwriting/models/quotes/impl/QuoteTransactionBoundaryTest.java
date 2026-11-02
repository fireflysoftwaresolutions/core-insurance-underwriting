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

package io.fireflysoftware.core.insurance.underwriting.models.quotes.impl;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.*;

import io.r2dbc.spi.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring transaction lifecycle over networkless driver publishers, not SQL/native/database proof. */
class QuoteTransactionBoundaryTest {
    static final Instant BASE=Instant.parse("2026-10-26T12:00:00Z");
    static Clock clock() {return new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return BASE.plusNanos(VirtualTimeScheduler.get().now(TimeUnit.NANOSECONDS));}};}
    static LongSupplier nanos(){return ()->VirtualTimeScheduler.get().now(TimeUnit.NANOSECONDS);}
    static final class Driver implements ConnectionFactory {
        boolean stallAcquire,stallBegin,stallCommit,stallRollback,stallStatement,stallClose;
        int creates,begins,commits,rollbacks,closes,acquireCancelled,beginCancelled,commitCancelled,rollbackCancelled,statementCancelled,closeCancelled;
        final AtomicBoolean closed=new AtomicBoolean();
        final Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()) {
            case "isAutoCommit" -> true;
            case "beginTransaction" -> Mono.defer(()->{begins++;return stallBegin?Mono.<Void>never().doOnCancel(()->beginCancelled++):Mono.empty();});
            case "commitTransaction" -> Mono.defer(()->{commits++;return stallCommit?Mono.<Void>never().doOnCancel(()->commitCancelled++):Mono.empty();});
            case "rollbackTransaction" -> Mono.defer(()->{rollbacks++;return stallRollback?Mono.<Void>never().doOnCancel(()->rollbackCancelled++):Mono.empty();});
            case "close" -> Mono.defer(()->{if(stallClose)return Mono.<Void>never().doOnCancel(()->closeCancelled++);return Mono.fromRunnable(()->{if(closed.compareAndSet(false,true))closes++;});});
            case "createStatement" -> statement();
            case "toString" -> "OwnedNetworklessConnection";
            default -> Mono.empty();
        });
        Statement statement(){return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
            if(m.getName().equals("execute"))return Mono.<Result>never().doOnCancel(()->statementCancelled++);
            if(m.getReturnType()==Statement.class)return p;throw new UnsupportedOperationException(m.getName());
        });}
        public Publisher<? extends Connection> create(){return Mono.defer(()->{creates++;return stallAcquire?Mono.<Connection>never().doOnCancel(()->acquireCancelled++):Mono.just(connection);});}
        public ConnectionFactoryMetadata getMetadata(){return ()->"PostgreSQL";}
    }
    static QuoteAdmission admission(long millis){
        var a=new QuoteAdmission(new UUID(0,1),QuoteAdmission.Mode.OFFER_PREPARE,new UUID(0,2),new UUID(0,3),new UUID(0,4),null,null,new UUID(0,5),"https://issuer.example","human",BASE.plusMillis(millis),BASE.plusMillis(millis),BASE.plusMillis(millis),"a".repeat(64));
        try {
            var type=Class.forName("io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteAuthorityBudget");
            var budget=type.getMethod("observe",Instant.class,Instant.class,long.class,Clock.class,LongSupplier.class).invoke(null,BASE,BASE.plusMillis(millis),0L,clock(),nanos());
            return (QuoteAdmission)QuoteAdmission.class.getMethod("withBudget",type).invoke(a,budget);
        }catch(ClassNotFoundException missing){return a;}catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    static Mono<String> invoke(QuotePersistenceImpl p,QuoteAdmission a,Function<Object,Mono<String>> work){
        try {
            var method=QuotePersistenceImpl.class.getDeclaredMethod("command",QuoteAdmission.class,QuoteNativeWindow.class,Function.class);
            return (Mono<String>)method.invoke(p,a,null,work);
        }catch(NoSuchMethodException old){return p.command(()->work.apply(null));}catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    static void nativeWindow(Object budget,long millis){
        if(budget==null)return;
        try {budget.getClass().getMethod("observeNative",QuoteNativeWindow.class).invoke(budget,QuoteNativeWindow.from(BASE,BASE.toEpochMilli(),BASE.toEpochMilli()+millis,0));}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    static QuotePersistenceImpl persistence(Driver d,GenericApplicationContext context){return new QuotePersistenceImpl(d,new Driver(),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);}
    static void uncertain(Throwable e){assertEquals("QuoteTransactionUncertain",e.getClass().getSimpleName());assertFalse(e.toString().contains("protected-value"));}

    @Test void acquisitionIsBoundedByOriginalAuthority(){
        var d=new Driver();d.stallAcquire=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->Mono.just("protected-value"))).thenAwait(Duration.ofSeconds(4)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));}
        assertEquals(1,d.acquireCancelled);assertEquals(0,d.begins);
    }
    @Test void stalledBeginReleasesOnlyItsCreatedLease(){
        var d=new Driver();d.stallBegin=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->Mono.just("protected-value"))).thenAwait(Duration.ofSeconds(4)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));}
        assertEquals(1,d.beginCancelled);assertEquals(1,d.closes);assertEquals(0,d.commits);
    }
    @Test void actualNativeWindowTightensWorkBeforeFiveSeconds(){
        var d=new Driver();var cancelled=new AtomicInteger();try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(5000),b->{nativeWindow(b,1000);return Mono.<String>never().doOnCancel(cancelled::incrementAndGet);})).thenAwait(Duration.ofSeconds(4)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));}
        assertEquals(1,cancelled.get());assertEquals(0,d.commits);assertEquals(1,d.closes);
    }
    @Test void nativeStatementStallDoesNotInventWitnessOrAuthority(){
        var d=new Driver();try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->p.commandOperations().getDatabaseClient().sql("SELECT 1").fetch().rowsUpdated().map(v->"protected-value")))
                .thenAwait(Duration.ofSeconds(4)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));}
        assertEquals(1,d.statementCancelled);assertEquals(0,d.commits);assertEquals(1,d.closes);
    }
    @Test void commitPublisherIsActuallyCancelledAndNoProtectedValueEscapes(){
        var d=new Driver();d.stallCommit=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->Mono.just("protected-value"))).thenAwait(Duration.ofSeconds(5)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));}
        assertEquals(1,d.commitCancelled);assertEquals(1,d.closes);
    }
    @Test void stalledRollbackHasSeparateFiniteCleanupAndStaysUncertain(){
        var d=new Driver();d.stallRollback=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->Mono.error(new IllegalStateException("normal callback rejection"))))
                .thenAwait(Duration.ofSeconds(6)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));}
        assertTrue(d.rollbackCancelled>0);assertEquals(1,d.closes);assertEquals(0,d.commits);
    }
    @Test void sourceControlOuterTimeoutAloneDoesNotReleaseR2dbcBeginLease(){
        var d=new Driver();d.stallBegin=true;
        StepVerifier.withVirtualTime(()->TransactionalOperator.create(new R2dbcTransactionManager(d)).transactional(Mono.just("protected-value")).timeout(Duration.ofSeconds(30)))
            .thenAwait(Duration.ofSeconds(31)).expectError(java.util.concurrent.TimeoutException.class).verify(Duration.ofSeconds(1));
        assertEquals(1,d.beginCancelled);assertEquals(0,d.closes);assertEquals(0,d.commits);
    }

    @Test void successfulTransactionEmitsOnlyAfterItsLeaseClose() {
        var d=new Driver();try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->Mono.just("protected-value")))
                .assertNext(value->{assertEquals("protected-value",value);assertEquals(1,d.closes);}).verifyComplete();
        }assertEquals(1,d.commits);assertEquals(0,d.rollbacks);assertEquals(1,d.creates);
    }
    @Test void callerCancellationClosesTheTrackedBeginLease() {
        var d=new Driver();d.stallBegin=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(5000),b->Mono.just("protected-value"))).thenAwait(Duration.ofMillis(10)).thenCancel().verify(Duration.ofSeconds(1));
        }assertEquals(1,d.beginCancelled);assertEquals(1,d.closes);assertEquals(0,d.commits);
    }
    @Test void anotherFactoryIsNeitherAcquiredNorClosedByThisInvocation() {
        var owned=new Driver();var foreign=new Driver();try(var context=new GenericApplicationContext()){context.refresh();var p=new QuotePersistenceImpl(owned,foreign,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->p.readOperations().getDatabaseClient().sql("SELECT 1").fetch().rowsUpdated().map(v->"protected-value")))
                .expectError().verify(Duration.ofSeconds(1));
        }assertEquals(1,owned.closes);assertEquals(0,foreign.creates);assertEquals(0,foreign.closes);
    }
    @Test void delayBeforeTransactionCannotResetOriginalAuthorityBudget() {
        var d=new Driver();d.stallBegin=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->{var original=admission(1000);return Mono.delay(Duration.ofMillis(800)).then(Mono.defer(()->invoke(p,original,b->Mono.just("protected-value"))));})
                .thenAwait(Duration.ofMillis(1100)).expectErrorSatisfies(QuoteTransactionBoundaryTest::uncertain).verify(Duration.ofSeconds(1));
        }assertEquals(1,d.closes);assertEquals(1,d.beginCancelled);
    }
    @Test void stalledCloseAfterConfirmedCommitKeepsKnownCommitWithoutResult() {
        var d=new Driver();d.stallClose=true;try(var context=new GenericApplicationContext()){context.refresh();var p=persistence(d,context);
            StepVerifier.withVirtualTime(()->invoke(p,admission(1000),b->Mono.just("protected-value"))).thenAwait(Duration.ofSeconds(5))
                .expectErrorSatisfies(error->{uncertain(error);try {assertEquals(true,error.getClass().getMethod("commitKnown").invoke(error));}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}).verify(Duration.ofSeconds(1));
        }assertEquals(1,d.commits);assertEquals(1,d.closeCancelled);
    }
}
