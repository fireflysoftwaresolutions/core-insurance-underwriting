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

import io.fireflysoftware.core.insurance.underwriting.core.services.quotes.QuoteConfigurationPort;
import io.fireflysoftware.core.insurance.underwriting.interfaces.quotes.QuoteOfferScope;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Data-shape controls only; the current authenticated insurance configuration producer is missing. */
class QuoteConfigurationObservationTest {
    private final UUID tenant=new UUID(0,1),product=new UUID(0,2),revision=new UUID(0,3);
    private final Instant now=Instant.parse("2026-10-26T12:00:00Z");
    private QuoteConfigurationPort.Observation value(Instant observed,Instant until,BigDecimal deductible,Duration ttl) {
        return new QuoteConfigurationPort.Observation(tenant,product,revision,"a".repeat(64),"INSURANCE_SANDBOX_PROPERTY_QUOTE_1","USD",deductible,ttl,
                "firefly-sandbox-property-1","illustrative-property-1",observed,until);
    }
    @Test void typedFactsBindOnlyTheirActualProductTenantAndRevision() {
        var value=value(now,now.plusSeconds(20),BigDecimal.ZERO,Duration.ofHours(1));
        var scope=new QuoteOfferScope(tenant,new UUID(0,4),new UUID(0,5),product);
        value.requireMatch(scope,revision,"a".repeat(64));
        assertThrows(IllegalArgumentException.class,()->value.requireMatch(scope,new UUID(0,9),"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->value.requireMatch(new QuoteOfferScope(tenant,scope.submissionId(),scope.partyId(),new UUID(0,9)),revision,"a".repeat(64)));
        assertEquals("QuoteConfigurationObservation",value.toString());
    }
    @Test void noDefaultOrUnboundedObservationHorizon() {
        assertThrows(IllegalArgumentException.class,()->value(now,now,BigDecimal.ZERO,Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,()->value(now,now.plusSeconds(31),BigDecimal.ZERO,Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,()->value(now.plusNanos(1),now.plusSeconds(20),BigDecimal.ZERO,Duration.ofHours(1)));
    }
    @Test void configuredMoneyAndLifetimeFitTheExistingExplicitSandbox() {
        assertThrows(IllegalArgumentException.class,()->value(now,now.plusSeconds(20),new BigDecimal("-0.01"),Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,()->value(now,now.plusSeconds(20),new BigDecimal("0.001"),Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class,()->value(now,now.plusSeconds(20),BigDecimal.ZERO,Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class,()->value(now,now.plusSeconds(20),BigDecimal.ZERO,Duration.ofNanos(1)));
    }
    @Test void refreshedObservationCanChangeTimeButNotImmutableDefinitionFacts() {
        var original=value(now,now.plusSeconds(20),new BigDecimal("10.00"),Duration.ofHours(1));
        var newer=value(now.plusSeconds(1),now.plusSeconds(21),new BigDecimal("10.00"),Duration.ofHours(1));
        assertTrue(original.sameDefinition(newer));assertNotEquals(original.factsDigest(),newer.factsDigest());
        assertFalse(original.sameDefinition(value(now,now.plusSeconds(20),new BigDecimal("11.00"),Duration.ofHours(1))));
    }
}
