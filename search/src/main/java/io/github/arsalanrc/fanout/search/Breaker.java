package io.github.arsalanrc.fanout.search;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Stops calling a supplier that has stopped answering.
 *
 * <p>Without this, a dead supplier costs the deadline on **every** search. Eight
 * suppliers where one is down and the budget is twenty seconds means every
 * search waits for a corpse, and the other seven get whatever is left. The
 * failure is not that one supplier is missing, it is that all of them get
 * slower.
 *
 * <p>Three states, and the third is the one people leave out. Closed is normal.
 * Open means recent failures were enough to stop trying. **Half-open** lets
 * exactly one search through after the cooldown, so the supplier can prove it
 * is back. Without half-open a breaker either never reopens or reopens by
 * flooding a service that is still recovering.
 *
 * <p>The clock is injectable, so the tests never sleep through a cooldown.
 */
public final class Breaker {

    /** Consecutive failures before a supplier is dropped. */
    private final int threshold;

    /** How long to leave it alone before letting one call through. */
    private final Duration cooldown;

    private final LongSupplier clock;

    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> openedAt = new ConcurrentHashMap<>();

    public Breaker(int threshold, Duration cooldown) {
        this(threshold, cooldown, System::nanoTime);
    }

    public Breaker(int threshold, Duration cooldown, LongSupplier clock) {
        if (threshold < 1) throw new IllegalArgumentException("A breaker needs at least one failure to trip");
        this.threshold = threshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /** Whether this supplier should be called at all right now. */
    public boolean allows(String supplier) {
        Long opened = openedAt.get(supplier);
        if (opened == null) return true;

        long elapsed = clock.getAsLong() - opened;
        if (elapsed < cooldown.toNanos()) return false;

        // Half-open: the cooldown has passed, so one call goes through. It is
        // removed from the open set rather than reset to zero failures, so a
        // supplier that is still broken trips again on this single attempt
        // instead of needing another full threshold.
        openedAt.remove(supplier);
        return true;
    }

    public void succeeded(String supplier) {
        failures.remove(supplier);
        openedAt.remove(supplier);
    }

    public void failed(String supplier) {
        int count = failures.computeIfAbsent(supplier, key -> new AtomicInteger()).incrementAndGet();
        if (count >= threshold) openedAt.put(supplier, clock.getAsLong());
    }

    /** Whether this supplier is currently being skipped, without changing anything. */
    public boolean isOpen(String supplier) {
        Long opened = openedAt.get(supplier);
        return opened != null && clock.getAsLong() - opened < cooldown.toNanos();
    }
}
