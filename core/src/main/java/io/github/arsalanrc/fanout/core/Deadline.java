package io.github.arsalanrc.fanout.core;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * How much time is left for the whole search.
 *
 * <p>This is the first argument the repository makes, and it is the one people
 * get wrong most often. A metasearch calls eight suppliers. Give each one a
 * three second timeout and every individual call looks correctly configured,
 * while the search itself can take twenty-four seconds. Nothing in the code
 * says so, because no single line is wrong.
 *
 * <p>A deadline fixes that by being the budget rather than the allowance.
 * It is set once, at the edge, and every call downstream gets whatever is left
 * of it. A supplier asked at nineteen seconds into a twenty second budget gets
 * one second, not three, and it is refused outright once nothing remains.
 *
 * <p>The clock is injectable, so the tests measure this without sleeping
 * through it. A suite that proves timeout behaviour by actually waiting is a
 * suite people stop running.
 */
public final class Deadline {

    private final long expiresAt;
    private final LongSupplier clock;

    private Deadline(long expiresAt, LongSupplier clock) {
        this.expiresAt = expiresAt;
        this.clock = clock;
    }

    /** A budget starting now, measured on the system clock. */
    public static Deadline in(Duration budget) {
        return in(budget, System::nanoTime);
    }

    /**
     * A budget starting now, measured on a clock you supply.
     *
     * @param clock nanosecond ticks, the same contract as {@link System#nanoTime()}.
     *              Only the differences matter, never the absolute value.
     */
    public static Deadline in(Duration budget, LongSupplier clock) {
        if (budget.isNegative()) throw new IllegalArgumentException("A budget cannot be negative: " + budget);
        return new Deadline(clock.getAsLong() + budget.toNanos(), clock);
    }

    /** How long is left, never negative. */
    public Duration remaining() {
        long left = expiresAt - clock.getAsLong();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    public boolean expired() {
        return remaining().isZero();
    }

    /**
     * A tighter deadline inside this one.
     *
     * <p>Takes whichever is sooner, so a child can never outlive its parent.
     * That is the whole guarantee: a connector may say it never wants to wait
     * more than two seconds, and that shortens its slice without ever extending
     * the search past what the caller asked for.
     */
    public Deadline within(Duration cap) {
        if (cap.isNegative()) throw new IllegalArgumentException("A cap cannot be negative: " + cap);

        long capped = clock.getAsLong() + cap.toNanos();
        return new Deadline(Math.min(expiresAt, capped), clock);
    }

    /**
     * Milliseconds left, for the JDK APIs that take a timeout as a number.
     *
     * <p>Rounds up, never down. A remaining budget of 400 microseconds is not
     * zero milliseconds: passing zero to a timeout means "wait forever" often
     * enough that rounding down here would occasionally hang the whole search.
     */
    public long remainingMillis() {
        long nanos = remaining().toNanos();
        return nanos == 0 ? 0 : Math.max(1, (nanos + 999_999) / 1_000_000);
    }

    @Override
    public String toString() {
        return "Deadline[" + remaining().toMillis() + "ms left]";
    }
}
