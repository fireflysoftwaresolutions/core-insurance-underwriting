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

import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Created only by the dedicated command/read factories; nested repositories are not component-scanned. */
public final class QuoteRepositories {
    private QuoteRepositories() {}
    public interface Controls extends Repository<QuoteRows.Control, UUID>, QuoteControlOperations {
        Mono<QuoteRows.Control> findByOfferId(UUID offerId);
        Flux<QuoteRows.Control> findFirst20ByTenantIdAndPartyIdAndSubmissionIdAndProductIdAndOwnerIssuerAndOwnerSubjectOrderByCreatedAtDescOfferIdDesc(
                UUID tenantId, UUID partyId, UUID submissionId, UUID productId, String ownerIssuer, String ownerSubject);

    }
    public interface Versions extends Repository<QuoteRows.Version, UUID> { Mono<QuoteRows.Version> findByOfferId(UUID offerId); }
    public interface Acceptances extends Repository<QuoteRows.Acceptance, UUID> { Mono<QuoteRows.Acceptance> findByAcceptanceId(UUID acceptanceId); }
    public interface Actions extends Repository<QuoteRows.Action, UUID> {
        Flux<QuoteRows.Action> findFirst3ByOfferId(UUID offerId);
        Mono<QuoteRows.Action> findByTenantIdAndPartyIdAndSubmissionIdAndProductIdAndActionIdAndOwnerIssuerAndOwnerSubject(
                UUID tenantId, UUID partyId, UUID submissionId, UUID productId, UUID actionId, String ownerIssuer, String ownerSubject);
    }
    public interface Submissions extends Repository<QuoteRows.SubmissionSource, UUID> { Mono<QuoteRows.SubmissionSource> findBySubmissionId(UUID submissionId); }
    public interface Risks extends Repository<QuoteRows.RiskSource, UUID> { Flux<QuoteRows.RiskSource> findFirst2BySubmissionId(UUID submissionId); }
    public interface Values extends Repository<QuoteRows.ValueSource, UUID> { Flux<QuoteRows.ValueSource> findFirst2BySubmissionId(UUID submissionId); }
    public interface AcceptMetadata extends Repository<QuoteRows.AcceptMetadata,UUID> { Mono<QuoteRows.AcceptMetadata> findByOfferId(UUID offerId); }
    public interface Native extends Repository<QuoteRows.Control, UUID> {
        @Query("SELECT insurance_quote.begin_command(:nonce,:mode,:tenant,:submission,:party,:product,:offer,:action,:issuer,:subject,:actorUntil,:permitUntil,:configUntil,:evidenceDigest) AS nonce")
        Mono<UUID> beginCommand(@Param("nonce") UUID nonce,
                @Param("mode") String mode,
                @Param("tenant") UUID tenant,
                @Param("submission") UUID submission,
                @Param("party") UUID party,
                @Param("product") UUID product,
                @Param("offer") UUID offer,
                @Param("action") UUID action,
                @Param("issuer") String issuer,
                @Param("subject") String subject,
                @Param("actorUntil") long actorUntil,
                @Param("permitUntil") long permitUntil,
                @Param("configUntil") long configUntil,
                @Param("evidenceDigest") String evidenceDigest);
        @Query("SELECT * FROM insurance_quote.begin_read(:nonce,:mode,:tenant,:submission,:party,:product,:offer,:action,:issuer,:subject,:actorUntil,:permitUntil,:configUntil,:evidenceDigest)")
        Mono<QuoteRows.NativeStamp> beginRead(@Param("nonce") UUID nonce,
                @Param("mode") String mode,
                @Param("tenant") UUID tenant,
                @Param("submission") UUID submission,
                @Param("party") UUID party,
                @Param("product") UUID product,
                @Param("offer") UUID offer,
                @Param("action") UUID action,
                @Param("issuer") String issuer,
                @Param("subject") String subject,
                @Param("actorUntil") long actorUntil,
                @Param("permitUntil") long permitUntil,
                @Param("configUntil") long configUntil,
                @Param("evidenceDigest") String evidenceDigest);
        @Query("SELECT * FROM insurance_quote.lock_submission(:nonce)")
        Mono<QuoteRows.NativeStamp> lockSubmission(@Param("nonce") UUID nonce);
        @Query("SELECT insurance_quote.context_current(:nonce) AS valid")
        Mono<Boolean> contextCurrent(@Param("nonce") UUID nonce);
        @Query("SELECT insurance_quote.validate_complete(:nonce) AS valid")
        Mono<Boolean> validateComplete(@Param("nonce") UUID nonce);
    }
}
