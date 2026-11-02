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


package io.fireflysoftware.core.insurance.underwriting.core.services.quotes;

import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteCanonicalCodec;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import reactor.core.publisher.Mono;

/**
 * Mandatory authenticated current insurance-purpose producer boundary. No implementation or success
 * fallback is supplied. A generic ACTIVE product or banking publication cannot satisfy this purpose.
 */
public interface QuoteConfigurationPort {
    Mono<Observation> observe(QuoteOfferScope requestedScope,UUID expectedRevisionId,String expectedProductDigest);

    /** Supplied data, not owner authenticity, a signed permit or evidence that this purpose exists. */
    record Observation(UUID tenantId,UUID productId,UUID revisionId,String productDigest,String purpose,String currency,
            BigDecimal deductible,Duration offerLifetime,String providerVersion,String ruleSetVersion,
            Instant observedAt,Instant effectiveUntil) {
        public Observation {
            Objects.requireNonNull(tenantId);Objects.requireNonNull(productId);Objects.requireNonNull(revisionId);
            if (productDigest==null || !productDigest.matches("[0-9a-f]{64}") || !"INSURANCE_SANDBOX_PROPERTY_QUOTE_1".equals(purpose)
                    || !"USD".equals(currency) || !"firefly-sandbox-property-1".equals(providerVersion) || !"illustrative-property-1".equals(ruleSetVersion)) throw invalid();
            Objects.requireNonNull(deductible);Objects.requireNonNull(offerLifetime);Objects.requireNonNull(observedAt);Objects.requireNonNull(effectiveUntil);
            if (deductible.precision()>38 || deductible.scale() < -19 || deductible.scale()>19) throw invalid();
            try {deductible=deductible.setScale(2,RoundingMode.UNNECESSARY);} catch (ArithmeticException error) {throw invalid();}
            if (deductible.signum()<0 || deductible.compareTo(new BigDecimal("1000000.00"))>0
                    || offerLifetime.isZero() || offerLifetime.isNegative() || offerLifetime.compareTo(Duration.ofHours(24))>0 || offerLifetime.getNano()%1000!=0
                    || observedAt.getNano()%1000!=0 || effectiveUntil.getNano()%1000!=0 || !effectiveUntil.isAfter(observedAt)
                    || Duration.between(observedAt,effectiveUntil).compareTo(Duration.ofSeconds(30))>0) throw invalid();
        }
        public void requireMatch(QuoteOfferScope scope,UUID expectedRevision,String expectedDigest) {
            if (!tenantId.equals(scope.tenantId()) || !productId.equals(scope.productId()) || !revisionId.equals(expectedRevision) || !productDigest.equals(expectedDigest)) throw invalid();
        }
        public boolean sameDefinition(Observation other) {
            return other!=null && tenantId.equals(other.tenantId) && productId.equals(other.productId) && revisionId.equals(other.revisionId)
                    && productDigest.equals(other.productDigest) && purpose.equals(other.purpose) && currency.equals(other.currency)
                    && deductible.equals(other.deductible) && offerLifetime.equals(other.offerLifetime)
                    && providerVersion.equals(other.providerVersion) && ruleSetVersion.equals(other.ruleSetVersion);
        }
        public String factsDigest() {
            return QuoteCanonicalCodec.digest(QuoteCanonicalCodec.encodeFields(List.of("QUOTE_SUPPLIED_CONFIGURATION_FACTS_1",tenantId.toString(),productId.toString(),revisionId.toString(),productDigest,
                    purpose,currency,deductible.toPlainString(),offerLifetime.toString(),providerVersion,ruleSetVersion,observedAt.toString(),effectiveUntil.toString())));
        }
        @Override public String toString() {return "QuoteConfigurationObservation";}
        private static IllegalArgumentException invalid() {return new IllegalArgumentException("Unsupported supplied quote configuration facts");}
    }
}
