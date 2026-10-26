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


package io.fireflysoftware.core.insurance.underwriting.interfaces.quotes;

import java.util.Objects;
import java.util.UUID;

public record QuoteOfferScope(UUID tenantId, UUID submissionId, UUID partyId, UUID productId) {
    public QuoteOfferScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(submissionId, "submissionId");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(productId, "productId");
    }
}
