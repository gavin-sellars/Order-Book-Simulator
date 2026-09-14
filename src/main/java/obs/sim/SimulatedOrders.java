package obs.sim;

import obs.core.OrderBook;
import obs.core.OrderResult;
import obs.core.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * The strategy's orders inside a replayed L3 feed, and when they would have filled (Part 6).
 *
 * The historical book is kept exactly as the feed describes it; strategy orders are never inserted
 * into it. Instead each strategy order remembers where it joined the queue: the shares already
 * resting at its price, and the book's arrival sequence number at that moment. Every real order
 * with a lower sequence number was ahead of it; every later one is behind. Because the feed is L3,
 * this is exact, with no need for the pessimistic guesses an L2 feed forces.
 *
 * Call the matching {@code before...} method for each feed message, <em>before</em> the message is
 * applied to the book, so the affected order's price, side and sequence number can still be read:
 * <ul>
 *   <li>a cancel, delete or replace of a real order ahead moves the strategy order up the queue;
 *       one behind changes nothing</li>
 *   <li>an execution of a real order ahead also moves it up</li>
 *   <li>an execution of a real order behind it, or at a worse price, means the aggressor would have
 *       reached the strategy order first, so the strategy order fills</li>
 *   <li>a new real order that crosses a strategy order's price would have traded with it</li>
 * </ul>
 *
 * Limitations, all from assuming the strategy has no market impact:
 * <ul>
 *   <li>Historical orders still trade as they did, even when a strategy order took those shares first.</li>
 *   <li>A marketable strategy order takes the displayed liquidity without removing it, so two
 *       strategy orders could take the same shares.</li>
 *   <li>With several strategy orders on one side, one historical execution can fill each of them.</li>
 * </ul>
 */
public final class SimulatedOrders {

    /** Fill notifications. {@code passive} is true when the strategy order was resting. */
    @FunctionalInterface
    public interface FillListener {
        void onFill(long clientId, byte side, long price, int qty, boolean passive, int remaining);
    }

    private static final class Order {
        final long clientId;
        final byte side;
        final long price;
        final long arrivalSeq;
        int remaining;
        long qtyAhead;

        Order(long clientId, byte side, long price, int remaining, long qtyAhead, long arrivalSeq) {
            this.clientId = clientId;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
            this.qtyAhead = qtyAhead;
            this.arrivalSeq = arrivalSeq;
        }
    }

    private static final int MAX_DEPTH = 256;

    private final OrderBook book;
    private final FillListener listener;
    private final List<Order> resting = new ArrayList<>();
    private final long[] depthPrices = new long[MAX_DEPTH];
    private final long[] depthQtys = new long[MAX_DEPTH];

    public SimulatedOrders(OrderBook book, FillListener listener) {
        this.book = book;
        this.listener = listener;
    }

    /**
     * A strategy order reaches the matching engine. It first takes displayed liquidity on the other
     * side that its price reaches, best price first, at the resting prices. Anything left rests at
     * the back of its price level. Returns an {@link OrderResult} code.
     */
    public int submit(long clientId, byte side, long price, int qty) {
        if (!Side.isValid(side)) return OrderResult.REJECTED_SIDE;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (price < book.minPrice() || price > book.maxPrice() || (price - book.minPrice()) % book.tickSize() != 0) {
            return OrderResult.REJECTED_PRICE;
        }
        if (find(clientId) != null) return OrderResult.REJECTED_DUP_ID;

        int remaining = qty;
        byte opposite = Side.opposite(side);
        int levels = book.depth(opposite, MAX_DEPTH, depthPrices, depthQtys, null);
        for (int i = 0; i < levels && remaining > 0; i++) {
            boolean reaches = side == Side.BUY ? depthPrices[i] <= price : depthPrices[i] >= price;
            if (!reaches) break;
            int fill = (int) Math.min(remaining, depthQtys[i]);
            remaining -= fill;
            listener.onFill(clientId, side, depthPrices[i], fill, false, remaining);
        }

        if (remaining > 0) {
            long ahead = side == Side.BUY ? book.bidQtyAt(price) : book.askQtyAt(price);
            resting.add(new Order(clientId, side, price, remaining, ahead, book.nextArrivalSeq()));
        }
        return OrderResult.ACCEPTED;
    }

    /** Removes a strategy order. Returns the quantity cancelled, or 0 if it had already filled or never existed. */
    public int cancel(long clientId) {
        Order order = find(clientId);
        if (order == null) return 0;
        resting.remove(order);
        return order.remaining;
    }

    // ---------------------------------------------------------------- feed messages, before they reach the book

    public void beforeAdd(long orderRef, byte side, int shares, long price) {
        for (Order o : resting) {
            if (o.side == side || o.remaining == 0) continue;
            boolean crosses = side == Side.SELL ? price <= o.price : price >= o.price;
            if (crosses) fill(o, shares);
        }
        removeFilled();
    }

    public void beforeExecute(long orderRef, int shares) {
        byte side = book.sideOf(orderRef);
        if (side < 0) return;
        long price = book.priceOf(orderRef);
        long seq = book.arrivalSeq(orderRef);

        for (Order o : resting) {
            if (o.side != side || o.remaining == 0) continue;
            if (price == o.price) {
                if (seq < o.arrivalSeq) {
                    o.qtyAhead = Math.max(0, o.qtyAhead - shares);      // an order ahead traded
                } else {
                    o.qtyAhead = 0;                                     // an order behind traded: we were reached first
                    fill(o, shares);
                }
            } else if (side == Side.BUY ? price < o.price : price > o.price) {
                fill(o, shares);                                        // the market traded through our price
            }
        }
        removeFilled();
    }

    public void beforeCancel(long orderRef, int cancelledShares) {
        moveUp(orderRef, Math.min(cancelledShares, book.restingQty(orderRef)));
    }

    public void beforeDelete(long orderRef) {
        moveUp(orderRef, book.restingQty(orderRef));
    }

    /** The original order leaves its place; its replacement joins the back, behind every strategy order. */
    public void beforeReplace(long originalRef) {
        moveUp(originalRef, book.restingQty(originalRef));
    }

    // ---------------------------------------------------------------- queries

    public int restingQty(long clientId) {
        Order order = find(clientId);
        return order == null ? 0 : order.remaining;
    }

    /** Shares ahead of a strategy order in its queue, or -1 if it isn't resting. */
    public long qtyAhead(long clientId) {
        Order order = find(clientId);
        return order == null ? -1 : order.qtyAhead;
    }

    public int restingCount() {
        return resting.size();
    }

    // ---------------------------------------------------------------- internals

    private void moveUp(long orderRef, int shares) {
        byte side = book.sideOf(orderRef);
        if (side < 0 || shares <= 0) return;
        long price = book.priceOf(orderRef);
        long seq = book.arrivalSeq(orderRef);
        for (Order o : resting) {
            if (o.side == side && o.price == price && seq < o.arrivalSeq) o.qtyAhead = Math.max(0, o.qtyAhead - shares);
        }
    }

    private void fill(Order o, int available) {
        int qty = Math.min(o.remaining, available);
        if (qty <= 0) return;
        o.remaining -= qty;
        listener.onFill(o.clientId, o.side, o.price, qty, true, o.remaining);
    }

    private void removeFilled() {
        resting.removeIf(o -> o.remaining == 0);
    }

    private Order find(long clientId) {
        for (Order o : resting) if (o.clientId == clientId) return o;
        return null;
    }
}
