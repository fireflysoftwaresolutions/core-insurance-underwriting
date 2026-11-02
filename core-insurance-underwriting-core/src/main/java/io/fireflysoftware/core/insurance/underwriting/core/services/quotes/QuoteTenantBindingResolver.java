/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.core.insurance.underwriting.core.services.quotes;

import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteAuthorityBudget;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.fireflyframework.security.api.domain.SecurityPrincipal;
import reactor.core.publisher.Mono;

/** Server-observed namespace mapping; a value constructor grants no actor or write authority. */
public interface QuoteTenantBindingResolver {
    record Observation(String issuer,String platformTenant,UUID resourceTenantId,UUID bindingId,long version,String definitionDigest,
            long controlVersion,long tenantGeneration,UUID statusId,long statusGeneration,Instant observedAt,Instant validUntil,Instant businessUntil) {
        public Observation {
            text(issuer,500);text(platformTenant,1024);id(resourceTenantId);id(bindingId);id(statusId);
            Objects.requireNonNull(observedAt);Objects.requireNonNull(validUntil);Objects.requireNonNull(businessUntil);
            if(version<1 || version==Long.MAX_VALUE || controlVersion<1 || controlVersion==Long.MAX_VALUE
                    || tenantGeneration<0 || tenantGeneration==Long.MAX_VALUE || statusGeneration<0 || statusGeneration==Long.MAX_VALUE
                    || definitionDigest==null || !definitionDigest.matches("[0-9a-f]{64}") || !validUntil.isAfter(observedAt)
                    || Duration.between(observedAt,validUntil).compareTo(Duration.ofSeconds(5))>0 || validUntil.isAfter(businessUntil))throw invalid();
        }
        public boolean sameDefinition(Observation other) {
            return other!=null && issuer.equals(other.issuer) && platformTenant.equals(other.platformTenant)
                    && resourceTenantId.equals(other.resourceTenantId) && bindingId.equals(other.bindingId) && version==other.version
                    && definitionDigest.equals(other.definitionDigest) && controlVersion==other.controlVersion && tenantGeneration==other.tenantGeneration
                    && statusId.equals(other.statusId) && statusGeneration==other.statusGeneration && businessUntil.equals(other.businessUntil);
        }
        @Override public String toString() {return "QuoteTenantBindingObservation[redacted]";}
    }
    Mono<Observation> resolve(SecurityPrincipal expected,UUID expectedResourceTenant,QuoteAuthorityBudget originalBudget,
            Instant originalNotAfter,Supplier<Duration> remaining);

    private static void id(UUID value) {if(value==null || value.equals(new UUID(0,0)))throw invalid();}
    private static void text(String value,int max) {
        if(value==null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length>max
                || value.codePoints().anyMatch(c->Character.isISOControl(c) || c>=0xD800 && c<=0xDFFF))throw invalid();
    }
    private static IllegalArgumentException invalid() {return new IllegalArgumentException("Exact observed quote tenant binding required");}
}
