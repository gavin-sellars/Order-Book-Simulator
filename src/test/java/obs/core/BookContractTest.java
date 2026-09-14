package obs.core;

import obs.testutil.TradeRecorder;
import obs.testutil.TradeRecorder.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static obs.core.OrderResult.ACCEPTED;
import static obs.core.OrderResult.REJECTED_DUP_ID;
import static obs.core.OrderResult.REJECTED_PRICE;
import static obs.core.OrderResult.REJECTED_QTY;
import static obs.core.OrderResult.REJECTED_SIDE;
import static obs.core.OrderResult.REJECTED_UNKNOWN_ID;
import static obs.core.Side.BUY;
import static obs.core.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every {@link Book} must have. Each implementation's test class extends this, so the
 * reference book and the fast book are held to exactly the same scenarios. Prices used here
 * stay between $149.98 and $151.00.
 */
public abstract class BookContractTest {

    protected TradeRecorder trades;
    protected Book book;

    protected abstract Book newBook(TradeListener listener);

    @BeforeEach
    void createBook() {
        trades = new TradeRecorder();
        book = newBook(trades);
    }

    protected static long px(String price) {
        return Prices.parse(price);
    }

    private static Trade trade(long aggressorId, long restingId, String price, int qty, byte aggressorSide) {
        return new Trade(aggressorId, restingId, px(price), qty, aggressorSide);
    }

    /** The example book from Part 0 of the guide. Asks are ids 101-103, bids 201-203. */
    private void loadPart0Book() {
        assertEquals(ACCEPTED, book.addLimitOrder(101, SELL, px("150.03"), 900));
        assertEquals(ACCEPTED, book.addLimitOrder(102, SELL, px("150.02"), 400));
        assertEquals(ACCEPTED, book.addLimitOrder(103, SELL, px("150.01"), 200));
        assertEquals(ACCEPTED, book.addLimitOrder(201, BUY, px("150.00"), 500));
        assertEquals(ACCEPTED, book.addLimitOrder(202, BUY, px("149.99"), 1200));
        assertEquals(ACCEPTED, book.addLimitOrder(203, BUY, px("149.98"), 700));
        assertTrue(trades.isEmpty());
    }

    // ---------------------------------------------------------------- resting and the touch

    @Test
    void emptyBookReportsSentinelTouch() {
        assertEquals(Book.NO_BID, book.bestBid());
        assertEquals(Book.NO_ASK, book.bestAsk());
        assertFalse(book.isCrossed());
        assertEquals(0, book.orderCount());
    }

    @Test
    void nonCrossingOrdersRest() {
        loadPart0Book();

        assertEquals(px("150.00"), book.bestBid());
        assertEquals(px("150.01"), book.bestAsk());
        assertEquals(400, book.askQtyAt(px("150.02")));
        assertEquals(1200, book.bidQtyAt(px("149.99")));
        assertEquals(0, book.bidQtyAt(px("150.02")));
        assertEquals(6, book.orderCount());
        assertEquals(3, book.levelCount(BUY));
        assertEquals(3, book.levelCount(SELL));
        assertFalse(book.isCrossed());
    }

    @Test
    void limitThatDoesNotReachTheTouchJustRests() {
        book.addLimitOrder(1, SELL, px("150.01"), 100);
        book.addLimitOrder(2, BUY, px("150.00"), 100);

        assertTrue(trades.isEmpty());
        assertEquals(2, book.orderCount());
    }

    @Test
    void depthIsBestPriceFirst() {
        loadPart0Book();
        long[] prices = new long[10];
        long[] qtys = new long[10];
        int[] counts = new int[10];

        assertEquals(2, book.depth(SELL, 2, prices, qtys, counts));
        assertEquals(px("150.01"), prices[0]);
        assertEquals(px("150.02"), prices[1]);
        assertEquals(200, qtys[0]);
        assertEquals(400, qtys[1]);
        assertEquals(1, counts[0]);

        assertEquals(3, book.depth(BUY, 10, prices, qtys, null));
        assertEquals(px("150.00"), prices[0]);
        assertEquals(px("149.99"), prices[1]);
        assertEquals(px("149.98"), prices[2]);
    }

    // ---------------------------------------------------------------- matching

    @Test
    void part0SweepFillsAtRestingPricesWithPriceImprovement() {
        loadPart0Book();

        assertEquals(ACCEPTED, book.addLimitOrder(1, BUY, px("150.03"), 500));

        assertEquals(List.of(
                trade(1, 103, "150.01", 200, BUY),
                trade(1, 102, "150.02", 300, BUY)), trades.drain());
        assertEquals(px("150.02"), book.bestAsk());
        assertEquals(100, book.askQtyAt(px("150.02")));
        assertEquals(100, book.restingQty(102));
        assertFalse(book.contains(103));
        assertFalse(book.contains(1), "a fully filled aggressor never rests");
        assertEquals(px("150.00"), book.bestBid());
    }

    @Test
    void part0LargeBuySweepsAllAsksAndRestsTheRemainder() {
        loadPart0Book();

        book.addLimitOrder(1, BUY, px("150.03"), 5000);

        assertEquals(List.of(
                trade(1, 103, "150.01", 200, BUY),
                trade(1, 102, "150.02", 400, BUY),
                trade(1, 101, "150.03", 900, BUY)), trades.drain());
        assertEquals(3500, book.restingQty(1));
        assertEquals(px("150.03"), book.bestBid());
        assertEquals(Book.NO_ASK, book.bestAsk());
    }

    @Test
    void exactFillRemovesBothOrders() {
        book.addLimitOrder(1, SELL, px("150.00"), 300);
        book.addLimitOrder(2, BUY, px("150.00"), 300);

        assertEquals(List.of(trade(2, 1, "150.00", 300, BUY)), trades.drain());
        assertEquals(0, book.orderCount());
        assertEquals(Book.NO_BID, book.bestBid());
        assertEquals(Book.NO_ASK, book.bestAsk());
    }

    @Test
    void partiallyFilledRestingOrderKeepsItsPlaceAtTheFront() {
        book.addLimitOrder(1, SELL, px("150.00"), 500);
        book.addLimitOrder(2, SELL, px("150.00"), 100);

        book.addLimitOrder(3, BUY, px("150.00"), 200);
        assertEquals(List.of(trade(3, 1, "150.00", 200, BUY)), trades.drain());
        assertEquals(300, book.restingQty(1));
        assertArrayEquals(new long[] {1, 2}, book.queueAt(SELL, px("150.00")));

        book.addLimitOrder(4, BUY, px("150.00"), 350);
        assertEquals(List.of(
                trade(4, 1, "150.00", 300, BUY),
                trade(4, 2, "150.00", 50, BUY)), trades.drain());
        assertArrayEquals(new long[] {2}, book.queueAt(SELL, px("150.00")));
        assertEquals(50, book.restingQty(2));
    }

    @Test
    void sameLevelFillsFirstInFirstOut() {
        book.addLimitOrder(1, SELL, px("150.00"), 100);
        book.addLimitOrder(2, SELL, px("150.00"), 100);
        book.addLimitOrder(3, SELL, px("150.00"), 100);

        book.addLimitOrder(10, BUY, px("150.00"), 250);

        assertEquals(List.of(
                trade(10, 1, "150.00", 100, BUY),
                trade(10, 2, "150.00", 100, BUY),
                trade(10, 3, "150.00", 50, BUY)), trades.drain());
    }

    @Test
    void betterPriceBeatsEarlierArrival() {
        book.addLimitOrder(1, SELL, px("150.02"), 100);
        book.addLimitOrder(2, SELL, px("150.01"), 100);

        book.addLimitOrder(3, BUY, px("150.02"), 150);

        assertEquals(List.of(
                trade(3, 2, "150.01", 100, BUY),
                trade(3, 1, "150.02", 50, BUY)), trades.drain());
    }

    @Test
    void sellAggressorSweepsBidsDownwardAndRestsRemainder() {
        book.addLimitOrder(1, BUY, px("150.00"), 100);
        book.addLimitOrder(2, BUY, px("149.99"), 100);
        book.addLimitOrder(3, BUY, px("149.98"), 100);

        book.addLimitOrder(4, SELL, px("149.99"), 250);

        assertEquals(List.of(
                trade(4, 1, "150.00", 100, SELL),
                trade(4, 2, "149.99", 100, SELL)), trades.drain());
        assertEquals(50, book.restingQty(4));
        assertEquals(px("149.98"), book.bestBid());
        assertEquals(px("149.99"), book.bestAsk());
    }

    @Test
    void marketOrderSweepsAndDiscardsTheUnfilledRest() {
        loadPart0Book();

        assertEquals(ACCEPTED, book.addMarketOrder(1, BUY, 2000));

        assertEquals(List.of(
                trade(1, 103, "150.01", 200, BUY),
                trade(1, 102, "150.02", 400, BUY),
                trade(1, 101, "150.03", 900, BUY)), trades.drain());
        assertFalse(book.contains(1));
        assertEquals(Book.NO_ASK, book.bestAsk());
        assertEquals(px("150.00"), book.bestBid());
    }

    @Test
    void marketOrderAgainstEmptySideDoesNothing() {
        book.addLimitOrder(1, BUY, px("150.00"), 100);

        assertEquals(ACCEPTED, book.addMarketOrder(2, BUY, 100));

        assertTrue(trades.isEmpty());
        assertEquals(1, book.orderCount());
    }

    // ---------------------------------------------------------------- cancel and reduce

    @Test
    void cancelFromMiddleOfQueuePreservesTheOthersOrder() {
        book.addLimitOrder(1, BUY, px("150.00"), 100);
        book.addLimitOrder(2, BUY, px("150.00"), 100);
        book.addLimitOrder(3, BUY, px("150.00"), 100);

        assertTrue(book.cancel(2));

        assertArrayEquals(new long[] {1, 3}, book.queueAt(BUY, px("150.00")));
        assertEquals(200, book.bidQtyAt(px("150.00")));
        book.addLimitOrder(4, SELL, px("150.00"), 150);
        assertEquals(List.of(
                trade(4, 1, "150.00", 100, SELL),
                trade(4, 3, "150.00", 50, SELL)), trades.drain());
    }

    @Test
    void cancellingTheLastOrderAtTheTouchMovesTheTouch() {
        book.addLimitOrder(1, BUY, px("150.00"), 100);
        book.addLimitOrder(2, BUY, px("149.99"), 100);

        book.cancel(1);

        assertEquals(px("149.99"), book.bestBid());
        assertEquals(1, book.levelCount(BUY));
    }

    @Test
    void cancelOfUnknownOrGoneOrderIsANoOp() {
        assertFalse(book.cancel(42));

        book.addLimitOrder(1, BUY, px("150.00"), 100);
        assertTrue(book.cancel(1));
        assertFalse(book.cancel(1), "second cancel");

        book.addLimitOrder(2, SELL, px("150.00"), 100);
        book.addLimitOrder(3, BUY, px("150.00"), 100);
        assertFalse(book.cancel(2), "fully filled order");
    }

    @Test
    void reduceKeepsQueuePosition() {
        book.addLimitOrder(1, BUY, px("150.00"), 300);
        book.addLimitOrder(2, BUY, px("150.00"), 100);

        assertTrue(book.reduce(1, 200));

        assertEquals(100, book.restingQty(1));
        assertEquals(200, book.bidQtyAt(px("150.00")));
        assertArrayEquals(new long[] {1, 2}, book.queueAt(BUY, px("150.00")));
        book.addLimitOrder(3, SELL, px("150.00"), 100);
        assertEquals(List.of(trade(3, 1, "150.00", 100, SELL)), trades.drain());
    }

    @Test
    void reduceByAtLeastTheRemainingQuantityRemovesTheOrder() {
        book.addLimitOrder(1, BUY, px("150.00"), 300);

        assertFalse(book.reduce(1, 0));
        assertFalse(book.reduce(42, 10));
        assertTrue(book.reduce(1, 500));

        assertFalse(book.contains(1));
        assertEquals(Book.NO_BID, book.bestBid());
    }

    // ---------------------------------------------------------------- book-builder mode

    @Test
    void addRestingOrderNeverMatches() {
        book.addLimitOrder(1, SELL, px("150.01"), 100);

        assertEquals(ACCEPTED, book.addRestingOrder(2, BUY, px("150.01"), 100));

        assertTrue(trades.isEmpty());
        assertTrue(book.isCrossed(), "book-builder mode trusts the feed");
    }

    @Test
    void executeReportsTradeAtRestingPriceWithUnknownAggressor() {
        book.addRestingOrder(1, SELL, px("150.01"), 300);

        assertTrue(book.execute(1, 100));
        assertEquals(List.of(new Trade(TradeListener.UNKNOWN_ID, 1, px("150.01"), 100, BUY)), trades.drain());
        assertEquals(200, book.restingQty(1));

        assertTrue(book.execute(1, 200));
        assertFalse(book.contains(1));
        assertEquals(Book.NO_ASK, book.bestAsk());
    }

    @Test
    void executeRejectsImpossibleQuantities() {
        book.addRestingOrder(1, SELL, px("150.01"), 300);

        assertFalse(book.execute(1, 0));
        assertFalse(book.execute(1, 301));
        assertFalse(book.execute(42, 10));

        assertTrue(trades.isEmpty());
        assertEquals(300, book.restingQty(1));
    }

    @Test
    void replaceLosesTimePriority() {
        book.addRestingOrder(1, BUY, px("150.00"), 100);
        book.addRestingOrder(2, BUY, px("150.00"), 100);

        assertEquals(ACCEPTED, book.replace(1, 11, px("150.00"), 100));

        assertArrayEquals(new long[] {2, 11}, book.queueAt(BUY, px("150.00")));
        assertFalse(book.contains(1));
    }

    @Test
    void replaceCanChangePriceAndSizeAndKeepsTheSide() {
        book.addRestingOrder(1, BUY, px("150.00"), 100);

        assertEquals(ACCEPTED, book.replace(1, 11, px("149.99"), 50));

        assertEquals(0, book.bidQtyAt(px("150.00")));
        assertEquals(50, book.bidQtyAt(px("149.99")));
        assertEquals(px("149.99"), book.bestBid());
    }

    @Test
    void rejectedReplaceChangesNothing() {
        book.addRestingOrder(1, BUY, px("150.00"), 100);
        book.addRestingOrder(2, BUY, px("149.99"), 100);

        assertEquals(REJECTED_UNKNOWN_ID, book.replace(42, 43, px("150.00"), 100));
        assertEquals(REJECTED_DUP_ID, book.replace(1, 2, px("150.00"), 100));
        assertEquals(REJECTED_QTY, book.replace(1, 11, px("150.00"), 0));
        assertEquals(REJECTED_PRICE, book.replace(1, 11, px("150.005"), 100));

        assertArrayEquals(new long[] {1}, book.queueAt(BUY, px("150.00")));
        assertEquals(2, book.orderCount());
    }

    // ---------------------------------------------------------------- validation

    @Test
    void rejectsInvalidNewOrdersWithoutChangingTheBook() {
        book.addLimitOrder(1, BUY, px("150.00"), 100);

        assertEquals(REJECTED_SIDE, book.addLimitOrder(2, (byte) 7, px("150.00"), 100));
        assertEquals(REJECTED_QTY, book.addLimitOrder(2, BUY, px("150.00"), 0));
        assertEquals(REJECTED_QTY, book.addLimitOrder(2, BUY, px("150.00"), -5));
        assertEquals(REJECTED_PRICE, book.addLimitOrder(2, BUY, 0, 100));
        assertEquals(REJECTED_PRICE, book.addLimitOrder(2, BUY, -100, 100));
        assertEquals(REJECTED_PRICE, book.addLimitOrder(2, BUY, px("150.005"), 100), "off tick");
        assertEquals(REJECTED_DUP_ID, book.addLimitOrder(1, SELL, px("150.00"), 100));

        assertEquals(REJECTED_SIDE, book.addMarketOrder(2, (byte) -1, 100));
        assertEquals(REJECTED_QTY, book.addMarketOrder(2, SELL, 0));
        assertEquals(REJECTED_DUP_ID, book.addMarketOrder(1, SELL, 100));

        assertEquals(REJECTED_PRICE, book.addRestingOrder(2, SELL, px("150.005"), 100));
        assertEquals(REJECTED_DUP_ID, book.addRestingOrder(1, SELL, px("151.00"), 100));

        assertTrue(trades.isEmpty());
        assertEquals(1, book.orderCount());
        assertEquals(100, book.restingQty(1));
    }

    @Test
    void idOfAnOrderNoLongerInTheBookMayBeReused() {
        book.addLimitOrder(1, BUY, px("150.00"), 100);
        book.cancel(1);

        assertEquals(ACCEPTED, book.addLimitOrder(1, SELL, px("151.00"), 100));
    }
}
