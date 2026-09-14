package obs.feed;

import obs.core.Prices;

/**
 * Parameters for {@link SyntheticItchGenerator}.
 *
 * Order flow comes from a four-stream Hawkes process: passive limit orders, marketable orders,
 * deletes, and modifications (half partial cancels, half replaces). {@code excitation[i][j]} is
 * how much one event of stream j raises stream i's rate; see {@link HawkesProcess}.
 *
 * Prices follow a hidden fair value that moves as a random walk. Passive orders don't rest on the
 * wrong side of it, and a share of marketable orders are "informed": they buy when the fair value
 * is above the mid and sell when it is below. That is what moves the market, and what makes a
 * slow quote get picked off.
 *
 * Times are nanoseconds; prices use the {@link Prices} convention.
 *
 * @param fairValueVolatility standard deviation of the fair value's move over one second, in price units
 * @param informedProbability chance that a marketable order trades towards the fair value rather than on a coin flip
 */
public record FlowConfig(
        long seed,
        String ticker,
        int stockLocate,
        long openNanos,
        long durationNanos,
        long startPrice,
        long tickSize,
        long minPrice,
        long maxPrice,
        double[] baseRates,
        double[][] excitation,
        double[] decay,
        int minLiveOrders,
        int maxLiveOrders,
        double fairValueVolatility,
        double informedProbability) {

    public static final int PASSIVE = 0;
    public static final int MARKETABLE = 1;
    public static final int DELETE = 2;
    public static final int MODIFY = 3;
    public static final int STREAMS = 4;

    public static final long NANOS_PER_SECOND = 1_000_000_000L;
    /** 09:30:00 in nanoseconds since midnight. */
    public static final long MARKET_OPEN = (9 * 3600 + 30 * 60) * NANOS_PER_SECOND;
    /** 09:30 to 16:00. */
    public static final long FULL_SESSION = (6 * 3600 + 30 * 60) * NANOS_PER_SECOND;

    public FlowConfig {
        if (baseRates.length != STREAMS || excitation.length != STREAMS || decay.length != STREAMS) {
            throw new IllegalArgumentException("need rates, excitation and decay for all " + STREAMS + " streams");
        }
        if (tickSize <= 0 || minPrice <= 0 || minPrice > startPrice || startPrice > maxPrice
                || startPrice % tickSize != 0 || minPrice % tickSize != 0 || maxPrice % tickSize != 0) {
            throw new IllegalArgumentException("need 0 < minPrice <= startPrice <= maxPrice, all on the tick");
        }
        if (durationNanos <= 0 || openNanos < 3 * NANOS_PER_SECOND || openNanos + durationNanos + 2 * NANOS_PER_SECOND >= 24 * 3600 * NANOS_PER_SECOND) {
            throw new IllegalArgumentException("the session, with its opening and closing messages, must fit in one day");
        }
        if (minLiveOrders < 0 || maxLiveOrders <= minLiveOrders) {
            throw new IllegalArgumentException("need 0 <= minLiveOrders < maxLiveOrders");
        }
        if (!(fairValueVolatility >= 0) || !(informedProbability >= 0 && informedProbability <= 1)) {
            throw new IllegalArgumentException("need fairValueVolatility >= 0 and informedProbability in [0, 1]");
        }
    }

    /**
     * A $150 stock with a $0.01 tick quoted between $100.00 and $299.99 (the same ladder as the
     * benchmarks), about 90 order events a second in bursts. Decays are 50 per second, so a burst
     * fades over tens of milliseconds. The fair value moves $0.015 per √second, about $2.25 (1.5%)
     * over a full session, and half of marketable orders are informed.
     */
    public static FlowConfig defaults(long seed) {
        return new FlowConfig(
                seed, "SYNTH", 1,
                MARKET_OPEN, FULL_SESSION,
                Prices.parse("150.00"), Prices.CENT, Prices.parse("100.00"), Prices.parse("299.99"),
                new double[] {45, 1.2, 35, 8},
                new double[][] {
                        //  passive  marketable  delete  modify     <- the event that happened
                        {15, 15, 5, 5},      // passive rate jumps
                        {0.2, 4, 0.2, 0.2},  // marketable rate jumps
                        {10, 15, 12, 5},     // delete rate jumps
                        {3, 5, 2, 8}},       // modify rate jumps
                new double[] {50, 50, 50, 50},
                200, 5_000,
                150, 0.5);
    }

    public FlowConfig withSeed(long newSeed) {
        return new FlowConfig(newSeed, ticker, stockLocate, openNanos, durationNanos, startPrice, tickSize, minPrice,
                maxPrice, baseRates, excitation, decay, minLiveOrders, maxLiveOrders, fairValueVolatility, informedProbability);
    }

    public FlowConfig withDuration(long newDurationNanos) {
        return new FlowConfig(seed, ticker, stockLocate, openNanos, newDurationNanos, startPrice, tickSize, minPrice,
                maxPrice, baseRates, excitation, decay, minLiveOrders, maxLiveOrders, fairValueVolatility, informedProbability);
    }

    /** The same flow with a different fair-value volatility and share of informed marketable orders. */
    public FlowConfig withFairValue(double volatility, double informed) {
        return new FlowConfig(seed, ticker, stockLocate, openNanos, durationNanos, startPrice, tickSize, minPrice,
                maxPrice, baseRates, excitation, decay, minLiveOrders, maxLiveOrders, volatility, informed);
    }

    /** Number of price levels between minPrice and maxPrice inclusive: the ladder a fast book needs. */
    public int ladderLevels() {
        return Math.toIntExact((maxPrice - minPrice) / tickSize + 1);
    }
}
