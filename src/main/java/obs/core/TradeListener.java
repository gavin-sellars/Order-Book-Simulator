package obs.core;

/**
 * Receives fills from a book. Trades are reported through a callback rather than as objects,
 * so the book itself never allocates one; the consumer decides whether to store them.
 *
 * A listener must not call back into the book that is notifying it.
 */
@FunctionalInterface
public interface TradeListener {

    /** Aggressor id used when a trade comes from a feed execution rather than from matching. */
    long UNKNOWN_ID = -1L;

    TradeListener NONE = (aggressorId, restingId, price, qty, aggressorSide) -> { };

    /** One fill. The price is always the resting order's price. */
    void onTrade(long aggressorId, long restingId, long price, int qty, byte aggressorSide);
}
