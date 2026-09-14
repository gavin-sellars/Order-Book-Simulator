package obs.strategy;

import obs.core.Book;
import obs.core.Side;

/**
 * A basic market maker (Part 6 of the guide). It isn't meant to be profitable; it exists to
 * exercise the simulator and produce a P&L curve and a fill rate.
 * <ul>
 *   <li>Quotes a fixed size on both sides: one tick inside the spread when the spread is at least
 *       three ticks wide, otherwise at the touch.</li>
 *   <li>When the price it wants on a side changes, it cancels that quote and sends a new one.</li>
 *   <li>Stops quoting the side that would add to its position once the position reaches the limit.</li>
 * </ul>
 * It keeps at most one working order per side. A replacement is only sent after the cancel is
 * acknowledged or the order has filled, so it never has two orders working on one side.
 */
public final class SampleMarketMaker implements Strategy {

    private final int quoteSize;
    private final long maxPosition;
    private final Quote bid = new Quote(Side.BUY);
    private final Quote ask = new Quote(Side.SELL);
    private StrategyContext context;
    private long position;
    private long lastBid = Book.NO_BID;
    private long lastAsk = Book.NO_ASK;
    private boolean closed;

    public SampleMarketMaker(int quoteSize, long maxPosition) {
        if (quoteSize <= 0 || maxPosition < quoteSize) throw new IllegalArgumentException("need 0 < quoteSize <= maxPosition");
        this.quoteSize = quoteSize;
        this.maxPosition = maxPosition;
    }

    /** One working order on one side of the book. */
    private static final class Quote {
        final byte side;
        long clientId = -1;         // -1: no order working
        long price;
        boolean cancelling;

        Quote(byte side) {
            this.side = side;
        }
    }

    @Override
    public void init(StrategyContext context) {
        this.context = context;
    }

    @Override
    public void onBookUpdate(long time, long bestBid, long bestAsk, long bidQty, long askQty) {
        lastBid = bestBid;
        lastAsk = bestAsk;
        requote();
    }

    @Override
    public void onOwnFill(long time, long clientId, byte side, long price, int qty, int remaining) {
        position += side == Side.BUY ? qty : -qty;
        Quote quote = side == Side.BUY ? bid : ask;
        if (quote.clientId == clientId && remaining == 0) {
            // The order is finished. If a cancel for it is still in flight, its acknowledgement will
            // no longer match this quote, so stop waiting for it here or the side would never requote.
            quote.clientId = -1;
            quote.cancelling = false;
        }
        requote();
    }

    @Override
    public void onOwnCancelAck(long time, long clientId, int cancelledQty) {
        for (Quote quote : new Quote[] {bid, ask}) {
            if (quote.clientId == clientId) {
                quote.clientId = -1;
                quote.cancelling = false;
            }
        }
        requote();
    }

    @Override
    public void onOrderRejected(long time, long clientId, int reason) {
        onOwnCancelAck(time, clientId, 0);
    }

    @Override
    public void onSessionEnd(long time) {
        closed = true;
        for (Quote quote : new Quote[] {bid, ask}) {
            if (quote.clientId >= 0 && !quote.cancelling) {
                quote.cancelling = true;
                context.cancel(quote.clientId);
            }
        }
    }

    public long position() {
        return position;
    }

    private void requote() {
        if (closed || lastBid == Book.NO_BID || lastAsk == Book.NO_ASK || lastBid >= lastAsk) return;

        long tick = context.tickSize();
        boolean improve = lastAsk - lastBid >= 3 * tick;
        long bidPrice = improve ? lastBid + tick : lastBid;
        long askPrice = improve ? lastAsk - tick : lastAsk;

        update(bid, bidPrice, position < maxPosition);
        update(ask, askPrice, position > -maxPosition);
    }

    private void update(Quote quote, long wantedPrice, boolean allowed) {
        if (quote.cancelling) return;                                   // wait for the acknowledgement
        if (quote.clientId >= 0) {
            if (allowed && quote.price == wantedPrice) return;          // already quoting the right price
            quote.cancelling = true;
            context.cancel(quote.clientId);
            return;
        }
        if (allowed) {
            quote.price = wantedPrice;
            quote.clientId = context.sendLimit(quote.side, wantedPrice, quoteSize);
        }
    }
}
