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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteControlOperations;
import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteRows;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.query.Update;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public final class QuoteControlOperationsImpl implements QuoteControlOperations {
    private final R2dbcEntityOperations operations;

    public QuoteControlOperationsImpl(R2dbcEntityOperations operations) {
        this.operations = Objects.requireNonNull(operations);
    }

    @Override
    public Mono<Integer> accept(UUID tenant, UUID party, UUID submission, UUID product, UUID offer,
            String issuer, String subject, UUID acceptanceId, OffsetDateTime recordedAt) {
        var scope = Criteria.where("tenantId").is(tenant)
                .and("partyId").is(party)
                .and("submissionId").is(submission)
                .and("productId").is(product)
                .and("offerId").is(offer)
                .and("ownerIssuer").is(issuer)
                .and("ownerSubject").is(subject)
                .and("currentOfferVersion").is(1L)
                .and("state").is("OFFERED")
                .and("controlGeneration").is(0L)
                .and("acceptanceId").isNull();
        var change = Update.update("state", "ACCEPTED")
                .set("controlGeneration", 1L)
                .set("acceptanceId", acceptanceId)
                .set("updatedAt", recordedAt);
        return operations.update(Query.query(scope), change, QuoteRows.Control.class)
                .map(Math::toIntExact);
    }
}
