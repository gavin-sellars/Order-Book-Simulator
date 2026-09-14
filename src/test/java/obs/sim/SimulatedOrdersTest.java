package obs.sim;

import obs.core.OrderBook;
import obs.core.OrderResult;
import obs.core.Prices;
import obs.core.TradeListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static obs.core.Side.BUY;
import static obs.core.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queue position inference. Each helper applies a feed message the way the simulator does:
 * the tracker sees it first, then the book applies it.
 */
class SimulatedOrdersTest {

    private final OrderBook book = new OrderBook(Prices.parse("100.00"), Prices.CENT, 20_000, 1024, TradeListener.NONE);
    private final List<String> fills = new ArrayList<>();
    private final SimulatedOrders orders = new SimulatedOrders(book,
            (clientId, side, price, qty, passive, remaining) -> fills.add(
                    "#" + clientId + " " + (side == BUY ? "BUY" : "SELL") + " " + qty + " @ " + Prices.format(price)
                            + (passive ? " passive" : " aggressive") + ", " + remaining + " left"));

    private static long px(String price) {
        return Prices.parse(price);
    }

    private void add(long ref, byte side, int shares, String price) {
        orders.beforeAdd(ref, side, shares, px(price));
        book.addRestingOrder(ref, side, px(price), shares);
    }

    private void execute(long ref, int shares) {
        orders.beforeExecute(ref, shares);
        assertTrue(book.execute(ref, shares));
    }

    private void cancel(long ref, int shares) {
        orders.beforeCancel(ref, shares);
        assertTrue(book.reduce(ref, shares));
    }

    private void delete(long ref) {
        orders.beforeDelete(ref);
        assertTrue(book.cancel(ref));
    }

    private void replace(long oldRef, long newRef, int shares, String price) {
        orders.beforeReplace(oldRef);
        assertEquals(OrderResult.ACCEPTED, book.replace(oldRef, newRef, px(price), shares));
    }

    @Test
    void theGuidesQueuePositionWalkthrough() {
        add(1, BUY, 300, "150.00");
        add(2, BUY, 200, "150.00");

        // You place a bid for 100 at $150.00 behind 500 shares.
        assertEquals(OrderResult.ACCEPTED, orders.submit(1001, BUY, px("150.00"), 100));
        assertEquals(500, orders.qtyAhead(1001));
        add(3, BUY, 400, "150.00");                         // someone joins behind you

        // A market sell of 300 fills the first 300 ahead of you.
        execute(1, 300);
        assertEquals(200, orders.qtyAhead(1001));
        assertTrue(fills.isEmpty());

        // Someone ahead of you cancels 150 shares.
        cancel(2, 150);
        assertEquals(50, orders.qtyAhead(1001));

        // A market sell of 200: 50 finishes the orders ahead, and in history the other 150 went to
        // the order behind you, so it would have filled you first.
        execute(2, 50);
        assertEquals(0, orders.qtyAhead(1001));
        assertTrue(fills.isEmpty());
        execute(3, 150);

        assertEquals(List.of("#1001 BUY 100 @ 150.00 passive, 0 left"), fills);
        assertEquals(0, orders.restingCount());
    }

    @Test
    void cancelsAndExecutionsBehindDoNotMoveTheOrderUp() {
        add(1, BUY, 500, "150.00");
        orders.submit(1001, BUY, px("150.00"), 100);
        add(2, BUY, 300, "150.00");

        cancel(2, 100);
        delete(2);

        assertEquals(500, orders.qtyAhead(1001));
        assertTrue(fills.isEmpty());
    }

    @Test
    void ordersAtOtherPricesOrOnTheOtherSideDoNotMoveTheOrderUp() {
        add(1, BUY, 500, "150.00");
        add(2, BUY, 500, "150.01");
        add(3, SELL, 500, "150.05");
        orders.submit(1001, BUY, px("150.00"), 100);

        delete(2);
        execute(3, 200);

        assertEquals(500, orders.qtyAhead(1001));
        assertTrue(fills.isEmpty());
    }

    @Test
    void tradingThroughTheOrdersPriceFillsIt() {
        add(1, BUY, 500, "150.00");
        add(2, BUY, 500, "149.99");
        orders.submit(1001, BUY, px("150.00"), 100);

        execute(2, 80);                                     // a trade below our bid: our whole level went first

        assertEquals(List.of("#1001 BUY 80 @ 150.00 passive, 20 left"), fills);
        assertEquals(20, orders.restingQty(1001));
    }

    @Test
    void aReplacedOrderAheadMovesBehind() {
        add(1, SELL, 300, "150.02");
        orders.submit(1001, SELL, px("150.02"), 100);
        assertEquals(300, orders.qtyAhead(1001));

        replace(1, 5, 300, "150.02");
        assertEquals(0, orders.qtyAhead(1001));

        execute(5, 250);
        assertEquals(List.of("#1001 SELL 100 @ 150.02 passive, 0 left"), fills);
    }

    @Test
    void anIncomingOrderThatCrossesTheStrategyOrderTradesWithIt() {
        orders.submit(1001, SELL, px("150.05"), 100);

        add(1, BUY, 60, "150.06");

        assertEquals(List.of("#1001 SELL 60 @ 150.05 passive, 40 left"), fills);
    }

    @Test
    void marketableOrderTakesDisplayedLiquidityAtRestingPricesThenRests() {
        add(1, SELL, 100, "150.01");
        add(2, SELL, 200, "150.02");
        add(3, BUY, 700, "150.00");

        orders.submit(1001, BUY, px("150.02"), 250);
        orders.submit(1002, BUY, px("150.01"), 500);

        assertEquals(List.of(
                "#1001 BUY 100 @ 150.01 aggressive, 150 left",
                "#1001 BUY 150 @ 150.02 aggressive, 0 left",
                "#1002 BUY 100 @ 150.01 aggressive, 400 left"), fills);
        assertEquals(0, orders.restingQty(1001));
        assertEquals(400, orders.restingQty(1002));
        assertEquals(0, orders.qtyAhead(1002), "nothing was bidding at 150.01");
        assertEquals(300, book.askQtyAt(px("150.01")) + book.askQtyAt(px("150.02")), "no market impact: the book is untouched");
    }

    @Test
    void cancelReturnsWhatWasStillOpen() {
        orders.submit(1001, BUY, px("150.00"), 100);
        add(1, BUY, 50, "149.99");
        execute(1, 30);

        assertEquals(70, orders.cancel(1001));
        assertEquals(0, orders.cancel(1001));
        assertEquals(0, orders.cancel(4242));
    }

    @Test
    void rejectsInvalidOrders() {
        orders.submit(1001, BUY, px("150.00"), 100);

        assertEquals(OrderResult.REJECTED_DUP_ID, orders.submit(1001, BUY, px("150.00"), 100));
        assertEquals(OrderResult.REJECTED_SIDE, orders.submit(1002, (byte) 7, px("150.00"), 100));
        assertEquals(OrderResult.REJECTED_QTY, orders.submit(1002, BUY, px("150.00"), 0));
        assertEquals(OrderResult.REJECTED_PRICE, orders.submit(1002, BUY, px("150.005"), 100));
        assertEquals(OrderResult.REJECTED_PRICE, orders.submit(1002, BUY, px("99.99"), 100));
        assertEquals(1, orders.restingCount());
    }
}
