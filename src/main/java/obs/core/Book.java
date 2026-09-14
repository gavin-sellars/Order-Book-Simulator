package obs.core;

/**
 * Operations shared by the fast {@link OrderBook} and the reference book. Having one interface
 * lets the same tests, feed handlers and generators drive either implementation.
 *
 * There are two ways to put an order in a book:
 * <ul>
 *   <li>Matching mode ({@link #addLimitOrder}, {@link #addMarketOrder}): the order trades
 *       against the opposite side where prices cross, and any limit remainder rests.</li>
 *   <li>Book-builder mode ({@link #addRestingOrder}, {@link #execute}, {@link #replace}): the
 *       order is applied exactly as given, with no matching. This is for replaying a feed,
 *       where the exchange has already done the matching. The caller is responsible for not
 *       creating a crossed book in this mode.</li>
 * </ul>
 *
 * Prices follow the {@link Prices} convention. Order entry reports problems through
 * {@link OrderResult} codes and never changes the book when it rejects something.
 */
public interface Book {

    /** bestBid() when there are no bids. */
    long NO_BID = Long.MIN_VALUE;
    /** bestAsk() when there are no asks. */
    long NO_ASK = Long.MAX_VALUE;

    /** Matches what it can at the resting orders' prices, then rests the remainder. */
    int addLimitOrder(long id, byte side, long price, int qty);

    /** Immediate-or-cancel at any price: fills what it can, discards the rest, never rests. */
    int addMarketOrder(long id, byte side, int qty);

    /** Rests the order exactly as given. Never matches, even if the price crosses. */
    int addRestingOrder(long id, byte side, long price, int qty);

    /** Removes an order entirely. Returns false if it isn't in the book. */
    boolean cancel(long id);

    /**
     * Partial cancel: shrinks the order and keeps its queue position. Removes the order if
     * {@code by} is at least its remaining quantity. Returns false if nothing was changed.
     */
    boolean reduce(long id, int by);

    /**
     * A feed reported that resting order {@code id} traded {@code qty} shares. The trade is
     * reported with {@link TradeListener#UNKNOWN_ID} as the aggressor. Returns false and changes
     * nothing unless qty is in 1..remaining, because a feed can never execute more than an order has.
     */
    boolean execute(long id, int qty);

    /**
     * Removes {@code oldId} and rests {@code newId} on the same side with the new price and size,
     * at the back of the queue (a replace loses time priority, as with ITCH 'U').
     * Validates everything before changing anything. Never matches.
     */
    int replace(long oldId, long newId, long price, int qty);

    long bestBid();

    long bestAsk();

    /** True if the best bid is at or above the best ask. Impossible in matching mode. */
    boolean isCrossed();

    long bidQtyAt(long price);

    long askQtyAt(long price);

    boolean contains(long id);

    /** Remaining quantity of a resting order, or 0 if it isn't in the book. */
    int restingQty(long id);

    int orderCount();

    /** Number of non-empty price levels on one side. */
    int levelCount(byte side);

    /**
     * Copies up to {@code n} levels of one side into the arrays, best price first.
     * {@code counts} may be null. Returns the number of levels written.
     */
    int depth(byte side, int n, long[] prices, long[] qtys, int[] counts);

    /** Order ids resting at one price, front of the queue first. Allocates: tests and debugging only. */
    long[] queueAt(byte side, long price);
}
