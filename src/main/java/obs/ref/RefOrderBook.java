package obs.ref;

import obs.core.Book;
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
 * Prices must be positive multiples of the tick. See {@link Book} for the two modes.
 */
public final class RefOrderBook implements Book {

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

    @Override
    public int addLimitOrder(long id, byte side, long price, int qty) {
        int check = validateNew(id, side, price, qty);
        if (check != OrderResult.ACCEPTED) return check;

        int remaining = match(id, side, true, price, qty);
        if (remaining > 0) rest(id, side, price, remaining);
        return OrderResult.ACCEPTED;
    }

    @Override
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

    @Override
    public int addRestingOrder(long id, byte side, long price, int qty) {
        int check = validateNew(id, side, price, qty);
        if (check != OrderResult.ACCEPTED) return check;

        rest(id, side, price, qty);
        return OrderResult.ACCEPTED;
    }

    @Override
    public boolean execute(long id, int qty) {
        Order o = byId.get(id);
        if (o == null || qty <= 0 || qty > o.qty) return false;

        o.qty -= qty;
        if (o.qty == 0) remove(o);
        listener.onTrade(TradeListener.UNKNOWN_ID, id, o.price, qty, Side.opposite(o.side));
        return true;
    }

    @Override
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

    @Override
    public boolean cancel(long id) {
        Order o = byId.get(id);
        if (o == null) return false;
        remove(o);
        return true;
    }

    @Override
    public boolean reduce(long id, int by) {
        if (by <= 0) return false;
        Order o = byId.get(id);
        if (o == null) return false;

        if (by >= o.qty) remove(o);
        else o.qty -= by;
        return true;
    }

    // ---------------------------------------------------------------- queries

    @Override
    public long bestBid() {
        return bids.isEmpty() ? NO_BID : bids.firstKey();
    }

    @Override
    public long bestAsk() {
        return asks.isEmpty() ? NO_ASK : asks.firstKey();
    }

    @Override
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }

    @Override
    public long bidQtyAt(long price) {
        return totalQty(bids.get(price));
    }

    @Override
    public long askQtyAt(long price) {
        return totalQty(asks.get(price));
    }

    @Override
    public boolean contains(long id) {
        return byId.containsKey(id);
    }

    @Override
    public int restingQty(long id) {
        Order o = byId.get(id);
        return o == null ? 0 : o.qty;
    }

    @Override
    public int orderCount() {
        return byId.size();
    }

    @Override
    public int levelCount(byte side) {
        return levels(side).size();
    }

    @Override
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

    @Override
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
