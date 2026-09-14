package obs.sim;

import java.util.SplittableRandom;

/**
 * How long messages take between the exchange and the strategy (Part 6 of the guide).
 *
 * Three one-way channels: market data (exchange to strategy), order entry (strategy to exchange),
 * and order responses such as fills and cancel acknowledgements (exchange to strategy, with the
 * same base delay as order entry). Each message's delay is the channel's base delay plus random
 * jitter drawn from an exponential distribution, which is never negative and has a long right
 * tail like real network delay. A message never overtakes an earlier one on the same channel, as
 * on a TCP connection.
 *
 * Seeded, so a run is reproducible.
 */
public final class LatencyModel {

    private final long marketDataNanos;
    private final long orderEntryNanos;
    private final long jitterMeanNanos;
    private final SplittableRandom random;
    private final Channel marketData = new Channel();
    private final Channel orderEntry = new Channel();
    private final Channel responses = new Channel();

    public LatencyModel(long marketDataNanos, long orderEntryNanos, long jitterMeanNanos, long seed) {
        if (marketDataNanos < 0 || orderEntryNanos < 0 || jitterMeanNanos < 0) {
            throw new IllegalArgumentException("latencies must be non-negative");
        }
        this.marketDataNanos = marketDataNanos;
        this.orderEntryNanos = orderEntryNanos;
        this.jitterMeanNanos = jitterMeanNanos;
        this.random = new SplittableRandom(seed);
    }

    /** Zero latency everywhere: the unrealistic assumption a naive backtest makes. */
    public static LatencyModel zero() {
        return new LatencyModel(0, 0, 0, 0);
    }

    /** When market data the exchange published at {@code sentAt} reaches the strategy. */
    public long marketDataDelivery(long sentAt) {
        return marketData.deliver(sentAt + marketDataNanos + jitter());
    }

    /** When an order or cancel the strategy sent at {@code sentAt} reaches the matching engine. */
    public long orderArrival(long sentAt) {
        return orderEntry.deliver(sentAt + orderEntryNanos + jitter());
    }

    /** When a fill or acknowledgement the exchange sent at {@code sentAt} reaches the strategy. */
    public long responseDelivery(long sentAt) {
        return responses.deliver(sentAt + orderEntryNanos + jitter());
    }

    public long marketDataNanos() {
        return marketDataNanos;
    }

    public long orderEntryNanos() {
        return orderEntryNanos;
    }

    public long jitterMeanNanos() {
        return jitterMeanNanos;
    }

    private long jitter() {
        return jitterMeanNanos == 0 ? 0 : (long) (-Math.log(1.0 - random.nextDouble()) * jitterMeanNanos);
    }

    /** Keeps delivery times non-decreasing, so a later message never arrives first. */
    private static final class Channel {
        private long lastDelivery = Long.MIN_VALUE;

        long deliver(long earliest) {
            lastDelivery = Math.max(earliest, lastDelivery);
            return lastDelivery;
        }
    }
}
