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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Expected request data; scope and product expectations do not establish current owner authority. */
public record QuoteOfferRequestIntent(QuoteOfferScope scope, UUID actionId, LocalDate coverageStart,
        LocalDate coverageEnd, UUID expectedProductRevisionId, String expectedProductDigest) {
    public QuoteOfferRequestIntent {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(coverageStart, "coverageStart");
        Objects.requireNonNull(coverageEnd, "coverageEnd");
        Objects.requireNonNull(expectedProductRevisionId, "expectedProductRevisionId");
        expectedProductDigest = QuoteValues.digest(expectedProductDigest);
        if (coverageStart.getYear() < 1 || coverageEnd.getYear() > 9999
                || !coverageEnd.isAfter(coverageStart)
                || ChronoUnit.DAYS.between(coverageStart, coverageEnd) > 366) {
            throw new IllegalArgumentException("Supported sandbox coverage period must be one to 366 days");
        }
    }

    public String intentDigest() {
        return QuoteCanonicalCodec.digest(QuoteCanonicalCodec.offerRequest(this));
    }
}
