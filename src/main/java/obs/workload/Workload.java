package obs.workload;

import obs.core.Book;
import obs.core.OrderBook;
import obs.core.OrderResult;
import obs.core.Prices;
import obs.core.Side;
import obs.core.TradeListener;
import obs.ref.RefOrderBook;

/**
 * The standard setup for benchmarks, latency runs and allocation tests, so every measurement
 * uses the same book shape.
 *
 * The ladder follows the guide: a $150 stock with a $0.01 tick and a range of $100.00 to $299.99.
 * The prefilled book has 50 levels a side around a $150.00 mid, 10 orders of 100 shares per level.
 */
public final class Workload {

    public static final long SEED = 20260914L;

    public static final long MID = Prices.parse("150.00");
    public static final long BASE_PRICE = Prices.parse("100.00");
    public static final int LEVELS = 20_000;
    public static final int POOL_CAPACITY = 1 << 20;

    public static final int PREFILL_LEVELS = 50;
    public static final int PREFILL_ORDERS_PER_LEVEL = 10;
    public static final int PREFILL_QTY = 100;
    public static final int PREFILL_ORDERS = 2 * PREFILL_LEVELS * PREFILL_ORDERS_PER_LEVEL;

    /** Prefilled orders use ids 1..PREFILL_ORDERS; generated flow starts here. */
    public static final long FIRST_FLOW_ID = 1_000_000L;

    private Workload() {}

    public static OrderBook newFastBook(TradeListener listener) {
        return newFastBook(listener, POOL_CAPACITY);
    }

    public static OrderBook newFastBook(TradeListener listener, int poolCapacity) {
        return new OrderBook(BASE_PRICE, Prices.CENT, LEVELS, poolCapacity, listener);
    }

    public static RefOrderBook newRefBook(TradeListener listener) {
        return new RefOrderBook(Prices.CENT, listener);
    }

    /** "fast" or "ref". */
    public static Book newBook(String impl, TradeListener listener) {
        return newBook(impl, listener, POOL_CAPACITY);
    }

    /** "fast" or "ref"; poolCapacity only applies to the fast book, which has a fixed pool. */
    public static Book newBook(String impl, TradeListener listener, int poolCapacity) {
        return switch (impl) {
            case "fast" -> newFastBook(listener, poolCapacity);
            case "ref" -> newRefBook(listener);
            default -> throw new IllegalArgumentException("unknown book implementation: " + impl);
        };
    }

    /** Rests the prefilled orders: for each level outward from the mid, a bid then an ask, repeated per order. */
    public static void prefill(Book book) {
        for (int p = 0; p < PREFILL_ORDERS; p++) {
            int result = book.addRestingOrder(prefillId(p), prefillSide(p), prefillPrice(p), PREFILL_QTY);
            if (result != OrderResult.ACCEPTED) {
                throw new IllegalStateException("prefill order " + p + ": " + OrderResult.name(result));
            }
        }
    }

    public static long prefillId(int p) {
        return p + 1;
    }

    public static byte prefillSide(int p) {
        return (p & 1) == 0 ? Side.BUY : Side.SELL;
    }

    public static long prefillPrice(int p) {
        int level = 1 + (p / 2) / PREFILL_ORDERS_PER_LEVEL;
        return prefillSide(p) == Side.BUY ? MID - level * Prices.CENT : MID + level * Prices.CENT;
    }
}
