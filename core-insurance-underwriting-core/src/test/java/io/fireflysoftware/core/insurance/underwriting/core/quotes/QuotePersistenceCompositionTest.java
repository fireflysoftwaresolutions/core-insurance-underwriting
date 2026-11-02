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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuotePersistenceImpl;
import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.transaction.TransactionDefinition;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class QuotePersistenceCompositionTest {
    private ConnectionFactory noConnect() { return new ConnectionFactory() {
        public Mono<? extends Connection> create() {throw new AssertionError("No database access permitted in composition controls");}
        public ConnectionFactoryMetadata getMetadata() {return ()->"PostgreSQL";}
    }; }
    @Test void templatesAndManagersShareExactlyTheirQualifiedFactory() {
        var commands=noConnect();var reads=noConnect();
        try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(commands,reads,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            var commandFactory=p.commandOperations().getDatabaseClient().getConnectionFactory();
            var readFactory=p.readOperations().getDatabaseClient().getConnectionFactory();
            assertSame(commands,((org.springframework.r2dbc.connection.DelegatingConnectionFactory)commandFactory).getTargetConnectionFactory());
            assertSame(reads,((org.springframework.r2dbc.connection.DelegatingConnectionFactory)readFactory).getTargetConnectionFactory());
            assertSame(commandFactory,p.commandManager().getConnectionFactory());assertSame(readFactory,p.readManager().getConnectionFactory());
            assertNotSame(p.commandOperations(),p.readOperations());
        }
    }
    @Test void explicitCommandRcAndEnforcedReadOnlyRrNeverRequireNew() {
        try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(noConnect(),noConnect(),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,p.commandDefinition().getIsolationLevel());
            assertFalse(p.commandDefinition().isReadOnly());
            assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ,p.readDefinition().getIsolationLevel());
            assertTrue(p.readDefinition().isReadOnly());assertTrue(p.readManager().isEnforceReadOnly());
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRED,p.commandDefinition().getPropagationBehavior());
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRED,p.readDefinition().getPropagationBehavior());
            assertTrue(p.commandDefinition().getTimeout()>0);assertTrue(p.readDefinition().getTimeout()>0);
        }
    }
    @Test void sameFactoryCannotServeBothRoles() {
        var cf=noConnect();try(var context=new GenericApplicationContext()) {
            context.refresh();assertThrows(IllegalArgumentException.class,()->new QuotePersistenceImpl(cf,cf,new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context));
        }
    }
    @Test void callerCannotChangeRetainedTransactionDefinition() {
        try(var context=new GenericApplicationContext()) {
            context.refresh();var p=new QuotePersistenceImpl(noConnect(),noConnect(),new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE).getConverter(),context);
            p.commandDefinition().setIsolationLevel(TransactionDefinition.ISOLATION_READ_UNCOMMITTED);
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,p.commandDefinition().getIsolationLevel());
        }
    }
}
