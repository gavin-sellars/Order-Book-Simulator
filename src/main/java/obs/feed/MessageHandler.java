package obs.feed;

/**
 * Receives order book events from a feed reader, already decoded into primitives. Every method
 * has an empty default, so a handler only implements what it needs.
 *
 * Timestamps are nanoseconds since midnight. Sides are {@link obs.core.Side} values. Prices use
 * the {@link obs.core.Prices} convention.
 */
public interface MessageHandler {

    /** A market-wide event, such as the start or end of market hours. */
    default void onSystemEvent(long timestamp, byte eventCode) {}

    /** A new order rested on the book. The exchange has already matched it, so it never crosses. */
    default void onAdd(long timestamp, long orderRef, byte side, int shares, long price) {}

    /**
     * A resting order traded. {@code price} is the execution price when the feed states one
     * ('C' messages), or -1 when the trade was at the resting order's own price ('E' messages).
     */
    default void onExecute(long timestamp, long orderRef, int shares, long matchNumber, long price) {}

    /** Part of a resting order was cancelled; it keeps its queue position. */
    default void onCancel(long timestamp, long orderRef, int shares) {}

    /** A resting order was removed entirely. */
    default void onDelete(long timestamp, long orderRef) {}

    /** A resting order was replaced by a new one with a new reference, which loses queue position. */
    default void onReplace(long timestamp, long oldRef, long newRef, int shares, long price) {}

    /** A trade against non-displayed liquidity. It doesn't change the visible book. */
    default void onHiddenTrade(long timestamp, byte side, int shares, long price, long matchNumber) {}
}
