package obs.ref;

import obs.core.Book;
import obs.core.OrderResult;
import obs.core.Side;
import obs.core.TradeListener;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * {@link RefOrderBook} with exactly one change, kept to measure what each part of the fast book buys.
 *
 * Each price level is an intrusive doubly-linked list of Order objects instead of an ArrayDeque,
 * so removing an order from the middle of a queue is O(1) instead of a scan. Everything else is
 * the reference book's: TreeMap levels, a HashMap from id to Order, an object per order, and the
 * same TreeMap lookup when an order is removed. Against RefOrderBook it isolates the O(1) cancel;
 * against the fast OrderBook it isolates the array ladder, order pool and primitive id map.
 * docs/BENCHMARKS.md has the measurements.
 */
public final class RefLinkedOrderBook implements Book {

    private static final class Order {
        final long id;
        final byte side;
        final long price;
        int qty;            // remaining, not original
        Order prev;         // towards the front of the queue
        Order next;         // towards the back

        Order(long id, byte side, long price, int qty) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.qty = qty;
        }
    }

    private static final class Level {
        Order head;         // oldest
        Order tail;         // newest
        int count;

        void addLast(Order o) {
            o.prev = tail;
            o.next = null;
            if (tail == null) head = o; else tail.next = o;
            tail = o;
            count++;
        }

        void unlink(Order o) {
            if (o.prev == null) head = o.next; else o.prev.next = o.next;
            if (o.next == null) tail = o.prev; else o.next.prev = o.prev;
            o.prev = null;
            o.next = null;
            count--;
        }

        boolean isEmpty() {
            return head == null;
        }
    }

    // Bids sorted descending and asks ascending, so firstKey() is always the best price.
    private final TreeMap<Long, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, Level> asks = new TreeMap<>();
    private final HashMap<Long, Order> byId = new HashMap<>();

    private final long tickSize;
    private final TradeListener listener;

    public RefLinkedOrderBook(long tickSize, TradeListener listener) {
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

    private int match(long aggressorId, byte side, boolean limited, long limitPrice, int qty) {
        TreeMap<Long, Level> opposite = (side == Side.BUY) ? asks : bids;

        while (qty > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, Level> best = opposite.firstEntry();
            long levelPrice = best.getKey();
            if (limited && !(side == Side.BUY ? levelPrice <= limitPrice : levelPrice >= limitPrice)) break;

            Level queue = best.getValue();
            while (qty > 0 && !queue.isEmpty()) {
                Order resting = queue.head;
                int fill = Math.min(qty, resting.qty);

                qty -= fill;
                resting.qty -= fill;
                if (resting.qty == 0) {
                    queue.unlink(resting);
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
        for (Map.Entry<Long, Level> level : levels(side).entrySet()) {
            if (i >= n) break;
            prices[i] = level.getKey();
            qtys[i] = totalQty(level.getValue());
            if (counts != null) counts[i] = level.getValue().count;
            i++;
        }
        return i;
    }

    @Override
    public long[] queueAt(byte side, long price) {
        Level queue = levels(side).get(price);
        if (queue == null) return new long[0];
        long[] ids = new long[queue.count];
        int i = 0;
        for (Order o = queue.head; o != null; o = o.next) ids[i++] = o.id;
        return ids;
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
        levels(side).computeIfAbsent(price, p -> new Level()).addLast(o);   // back of the queue
        byId.put(id, o);
    }

    private void remove(Order o) {
        byId.remove(o.id);
        TreeMap<Long, Level> book = levels(o.side);
        Level queue = book.get(o.price);        // the same lookup RefOrderBook does
        queue.unlink(o);                        // O(1), where RefOrderBook scans the queue
        if (queue.isEmpty()) book.remove(o.price);
    }

    private TreeMap<Long, Level> levels(byte side) {
        return switch (side) {
            case Side.BUY -> bids;
            case Side.SELL -> asks;
            default -> throw new IllegalArgumentException("invalid side " + side);
        };
    }

    private static long totalQty(Level queue) {
        if (queue == null) return 0;
        long sum = 0;
        for (Order o = queue.head; o != null; o = o.next) sum += o.qty;
        return sum;
    }
}
