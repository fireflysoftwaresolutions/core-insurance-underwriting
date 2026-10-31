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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Native admission evidence; intersecting a later observation never renews an earlier one. */
public final class QuoteNativeWindow {
    private final Instant recordedAt;
    private final Instant until;
    private final List<Bound> bounds;

    private QuoteNativeWindow(Instant recordedAt, Instant until, List<Bound> bounds) {
        this.recordedAt = recordedAt;
        this.until = until;
        this.bounds = List.copyOf(bounds);
    }

    public static QuoteNativeWindow from(Instant recordedAt, long databaseNowMillis,
            long effectiveUntilMillis, long beforeAdmissionNanos) {
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (recordedAt.getNano() % 1000 != 0 || recordedAt.toEpochMilli() != databaseNowMillis
                || effectiveUntilMillis <= databaseNowMillis
                || effectiveUntilMillis - databaseNowMillis > 5000) {
            throw new IllegalArgumentException("Invalid native quote clock witness");
        }
        // The database witness floors microseconds to milliseconds. Charge the entire lost millisecond.
        long budget = (effectiveUntilMillis - databaseNowMillis - 1) * 1_000_000L;
        if (budget <= 0) throw expired();
        return new QuoteNativeWindow(recordedAt, Instant.ofEpochMilli(effectiveUntilMillis),
                List.of(new Bound(beforeAdmissionNanos, budget)));
    }

    public QuoteNativeWindow intersect(QuoteNativeWindow other) {
        Objects.requireNonNull(other, "other");
        if (bounds.size() + other.bounds.size() > 16) throw new IllegalArgumentException("Too many quote observations");
        var combined = new ArrayList<>(bounds);
        combined.addAll(other.bounds);
        return new QuoteNativeWindow(recordedAt, until.isBefore(other.until) ? until : other.until, combined);
    }

    /** Adds a supplied external evidence cap; it does not turn that evidence into a native DB witness. */
    public QuoteNativeWindow intersectEvidence(Instant observedAt, Instant validUntil, long beforeObservationNanos) {
        Objects.requireNonNull(observedAt);Objects.requireNonNull(validUntil);
        Duration duration=Duration.between(observedAt,validUntil);
        if (observedAt.getNano()%1000!=0 || validUntil.getNano()%1000!=0 || duration.isZero() || duration.isNegative()
                || duration.compareTo(Duration.ofSeconds(30))>0 || bounds.size()>=16) throw new IllegalArgumentException("Invalid finite quote evidence times");
        long budget=duration.toNanos()-1_000_000L;
        if (budget<=0 || !validUntil.isAfter(recordedAt)) throw expired();
        var combined=new ArrayList<>(bounds);combined.add(new Bound(beforeObservationNanos,budget));
        return new QuoteNativeWindow(recordedAt,until.isBefore(validUntil)?until:validUntil,combined);
    }

    public Instant recordedAt() { return recordedAt; }
    public Instant until() { return until; }

    public void requireLive(Instant now, long monotonicNow) { remaining(now, monotonicNow); }

    public Duration remaining(Instant now, long monotonicNow) {
        Objects.requireNonNull(now, "now");
        if (!now.isBefore(until)) throw expired();
        long remaining = Long.MAX_VALUE;
        for (Bound bound : bounds) {
            long elapsed = monotonicNow - bound.start();
            if (elapsed < 0 || elapsed >= bound.budget()) throw expired();
            remaining = Math.min(remaining, bound.budget() - elapsed);
        }
        Duration absolute = Duration.between(now, until);
        Duration elapsed = Duration.ofNanos(remaining);
        return absolute.compareTo(elapsed) < 0 ? absolute : elapsed;
    }

    private static IllegalStateException expired() { return new IllegalStateException("QUOTE_RESPONSE_AUTHORITY_EXPIRED"); }
    private record Bound(long start, long budget) {}
    @Override public String toString() { return "QuoteNativeWindow"; }
}
