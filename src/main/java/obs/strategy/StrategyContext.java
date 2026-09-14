package obs.strategy;

/** What a {@link Strategy} can do: read the clock and send or cancel orders. */
public interface StrategyContext {

    /** Current simulation time, in nanoseconds since midnight. */
    long now();

    /** Minimum price increment of the instrument. */
    long tickSize();

    /**
     * Sends a limit order and returns its client id immediately. The order reaches the exchange
     * after the order-entry delay, where it trades against what is resting at that moment and rests
     * any remainder.
     */
    long sendLimit(byte side, long price, int qty);

    /** Asks the exchange to cancel an order. It takes effect after the order-entry delay, if the order is still open then. */
    void cancel(long clientId);
}
