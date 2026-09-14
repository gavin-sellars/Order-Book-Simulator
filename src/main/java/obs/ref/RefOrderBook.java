package obs.ref;

import obs.core.OrderResult;
import obs.core.Side;
import obs.core.TradeListener;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The slow, obviously-correct order book from Part 1 of the guide.
 *
 * Standard collections, allocation everywhere, O(n) cancels. It exists to be simple enough to
 * trust: the fast book is tested against it, and the synthetic ITCH generator uses it as its
 * matching engine, so the generator never depends on the code it is used to test.
 *
 * There are two ways to put an order in the book:
 * <ul>
 *   <li>Matching mode ({@link #addLimitOrder}, {@link #addMarketOrder}): the order trades
 *       against the opposite side where prices cross, and any limit remainder rests.</li>
 *   <li>Book-builder mode ({@link #addRestingOrder}, {@link #execute}, {@link #replace}): the
 *       order is applied exactly as given, with no matching. This is for replaying a feed,
 *       where the exchange has already done the matching. The caller is responsible for not
 *       creating a crossed book in this mode.</li>
 * </ul>
 *
 * Prices use the {@link obs.core.Prices} convention and must be positive multiples of the tick.
 */
public final class RefOrderBook {

    /** bestBid() when there are no bids. */
    public static final long NO_BID = Long.MIN_VALUE;
    /** bestAsk() when there are no asks. */
    public static final long NO_ASK = Long.MAX_VALUE;

    private static final class Order {
        final long id;
        final byte side;
        final long price;
        int qty;            // remaining, not original

        Order(long id, byte side, long price, int qty) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.qty = qty;
        }
    }

    // Bids sorted descending and asks ascending, so firstKey() is always the best price.
    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    // Finds an order by id without scanning.
    private final HashMap<Long, Order> byId = new HashMap<>();

    private final long tickSize;
    private final TradeListener listener;

    public RefOrderBook(long tickSize, TradeListener listener) {
        if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive");
        this.tickSize = tickSize;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    // ---------------------------------------------------------------- matching mode

    /** Matches what it can at the resting orders' prices, then rests the remainder. */
    public int addLimitOrder(long id, byte side, long price, int qty) {
        int check = validateNew(id, side, price, qty);
        if (check != OrderResult.ACCEPTED) return check;

        int remaining = match(id, side, true, price, qty);
        if (remaining > 0) rest(id, side, price, remaining);
        return OrderResult.ACCEPTED;
    }

    /** Immediate-or-cancel at any price: fills what it can, discards the rest, never rests. */
    public int addMarketOrder(long id, byte side, int qty) {
        if (!Side.isValid(side)) return OrderResult.REJECTED_SIDE;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (byId.containsKey(id)) return OrderResult.REJECTED_DUP_ID;

        match(id, side, false, 0L, qty);
        return OrderResult.ACCEPTED;
    }

    /** Crosses against the opposite side. Returns the unfilled quantity. */
    private int match(long aggressorId, byte side, boolean limited, long limitPrice, int qty) {
        TreeMap<Long, ArrayDeque<Order>> opposite = (side == Side.BUY) ? asks : bids;

        while (qty > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> best = opposite.firstEntry();
            long levelPrice = best.getKey();

            // A buyer crosses when the best ask is at or below their limit; a seller the reverse.
            if (limited && !(side == Side.BUY ? levelPrice <= limitPrice : levelPrice >= limitPrice)) break;

            ArrayDeque<Order> queue = best.getValue();
            while (qty > 0 && !queue.isEmpty()) {
                Order resting = queue.peekFirst();          // FIFO: oldest first
                int fill = Math.min(qty, resting.qty);

                qty -= fill;
                resting.qty -= fill;
                if (resting.qty == 0) {
                    queue.pollFirst();
                    byId.remove(resting.id);
                }
                listener.onTrade(aggressorId, resting.id, levelPrice, fill, side);
            }

            if (queue.isEmpty()) opposite.remove(levelPrice);
        }
        return qty;
    }

    // ---------------------------------------------------------------- book-builder mode

    /** Rests the order exactly as given. Never matches, even if the price crosses. */
    public int addRestingOrder(long id, byte side, long price, int qty) {
        int check = validateNew(id, side, price, qty);
        if (check != OrderResult.ACCEPTED) return check;

        rest(id, side, price, qty);
        return OrderResult.ACCEPTED;
    }

    /**
     * A feed reported that resting order {@code id} traded {@code qty} shares. The trade is
     * reported with {@link TradeListener#UNKNOWN_ID} as the aggressor.
     *
     * Returns false and changes nothing unless qty is in 1..remaining, because a feed can never
     * execute more than an order has.
     */
    public boolean execute(long id, int qty) {
        Order o = byId.get(id);
        if (o == null || qty <= 0 || qty > o.qty) return false;

        o.qty -= qty;
        if (o.qty == 0) remove(o);
        listener.onTrade(TradeListener.UNKNOWN_ID, id, o.price, qty, Side.opposite(o.side));
        return true;
    }

    /**
     * Removes {@code oldId} and rests {@code newId} on the same side with the new price and size,
     * at the back of the queue (a replace loses time priority, as with ITCH 'U').
     * Validates everything before changing anything. Never matches.
     */
    public int replace(long oldId, long newId, long price, int qty) {
        Order old = byId.get(oldId);
        if (old == null) return OrderResult.REJECTED_UNKNOWN_ID;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (!isValidPrice(price)) return OrderResult.REJECTED_PRICE;
        if (newId != oldId && byId.containsKey(newId)) return OrderResult.REJECTED_DUP_ID;

        remove(old);
        rest(newId, old.side, price, qty);
        return OrderResult.ACCEPTED;
    }

    // ---------------------------------------------------------------- both modes

    /** Removes an order entirely. Returns false if it isn't in the book. */
    public boolean cancel(long id) {
        Order o = byId.get(id);
        if (o == null) return false;
        remove(o);
        return true;
    }

    /**
     * Partial cancel: shrinks the order and keeps its queue position. Removes the order if
     * {@code by} is at least its remaining quantity. Returns false if nothing was changed.
     */
    public boolean reduce(long id, int by) {
        if (by <= 0) return false;
        Order o = byId.get(id);
        if (o == null) return false;

        if (by >= o.qty) remove(o);
        else o.qty -= by;
        return true;
    }

    // ---------------------------------------------------------------- queries

    public long bestBid() {
        return bids.isEmpty() ? NO_BID : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? NO_ASK : asks.firstKey();
    }

    /** True if the best bid is at or above the best ask. Impossible in matching mode. */
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }

    public long bidQtyAt(long price) {
        return totalQty(bids.get(price));
    }

    public long askQtyAt(long price) {
        return totalQty(asks.get(price));
    }

    public boolean contains(long id) {
        return byId.containsKey(id);
    }

    /** Remaining quantity of a resting order, or 0 if it isn't in the book. */
    public int restingQty(long id) {
        Order o = byId.get(id);
        return o == null ? 0 : o.qty;
    }

    public int orderCount() {
        return byId.size();
    }

    /** Number of non-empty price levels on one side. */
    public int levelCount(byte side) {
        return levels(side).size();
    }

    /**
     * Copies up to {@code n} levels of one side into the arrays, best price first.
     * {@code counts} may be null. Returns the number of levels written.
     */
    public int depth(byte side, int n, long[] prices, long[] qtys, int[] counts) {
        int i = 0;
        for (Map.Entry<Long, ArrayDeque<Order>> level : levels(side).entrySet()) {
            if (i >= n) break;
            prices[i] = level.getKey();
            qtys[i] = totalQty(level.getValue());
            if (counts != null) counts[i] = level.getValue().size();
            i++;
        }
        return i;
    }

    /** Order ids resting at one price, in time priority (front of the queue first). */
    public long[] queueAt(byte side, long price) {
        ArrayDeque<Order> queue = levels(side).get(price);
        if (queue == null) return new long[0];
        return queue.stream().mapToLong(o -> o.id).toArray();
    }

    // ---------------------------------------------------------------- internals

    private int validateNew(long id, byte side, long price, int qty) {
        if (!Side.isValid(side)) return OrderResult.REJECTED_SIDE;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (!isValidPrice(price)) return OrderResult.REJECTED_PRICE;
        if (byId.containsKey(id)) return OrderResult.REJECTED_DUP_ID;
        return OrderResult.ACCEPTED;
    }

    private boolean isValidPrice(long price) {
        return price > 0 && price % tickSize == 0;
    }

    private void rest(long id, byte side, long price, int qty) {
        Order o = new Order(id, side, price, qty);
        levels(side).computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(o);   // back of the queue
        byId.put(id, o);
    }

    private void remove(Order o) {
        byId.remove(o.id);
        TreeMap<Long, ArrayDeque<Order>> book = levels(o.side);
        ArrayDeque<Order> queue = book.get(o.price);
        queue.remove(o);                        // O(n) scan by identity; the fast book fixes this
        if (queue.isEmpty()) book.remove(o.price);
    }

    private TreeMap<Long, ArrayDeque<Order>> levels(byte side) {
        return switch (side) {
            case Side.BUY -> bids;
            case Side.SELL -> asks;
            default -> throw new IllegalArgumentException("invalid side " + side);
        };
    }

    private static long totalQty(ArrayDeque<Order> queue) {
        if (queue == null) return 0;
        long sum = 0;
        for (Order o : queue) sum += o.qty;
        return sum;
    }
}
