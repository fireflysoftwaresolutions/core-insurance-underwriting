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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteNativeWindow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;

/** Original backend-observation time control, never authentication or a signed capability. */
public final class QuoteAuthorityBudget {
    private final Instant until;
    private final long beforeNanos;
    private final long budgetNanos;
    private final Clock clock;
    private final LongSupplier nanos;
    private QuoteAuthorityBudget(Instant until,long beforeNanos,long budgetNanos,Clock clock,LongSupplier nanos) {
        this.until=until;this.beforeNanos=beforeNanos;this.budgetNanos=budgetNanos;this.clock=clock;this.nanos=nanos;
    }
    public static QuoteAuthorityBudget observe(Instant observedAt,Instant until,long beforeNanos,Clock clock,LongSupplier nanos) {
        Objects.requireNonNull(observedAt);Objects.requireNonNull(until);Objects.requireNonNull(clock);Objects.requireNonNull(nanos);
        var duration=Duration.between(observedAt,until);
        if (duration.isZero() || duration.isNegative() || duration.compareTo(Duration.ofSeconds(5))>0) throw new IllegalArgumentException("Finite original quote observation required");
        return new QuoteAuthorityBudget(until,beforeNanos,duration.toNanos(),clock,nanos);
    }
    public Duration remaining(Instant additionalUntil) {
        Instant cap=until.isBefore(additionalUntil)?until:additionalUntil;Instant now=clock.instant();long elapsed=nanos.getAsLong()-beforeNanos;
        if (!now.isBefore(cap) || elapsed<0 || elapsed>=budgetNanos) throw new QuotePersistenceImpl.QuoteTransactionUncertain(false);
        var absolute=Duration.between(now,cap);var monotonic=Duration.ofNanos(budgetNanos-elapsed);return absolute.compareTo(monotonic)<0?absolute:monotonic;
    }
    Clock clock() {return clock;}
    LongSupplier nanos() {return nanos;}
    R2dbcOperationBudget boundedSnapshot(Instant additionalUntil, QuoteNativeWindow nativeWindow) {
        try {
            long before = nanos.getAsLong();
            Instant now = clock.instant();
            Duration remaining = remaining(additionalUntil);
            if (nativeWindow != null) {
                Duration nativeRemaining = nativeWindow.remaining(clock.instant(), nanos.getAsLong());
                if (nativeRemaining.compareTo(remaining) < 0) remaining = nativeRemaining;
            }
            // Charge snapshot time to the existing limits; this never renews the original observation.
            Instant expiresAt = now.plus(remaining);
            if (until.isBefore(expiresAt)) expiresAt = until;
            if (additionalUntil.isBefore(expiresAt)) expiresAt = additionalUntil;
            if (nativeWindow != null && nativeWindow.until().isBefore(expiresAt)) expiresAt = nativeWindow.until();
            return R2dbcOperationBudget.observed(now, expiresAt, before, Duration.ofSeconds(5), clock, nanos);
        } catch (RuntimeException expired) {
            throw new QuotePersistenceImpl.QuoteTransactionUncertain(false);
        }
    }
    @Override public String toString() {return "QuoteAuthorityBudget";}
}
