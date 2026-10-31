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

import io.fireflysoftware.core.insurance.underwriting.models.quotes.QuoteNativeWindow;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuoteNativeWindowTest {
    private final Instant db=Instant.parse("2026-10-26T12:00:00.000999Z");
    private final long ms=db.toEpochMilli();
    @Test void preservesActualAbsoluteAndPreAdmissionAnchor() {
        var w=QuoteNativeWindow.from(db,ms,ms+1000,20);
        assertEquals(db,w.recordedAt());assertEquals(Instant.ofEpochMilli(ms+1000),w.until());
        w.requireLive(db,20+998_999_999);assertThrows(IllegalStateException.class,()->w.requireLive(db,20+999_000_000));
    }
    @Test void chargesOneMillisecondQuantization() {
        assertThrows(IllegalStateException.class,()->QuoteNativeWindow.from(db,ms,ms+1,0));
        var w=QuoteNativeWindow.from(db,ms,ms+2,0);
        w.requireLive(db,999_999);assertThrows(IllegalStateException.class,()->w.requireLive(db,1_000_000));
    }
    @Test void absoluteExpiryRefusesEvenWithNoElapsedTime() {
        var w=QuoteNativeWindow.from(db,ms,ms+1000,0);
        assertThrows(IllegalStateException.class,()->w.requireLive(w.until(),0));
    }
    @Test void backwardsWallClockCannotExtendElapsedCap() {
        var w=QuoteNativeWindow.from(db,ms,ms+1000,0);
        assertThrows(IllegalStateException.class,()->w.requireLive(db.minusSeconds(10),999_000_000));
    }
    @Test void exactClockWitnessRequired() {
        assertThrows(IllegalArgumentException.class,()->QuoteNativeWindow.from(db,ms+1,ms+1000,0));
        assertThrows(IllegalArgumentException.class,()->QuoteNativeWindow.from(db.plusNanos(1),ms,ms+1000,0));
    }
    @Test void finiteDatabaseLeaseRequired() {
        assertThrows(IllegalArgumentException.class,()->QuoteNativeWindow.from(db,ms,ms+5001,0));
        assertThrows(IllegalArgumentException.class,()->QuoteNativeWindow.from(db,ms,ms,0));
    }
    @Test void intersectionNeverRenewsEitherWindow() {
        var a=QuoteNativeWindow.from(db,ms,ms+1000,0);
        var b=QuoteNativeWindow.from(db.plusMillis(200),ms+200,ms+3000,100_000_000);
        var c=a.intersect(b);assertEquals(a.until(),c.until());
        c.requireLive(db,998_999_999);assertThrows(IllegalStateException.class,()->c.requireLive(db,999_000_000));
    }
    @Test void laterShorterWindowRestrictsOriginal() {
        var a=QuoteNativeWindow.from(db,ms,ms+4000,0);
        var b=QuoteNativeWindow.from(db.plusMillis(100),ms+100,ms+1000,100_000_000);
        var c=a.intersect(b);assertEquals(b.until(),c.until());
        assertThrows(IllegalStateException.class,()->c.requireLive(db,999_000_000));
    }
    @Test void signedNanoWrapIsSupportedWithinFiniteBudget() {
        long start=Long.MAX_VALUE-5;var w=QuoteNativeWindow.from(db,ms,ms+1000,start);
        w.requireLive(db,Long.MIN_VALUE+5);
    }
    @Test void negativeElapsedRefuses() {
        var w=QuoteNativeWindow.from(db,ms,ms+1000,20);
        assertThrows(IllegalStateException.class,()->w.requireLive(db,19));
    }
    @Test void guardRemainsUsableAfterCurrentCheckWithoutReset() {
        var ticks=new AtomicLong();var w=QuoteNativeWindow.from(db,ms,ms+1000,ticks.get());
        ticks.set(600_000_000);w.requireLive(db,ticks.get());
        ticks.set(999_000_000);assertThrows(IllegalStateException.class,()->w.requireLive(db,ticks.get()));
    }
    @Test void nullWitnessRefuses() {assertThrows(NullPointerException.class,()->QuoteNativeWindow.from(null,ms,ms+1000,0));}
    @Test void externalEvidenceNarrowsWithoutRelabelingNativeWitness() {
        var nativeWindow=QuoteNativeWindow.from(db,ms,ms+5000,0);
        var bounded=nativeWindow.intersectEvidence(db,db.plusMillis(500),0);
        assertEquals(db,nativeWindow.recordedAt());assertEquals(db,bounded.recordedAt());assertEquals(db.plusMillis(500),bounded.until());
        bounded.requireLive(db,498_999_999);assertThrows(IllegalStateException.class,()->bounded.requireLive(db,499_000_000));
    }
    @Test void laterEvidenceCannotResetOriginalElapsedOrAbsoluteCap() {
        var original=QuoteNativeWindow.from(db,ms,ms+1000,0);
        var bounded=original.intersectEvidence(db.plusMillis(200),db.plusSeconds(20),100_000_000);
        assertEquals(original.until(),bounded.until());assertThrows(IllegalStateException.class,()->bounded.requireLive(db,999_000_000));
    }
    @Test void externalEvidenceRequiresFiniteActualObservationTimes() {
        var original=QuoteNativeWindow.from(db,ms,ms+5000,0);
        assertThrows(IllegalArgumentException.class,()->original.intersectEvidence(db,db,0));
        assertThrows(IllegalArgumentException.class,()->original.intersectEvidence(db,db.plusSeconds(31),0));
        assertThrows(IllegalArgumentException.class,()->original.intersectEvidence(db.plusNanos(1),db.plusSeconds(1),0));
    }
}
