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

import io.r2dbc.spi.ConnectionFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.fireflyframework.core.transaction.BoundedR2dbcResources;
import org.fireflyframework.core.transaction.BoundedRootTransaction;
import org.fireflyframework.core.transaction.CleanupFact;
import org.fireflyframework.core.transaction.CleanupPolicy;
import org.fireflyframework.core.transaction.OwnedLeaseConnectionFactory;
import org.fireflyframework.core.transaction.RootCompletion;
import org.fireflyframework.core.transaction.RootTransactionException;
import org.fireflyframework.core.transaction.RootTransactionScope;
import org.fireflyframework.core.transaction.impl.DefaultBoundedRootTransaction;
import org.springframework.context.ApplicationContext;
import org.springframework.data.r2dbc.convert.R2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;

/** Quote-specific authority and outcomes over the opt-in shared transaction capability. */
public final class QuotePersistenceImpl {
    private static final CleanupPolicy CLEANUP = new CleanupPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2));
    final Session commands, reads;
    private final R2dbcTransactionManager commandManager, readManager;
    private final DefaultTransactionDefinition commandDefinition, readDefinition;
    private final BoundedRootTransaction commandTransaction, readTransaction;

    public QuotePersistenceImpl(ConnectionFactory commands, ConnectionFactory reads,
            R2dbcConverter converter, ApplicationContext context) {
        Objects.requireNonNull(commands); Objects.requireNonNull(reads);
        Objects.requireNonNull(converter); Objects.requireNonNull(context);
        if (commands == reads) throw new IllegalArgumentException("Distinct qualified quote factories required");
        var commandFactory = new OwnedLeaseConnectionFactory(commands);
        var readFactory = new OwnedLeaseConnectionFactory(reads);
        this.commands = new Session(commandFactory, converter, context, true);
        this.reads = new Session(readFactory, converter, context, false);
        commandManager = new R2dbcTransactionManager(commandFactory);
        readManager = new R2dbcTransactionManager(readFactory);
        readManager.setEnforceReadOnly(true);
        commandTransaction = new DefaultBoundedRootTransaction(BoundedR2dbcResources.verify(commandFactory,
                this.commands.operations.getDatabaseClient(), this.commands.operations, commandManager), CLEANUP);
        readTransaction = new DefaultBoundedRootTransaction(BoundedR2dbcResources.verify(readFactory,
                this.reads.operations.getDatabaseClient(), this.reads.operations, readManager), CLEANUP);
        commandDefinition = definition("governed-quote-command", false, TransactionDefinition.ISOLATION_READ_COMMITTED);
        readDefinition = definition("governed-quote-retained-read", true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    private static DefaultTransactionDefinition definition(String name, boolean readOnly, int isolation) {
        var value = new DefaultTransactionDefinition();
        value.setName(name); value.setReadOnly(readOnly); value.setIsolationLevel(isolation);
        value.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED); value.setTimeout(5);
        return value;
    }
    public R2dbcEntityTemplate commandOperations() { return commands.operations; }
    public R2dbcEntityTemplate readOperations() { return reads.operations; }
    public R2dbcTransactionManager commandManager() { return commandManager; }
    public R2dbcTransactionManager readManager() { return readManager; }
    public DefaultTransactionDefinition commandDefinition() { return new DefaultTransactionDefinition(commandDefinition); }
    public DefaultTransactionDefinition readDefinition() { return new DefaultTransactionDefinition(readDefinition); }

    <T> Mono<T> command(QuoteAdmission admission, QuoteNativeWindow prior, Function<Invocation, Mono<T>> work) {
        return isolated(commandTransaction, commandDefinition, admission, prior, work);
    }
    <T> Mono<T> read(QuoteAdmission admission, QuoteNativeWindow prior, Function<Invocation, Mono<T>> work) {
        return isolated(readTransaction, readDefinition, admission, prior, work);
    }
    <T> Mono<T> command(Supplier<Mono<T>> ignored) {
        return Mono.error(new IllegalArgumentException("Original quote observation budget required"));
    }
    <T> Mono<T> read(Supplier<Mono<T>> ignored) {
        return Mono.error(new IllegalArgumentException("Original quote observation budget required"));
    }

    private static <T> Mono<T> isolated(BoundedRootTransaction transaction, TransactionDefinition definition,
            QuoteAdmission admission, QuoteNativeWindow prior, Function<Invocation, Mono<T>> work) {
        return Mono.defer(() -> {
            var original = Objects.requireNonNull(admission.authorityBudget(), "Original quote observation budget required");
            Instant until = Collections.min(List.of(admission.actorUntil(), admission.permitUntil(), admission.configUntil()));
            var workError = new AtomicReference<Throwable>();
            return transaction.execute(original.boundedSnapshot(until, prior), definition, scope -> {
                var invocation = new Invocation(original, until, prior, scope);
                return Mono.defer(() -> work.apply(invocation)).doOnError(workError::set);
            }).map(RootCompletion::value).onErrorMap(RootTransactionException.class, error -> {
                var facts = error.transactionFacts();
                if (workError.get() != null && facts.rollbackAcknowledged()
                        && !facts.commitAcknowledged() && !facts.boundaryCompleted()
                        && error.cleanupFact() == CleanupFact.COMPLETE) return workError.get();
                return new QuoteTransactionUncertain(facts.commitAcknowledged());
            });
        });
    }

    public static final class QuoteTransactionUncertain extends IllegalStateException {
        private final boolean commitKnown;
        public QuoteTransactionUncertain(boolean commitKnown) {
            super("QUOTE_TRANSACTION_COMPLETION_UNCERTAIN"); this.commitKnown = commitKnown;
        }
        public boolean commitKnown() { return commitKnown; }
    }

    public static final class Invocation {
        private final QuoteAuthorityBudget original;
        private final Instant absoluteUntil;
        private final RootTransactionScope shared;
        private final AtomicReference<QuoteNativeWindow> nativeWindow;
        private final AtomicBoolean nativeObserved = new AtomicBoolean();

        private Invocation(QuoteAuthorityBudget original, Instant until, QuoteNativeWindow prior, RootTransactionScope shared) {
            this.original = original; this.absoluteUntil = until; this.shared = shared;
            nativeWindow = new AtomicReference<>(prior); remaining();
        }
        public void observeNative(QuoteNativeWindow window) {
            Objects.requireNonNull(window);
            if (!nativeObserved.compareAndSet(false, true)) throw new QuoteTransactionUncertain(false);
            var effective = nativeWindow.updateAndGet(prior -> prior == null ? window : prior.intersect(window));
            remaining();
            shared.narrow(original.boundedSnapshot(absoluteUntil, effective));
        }
        public Duration remaining() {
            try {
                var remaining = original.remaining(absoluteUntil);
                var window = nativeWindow.get();
                if (window != null) {
                    var nativeRemaining = window.remaining(original.clock().instant(), original.nanos().getAsLong());
                    if (nativeRemaining.compareTo(remaining) < 0) remaining = nativeRemaining;
                }
                var sharedRemaining = shared.remaining();
                return sharedRemaining.compareTo(remaining) < 0 ? sharedRemaining : remaining;
            } catch (RuntimeException expired) { throw new QuoteTransactionUncertain(false); }
        }
    }
    static final class Session {
        final R2dbcEntityTemplate operations;
        final QuoteRepositories.Controls controls;final QuoteRepositories.Versions versions;final QuoteRepositories.Acceptances acceptances;
        final QuoteRepositories.Actions actions;final QuoteRepositories.Native nativeContext;final QuoteRepositories.AcceptMetadata acceptMetadata;
        final QuoteRepositories.Submissions submissions;final QuoteRepositories.Risks risks;final QuoteRepositories.Values values;
        Session(ConnectionFactory cf,R2dbcConverter converter,ApplicationContext context,boolean command){
            operations=new R2dbcEntityTemplate(DatabaseClient.create(cf),PostgresDialect.INSTANCE,converter);operations.setBeanFactory(context);operations.setApplicationContext(context);
            var factory=new R2dbcRepositoryFactory(operations);factory.setBeanFactory(context);
            controls=factory.getRepository(QuoteRepositories.Controls.class,
                    RepositoryFragments.just(new QuoteControlOperationsImpl(operations)));versions=factory.getRepository(QuoteRepositories.Versions.class);
            acceptances=factory.getRepository(QuoteRepositories.Acceptances.class);actions=factory.getRepository(QuoteRepositories.Actions.class);
            nativeContext=factory.getRepository(QuoteRepositories.Native.class);acceptMetadata=factory.getRepository(QuoteRepositories.AcceptMetadata.class);
            submissions=command?factory.getRepository(QuoteRepositories.Submissions.class):null;risks=command?factory.getRepository(QuoteRepositories.Risks.class):null;values=command?factory.getRepository(QuoteRepositories.Values.class):null;
        }
    }
    @Override public String toString(){return "QuotePersistence";}
}
