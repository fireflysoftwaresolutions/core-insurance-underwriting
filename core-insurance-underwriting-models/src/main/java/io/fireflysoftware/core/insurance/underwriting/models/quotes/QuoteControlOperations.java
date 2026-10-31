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

import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Mapped compare-and-set for the original scoped acceptance transition. */
public interface QuoteControlOperations {
    Mono<Integer> accept(UUID tenant, UUID party, UUID submission, UUID product, UUID offer,
            String issuer, String subject, UUID acceptanceId, OffsetDateTime recordedAt);
}
