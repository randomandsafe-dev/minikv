package com.paul.minikv.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe test time shared with the server's event loops. */
public final class MutableClock extends Clock {
    private final AtomicLong millis;
    private final ZoneId zone;

    public MutableClock() {
        this(new AtomicLong(1_700_000_000_000L), ZoneOffset.UTC);
    }

    private MutableClock(AtomicLong millis, ZoneId zone) {
        this.millis = millis;
        this.zone = zone;
    }

    public void advanceMillis(long amount) {
        millis.addAndGet(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(millis, zone);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public long millis() {
        return millis.get();
    }
}
