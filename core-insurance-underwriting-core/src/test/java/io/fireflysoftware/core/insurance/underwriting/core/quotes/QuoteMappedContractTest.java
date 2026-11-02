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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteRows;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteRepositories;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import io.r2dbc.spi.*;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class QuoteMappedContractTest {
    @Test void fourActualMappingEntitiesUseScopedSchemaAndSingleUuidId() {
        var context=new R2dbcMappingContext();
        for(var type:List.of(QuoteRows.Control.class,QuoteRows.Version.class,QuoteRows.Acceptance.class,QuoteRows.Action.class)) {
            var entity=context.getRequiredPersistentEntity(type);
            assertEquals(UUID.class,entity.getRequiredIdProperty().getType());
            assertEquals("insurance_quote",type.getAnnotation(Table.class).schema());
            assertEquals(1,Arrays.stream(type.getDeclaredFields()).filter(c->c.getAnnotation(Id.class)!=null).count());
        }
    }
    @Test void exactBusinessColumnCountsHaveNoHiddenActorOrConfigField() {
        for(var pair:Map.of(QuoteRows.Control.class,14,QuoteRows.Version.class,24,QuoteRows.Acceptance.class,21,QuoteRows.Action.class,21).entrySet()) {
            assertEquals(pair.getValue(),pair.getKey().getRecordComponents().length);
            for(var c:pair.getKey().getDeclaredFields())assertNotNull(c.getAnnotation(Column.class));
        }
    }
    @Test void sourceViewsMapOnlyReviewedColumns() {
        for(var pair:Map.of(QuoteRows.SubmissionSource.class,7,QuoteRows.RiskSource.class,7,QuoteRows.ValueSource.class,8).entrySet()) {
            assertEquals(pair.getValue(),pair.getKey().getRecordComponents().length);
            assertEquals("insurance_quote",pair.getKey().getAnnotation(Table.class).schema());
        }
    }
    @Test void repositoriesExposeNoSaveDeleteOrUnboundedCollectionCrud() {
        for(var type:List.of(QuoteRepositories.Controls.class,QuoteRepositories.Versions.class,QuoteRepositories.Acceptances.class,QuoteRepositories.Actions.class)) {
            for(var method:type.getMethods())assertFalse(Set.of("save","saveAll","delete","deleteAll","findAll").contains(method.getName()));
        }
    }
    @Test void controlCasChangesExactlyFourColumnsAndChecksFullScopeVersionOwner() throws Exception {
        io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteControlMappedCasTest.assertMappedScopeAndColumns();
    }
    @Test void historyAndSourceChildQueriesAreBoundedAtRepositoryLevel() {
        assertTrue(Arrays.stream(QuoteRepositories.Controls.class.getMethods()).anyMatch(m->m.getName().startsWith("findFirst20By")));
        assertTrue(Arrays.stream(QuoteRepositories.Risks.class.getMethods()).anyMatch(m->m.getName().startsWith("findFirst2By")));
        assertTrue(Arrays.stream(QuoteRepositories.Values.class.getMethods()).anyMatch(m->m.getName().startsWith("findFirst2By")));
    }
    @Test void actualSpringDataFactoryParsesAllNineRepositoryContractsWithoutConnecting() {
        var cf=new ConnectionFactory() {
            public Mono<? extends Connection> create() { throw new AssertionError("No database access is permitted"); }
            public ConnectionFactoryMetadata getMetadata() { return ()->"PostgreSQL"; }
        };
        var strategy=new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE);
        var operations=new R2dbcEntityTemplate(DatabaseClient.create(cf),PostgresDialect.INSTANCE,strategy.getConverter());
        var factory=new R2dbcRepositoryFactory(operations);
        for(var type:List.of(QuoteRepositories.Controls.class,QuoteRepositories.Versions.class,QuoteRepositories.Acceptances.class,
                QuoteRepositories.Actions.class,QuoteRepositories.Submissions.class,QuoteRepositories.Risks.class,
                QuoteRepositories.Values.class,QuoteRepositories.AcceptMetadata.class,QuoteRepositories.Native.class)) assertNotNull(factory.getRepository(type,
                        type==QuoteRepositories.Controls.class
                                ? org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments.just(
                                        new io.fireflysoftware.core.insurance.underwriting.models.quotes.impl.QuoteControlOperationsImpl(operations))
                                : org.springframework.data.repository.core.support.RepositoryComposition.RepositoryFragments.empty()));
    }
    @Test void acceptPrerequisiteProjectionHasOnlyScopedImmutableRevisionDigests() {
        var columns=Arrays.stream(QuoteRows.AcceptMetadata.class.getDeclaredFields()).map(field->field.getAnnotation(Column.class).value()).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("offer_version_id","offer_id","offer_version","tenant_id","party_id","submission_id","product_id","owner_issuer","owner_subject","product_revision_id","product_digest","terms_digest"),columns);
        var context=new R2dbcMappingContext();assertEquals(UUID.class,context.getRequiredPersistentEntity(QuoteRows.AcceptMetadata.class).getRequiredIdProperty().getType());
        var strategy=new DefaultReactiveDataAccessStrategy(PostgresDialect.INSTANCE);
        assertEquals(columns,strategy.getAllColumns(QuoteRows.AcceptMetadata.class).stream().map(column->column.getReference()).collect(java.util.stream.Collectors.toSet()));
    }
}
