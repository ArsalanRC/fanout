package io.github.arsalanrc.fanout.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The clock is driven by hand in every case here.
 *
 * <p>A suite that proved timeout behaviour by sleeping would take a minute to
 * run and would be flaky on a loaded machine, which are the two things that
 * make people stop running a suite.
 */
class DeadlineTest {

    /** A clock that only moves when a test moves it. */
    private static final class Hand implements java.util.function.LongSupplier {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        void advance(Duration by) {
            nanos.addAndGet(by.toNanos());
        }
    }

    @Nested
    @DisplayName("the budget")
    class Budget {

        @Test
        void starts_with_the_whole_budget_left() {
            Hand clock = new Hand();
            Deadline deadline = Deadline.in(Duration.ofSeconds(20), clock);

            assertEquals(Duration.ofSeconds(20), deadline.remaining());
            assertFalse(deadline.expired());
        }

        @Test
        void shrinks_as_time_passes() {
            Hand clock = new Hand();
            Deadline deadline = Deadline.in(Duration.ofSeconds(20), clock);

            clock.advance(Duration.ofSeconds(15));

            assertEquals(Duration.ofSeconds(5), deadline.remaining());
        }

        @Test
        void never_reports_a_negative_remainder() {
            Hand clock = new Hand();
            Deadline deadline = Deadline.in(Duration.ofSeconds(1), clock);

            clock.advance(Duration.ofSeconds(30));

            // A negative remainder passed to a timeout argument is the bug this
            // prevents: many JDK APIs read a non-positive timeout as "forever".
            assertEquals(Duration.ZERO, deadline.remaining());
            assertTrue(deadline.expired());
        }

        @Test
        void refuses_a_negative_budget() {
            assertThrows(IllegalArgumentException.class,
                    () -> Deadline.in(Duration.ofSeconds(-1), new Hand()));
        }
    }

    @Nested
    @DisplayName("a child deadline")
    class Child {

        @Test
        void is_shortened_by_its_own_cap() {
            Hand clock = new Hand();
            Deadline search = Deadline.in(Duration.ofSeconds(20), clock);

            Deadline supplier = search.within(Duration.ofSeconds(3));

            assertEquals(Duration.ofSeconds(3), supplier.remaining());
        }

        @Test
        void can_never_outlive_its_parent() {
            Hand clock = new Hand();
            Deadline search = Deadline.in(Duration.ofSeconds(20), clock);
            clock.advance(Duration.ofSeconds(19));

            // The supplier wants three seconds. There is one left in the whole
            // search, and this is the line that stops eight suppliers each
            // taking three and the search running for twenty-four.
            Deadline supplier = search.within(Duration.ofSeconds(3));

            assertEquals(Duration.ofSeconds(1), supplier.remaining());
        }

        @Test
        void is_already_expired_once_the_parent_is() {
            Hand clock = new Hand();
            Deadline search = Deadline.in(Duration.ofSeconds(20), clock);
            clock.advance(Duration.ofSeconds(20));

            assertTrue(search.within(Duration.ofSeconds(3)).expired());
        }
    }

    @Nested
    @DisplayName("milliseconds, for the APIs that want a number")
    class Millis {

        @Test
        void rounds_a_sliver_up_rather_than_down() {
            Hand clock = new Hand();
            Deadline deadline = Deadline.in(Duration.ofNanos(400_000), clock);

            // 0.4ms. Rounding down gives 0, and 0 means "no timeout" in enough
            // JDK and driver APIs that it would occasionally hang the search
            // for good. One millisecond is wrong by half a millisecond; zero is
            // wrong by forever.
            assertEquals(1, deadline.remainingMillis());
        }

        @Test
        void is_zero_only_when_the_budget_is_truly_gone() {
            Hand clock = new Hand();
            Deadline deadline = Deadline.in(Duration.ofMillis(50), clock);
            clock.advance(Duration.ofMillis(50));

            assertEquals(0, deadline.remainingMillis());
        }
    }
}
