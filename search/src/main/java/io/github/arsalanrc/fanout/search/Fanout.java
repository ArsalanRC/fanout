package io.github.arsalanrc.fanout.search;

import io.github.arsalanrc.fanout.core.*;
import io.github.arsalanrc.fanout.integration.Connector;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Ask every supplier at once, under one budget, and answer with whatever came
 * back.
 *
 * <p>This is the whole repository in one class, and three decisions define it.
 *
 * <p><b>One deadline, shared.</b> Every connector is handed the same
 * {@link Deadline}, so the search costs what the caller asked for rather than
 * the sum of eight individual timeouts. A supplier reached late gets what is
 * left, not a fresh allowance.
 *
 * <p><b>A slow supplier never fails the search.</b> Missing the deadline is
 * recorded as an outcome beside the results, not raised. Returning six
 * suppliers' fares and saying two are missing is far more useful than an error,
 * and the caller can see the difference between a quiet route and a broken one.
 *
 * <p><b>Virtual threads, not a pool.</b> Every connector spends its time
 * waiting on a socket, which is exactly what a platform thread is bad at
 * holding. A virtual thread per supplier means the fan-out is as wide as the
 * supplier list without a pool size to tune, and there is no queue in which the
 * ninth supplier waits for the first.
 */
public final class Fanout {

    private final List<Connector> connectors;
    private final Breaker breaker;

    public Fanout(List<Connector> connectors) {
        this(connectors, new Breaker(3, Duration.ofSeconds(30)));
    }

    public Fanout(List<Connector> connectors, Breaker breaker) {
        this.connectors = List.copyOf(connectors);
        this.breaker = Objects.requireNonNull(breaker, "breaker");
    }

    public SearchResult search(Query query, Basket basket, Duration budget) {
        return search(query, basket, Deadline.in(budget), Instant.now());
    }

    /**
     * @param now the moment used to drop lapsed fares, taken once so that every
     *            fare in one search is judged against the same instant
     */
    public SearchResult search(Query query, Basket basket, Deadline deadline, Instant now) {
        List<SupplierOutcome> outcomes = new ArrayList<>();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        try {
            Map<String, Future<SupplierOutcome>> running = new LinkedHashMap<>();

            for (Connector connector : connectors) {
                if (!breaker.allows(connector.id())) {
                    outcomes.add(SupplierOutcome.skipped(connector.id(),
                            "breaker open after repeated failures"));
                    continue;
                }
                running.put(connector.id(), pool.submit(() -> call(connector, query, deadline)));
            }

            for (Map.Entry<String, Future<SupplierOutcome>> entry : running.entrySet()) {
                outcomes.add(collect(entry.getKey(), entry.getValue(), deadline));
            }
        } finally {
            /*
             * What actually bounds this search is the deadline on `get` in
             * `collect`, plus the `cancel(true)` that follows it. By the time
             * control reaches here every task has been asked to stop.
             *
             * `shutdownNow` rather than `close` is belt and braces on top of
             * that, and the honest note is that the tests do not distinguish
             * the two: a connector written to ignore interruption is caught by
             * the deadline either way. It is kept because `close` waits for
             * termination without a bound, and a future change that stopped
             * cancelling would turn that into a hang rather than a slow search.
             */
            pool.shutdownNow();
        }

        return new SearchResult(query, merge(outcomes, basket, now), outcomes);
    }

    /** Runs on the virtual thread. Times itself and never throws. */
    private SupplierOutcome call(Connector connector, Query query, Deadline deadline) {
        long started = System.nanoTime();

        try {
            List<Fare> fares = connector.search(query, deadline);
            breaker.succeeded(connector.id());
            return SupplierOutcome.answered(connector.id(), since(started), fares);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Interrupted means the deadline took it, which is lateness rather
            // than breakage. Counting it as a failure would trip the breaker on
            // every slow day and drop a supplier that works.
            return SupplierOutcome.timedOut(connector.id(), since(started));
        } catch (Exception e) {
            breaker.failed(connector.id());
            return SupplierOutcome.failed(connector.id(), since(started), describe(e));
        }
    }

    private SupplierOutcome collect(String supplier, Future<SupplierOutcome> future, Deadline deadline) {
        try {
            return future.get(deadline.remainingMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return SupplierOutcome.timedOut(supplier, Duration.ZERO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SupplierOutcome.timedOut(supplier, Duration.ZERO);
        } catch (ExecutionException e) {
            breaker.failed(supplier);
            return SupplierOutcome.failed(supplier, Duration.ZERO, describe(e.getCause()));
        }
    }

    /**
     * Collapse the same journey sold by several suppliers into one row.
     *
     * <p>Lapsed fares are dropped here rather than shown, because a quote that
     * has expired is worse than no quote: it sorts to the top, gets clicked and
     * fails where somebody was about to pay. A fare that cannot cover the
     * basket goes too, since it cannot be priced honestly for this traveller.
     */
    private static List<PricedItinerary> merge(List<SupplierOutcome> outcomes, Basket basket, Instant now) {
        Map<String, List<Fare>> byJourney = new LinkedHashMap<>();

        for (SupplierOutcome outcome : outcomes) {
            for (Fare fare : outcome.fares()) {
                if (fare.expiredAt(now) || !fare.satisfies(basket)) continue;
                byJourney.computeIfAbsent(fare.itinerary().key(), key -> new ArrayList<>()).add(fare);
            }
        }

        return byJourney.values().stream()
                .map(offers -> PricedItinerary.of(offers.getFirst().itinerary(), offers, basket))
                .sorted(Comparator.comparing(PricedItinerary::bestPrice))
                .toList();
    }

    private static Duration since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }
}
