package obs.strategy;

/**
 * A trading strategy running inside the simulator. Every callback arrives after the simulated
 * latency: market data after the market-data delay, fills and acknowledgements after the
 * order-entry delay. Orders the strategy sends reach the exchange after the order-entry delay too,
 * so it always acts on a book that is already stale.
 *
 * Times are nanoseconds since midnight; prices use the {@link obs.core.Prices} convention; sides
 * are {@link obs.core.Side} values. Callbacks run on the simulation thread, one at a time.
 */
public interface Strategy {

    /** Called once before the session starts. Keep the context to send and cancel orders. */
    void init(StrategyContext context);

    /**
     * The top of the book changed. A side with no orders is {@link obs.core.Book#NO_BID} or
     * {@link obs.core.Book#NO_ASK}, with quantity 0.
     */
    default void onBookUpdate(long time, long bestBid, long bestAsk, long bidQty, long askQty) {}

    /** A trade happened in the market (not necessarily ours). */
    default void onTrade(long time, long price, int qty, byte aggressorSide) {}

    /** One of our orders filled, partly or completely. {@code remaining} is what is still open. */
    default void onOwnFill(long time, long clientId, byte side, long price, int qty, int remaining) {}

    /** The exchange processed our cancel. {@code cancelledQty} is 0 if the order had already filled. */
    default void onOwnCancelAck(long time, long clientId, int cancelledQty) {}

    /** The exchange rejected our order; {@code reason} is an {@link obs.core.OrderResult} code. */
    default void onOrderRejected(long time, long clientId, int reason) {}

    /** The market closed. No more callbacks follow. */
    default void onSessionEnd(long time) {}
}
