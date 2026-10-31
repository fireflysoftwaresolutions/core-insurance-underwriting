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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.InsuredObjectType;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.LineOfBusiness;
import io.fireflysoftware.core.insurance.underwriting.interfaces.enums.SubmissionStatus;

/** Supported source data only; freshness, locked reads and current authority belong to the owner. */
public record QuoteRiskSnapshot(QuoteOfferScope scope, SubmissionStatus submissionStatus,
        LineOfBusiness lineOfBusiness, String currency, UUID riskId, UUID riskSubmissionId,
        InsuredObjectType insuredObjectType, UUID riskPartyId, UUID valueId, UUID valueSubmissionId,
        int lineNumber, BigDecimal declaredValue, String valueCurrency) {
    public record Submission(QuoteOfferScope scope, SubmissionStatus status, LineOfBusiness lineOfBusiness, String currency) {}
    public record Risk(UUID id, UUID submissionId, InsuredObjectType type, UUID partyId) {}
    public record Value(UUID id, UUID submissionId, int lineNumber, BigDecimal declaredValue, String currency) {}

    public QuoteRiskSnapshot {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(riskId, "riskId");
        Objects.requireNonNull(riskSubmissionId, "riskSubmissionId");
        Objects.requireNonNull(riskPartyId, "riskPartyId");
        Objects.requireNonNull(valueId, "valueId");
        Objects.requireNonNull(valueSubmissionId, "valueSubmissionId");
        if ((submissionStatus != SubmissionStatus.RECEIVED && submissionStatus != SubmissionStatus.QUOTED)
                || lineOfBusiness != LineOfBusiness.PROPERTY || insuredObjectType != InsuredObjectType.PROPERTY
                || !"USD".equals(currency) || !"USD".equals(valueCurrency)
                || !scope.submissionId().equals(riskSubmissionId) || !scope.submissionId().equals(valueSubmissionId)
                || !scope.partyId().equals(riskPartyId)) {
            throw new IllegalArgumentException("Unsupported or inconsistent quote source shape");
        }
        declaredValue = QuoteValues.money(declaredValue, QuoteValues.currency(currency), true);
        // The actual legacy declared_value column is DECIMAL(19,4), unlike the new terms amounts.
        if (declaredValue.setScale(4).precision() > 19) {
            throw new IllegalArgumentException("Declared value exceeds the source column precision");
        }
    }

    public static QuoteRiskSnapshot fromRows(Submission parent, List<Risk> risks, List<Value> values) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(risks, "risks");
        Objects.requireNonNull(values, "values");
        if (risks.size() != 1 || values.size() != 1) {
            throw new IllegalArgumentException("Exactly one supported risk and value row required");
        }
        var risk = Objects.requireNonNull(risks.getFirst(), "risk");
        var value = Objects.requireNonNull(values.getFirst(), "value");
        return new QuoteRiskSnapshot(parent.scope(), parent.status(), parent.lineOfBusiness(), parent.currency(),
                risk.id(), risk.submissionId(), risk.type(), risk.partyId(), value.id(), value.submissionId(),
                value.lineNumber(), value.declaredValue(), value.currency());
    }

    public String riskDigest() {
        return QuoteCanonicalCodec.digest(QuoteCanonicalCodec.risk(this));
    }
}
