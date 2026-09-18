package obs.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static obs.core.OrderResult.ACCEPTED;
import static obs.core.OrderResult.REJECTED_PRICE;
import static obs.core.Side.BUY;
import static obs.core.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the shared book scenarios against the fast book, plus what only the fast book has: a fixed ladder and pool. */
class OrderBookTest extends BookContractTest {

    private static final long BASE = Prices.parse("100.00");
    private static final int LEVELS = 10_000;          // $100.00 to $199.99

    private OrderBook fast;

    /** Overridden by a subclass to run every test again with the other touch search. */
    OrderBook.TouchSearch touchSearch() {
        return OrderBook.TouchSearch.BITSET;
    }

    @Override
    protected Book newBook(TradeListener listener) {
        fast = new OrderBook(BASE, Prices.CENT, LEVELS, 1024, listener, touchSearch());
        return fast;
    }

    /** Every test, including all the inherited ones, must leave the structure intact. */
    @AfterEach
    void bookIsStructurallyValid() {
        BookValidator.validate(fast, true);
    }

    @Test
    void rejectsPricesOffTheLadder() {
        assertEquals(REJECTED_PRICE, book.addLimitOrder(1, BUY, px("99.99"), 100));
        assertEquals(REJECTED_PRICE, book.addLimitOrder(1, BUY, px("200.00"), 100));
        assertEquals(REJECTED_PRICE, book.addRestingOrder(1, SELL, Long.MAX_VALUE, 100));
        assertEquals(REJECTED_PRICE, book.addRestingOrder(1, SELL, Long.MIN_VALUE, 100));

        assertEquals(0, book.bidQtyAt(px("99.99")));
        assertEquals(0, book.askQtyAt(Long.MAX_VALUE));
        assertEquals(0, book.orderCount());
    }

    @Test
    void farLevelsHoldStubQuotesAndSubPennyPricesOffTheLadder() {
        fast = new OrderBook(BASE, Prices.CENT, LEVELS, 1024, TradeListener.NONE, touchSearch(), 1, 4);
        long stubBid = px("0.0001");
        long stubAsk = px("199999.00");
        long between = px("150.0050");

        assertEquals(ACCEPTED, fast.addRestingOrder(1, BUY, stubBid, 100));
        assertEquals(ACCEPTED, fast.addRestingOrder(2, SELL, stubAsk, 100));
        assertEquals(stubBid, fast.bestBid(), "a far bid is the touch when the ladder is empty");
        assertEquals(stubAsk, fast.bestAsk());

        assertEquals(ACCEPTED, fast.addRestingOrder(3, BUY, px("150.00"), 200));
        assertEquals(ACCEPTED, fast.addRestingOrder(4, BUY, between, 300));
        assertEquals(between, fast.bestBid(), "a far level between ladder levels still ranks by price");
        assertEquals(REJECTED_PRICE, fast.addLimitOrder(5, SELL, between, 100), "matching mode stays on the ladder");

        long[] prices = new long[4];
        long[] qtys = new long[4];
        assertEquals(3, fast.depth(BUY, 4, prices, qtys, null));
        assertEquals(between, prices[0]);
        assertEquals(px("150.00"), prices[1]);
        assertEquals(stubBid, prices[2]);
        assertEquals(300, fast.bidQtyAt(between));
        assertEquals(between, fast.priceOf(4));
        assertEquals(2, fast.farLevelCount(BUY));

        // A market sell takes the far level first, then the ladder, then the stub.
        assertEquals(ACCEPTED, fast.addMarketOrder(6, SELL, 550));
        assertEquals(stubBid, fast.bestBid());
        assertEquals(50, fast.restingQty(1));
        assertEquals(1, fast.farLevelCount(BUY));

        assertEquals(ACCEPTED, fast.replace(2, 7, px("0.50"), 100));
        assertEquals(px("0.50"), fast.bestAsk());
        assertFalse(fast.isCrossed());
        assertTrue(fast.execute(7, 100));
        assertEquals(Book.NO_ASK, fast.bestAsk());
        assertEquals(0, fast.farLevelCount(SELL));
        BookValidator.validate(fast, true);
    }

    @Test
    void farLevelsFailLoudlyWhenFull() {
        fast = new OrderBook(BASE, Prices.CENT, LEVELS, 1024, TradeListener.NONE, touchSearch(), Prices.CENT, 1);
        assertEquals(ACCEPTED, fast.addRestingOrder(1, BUY, px("1.00"), 100));
        assertEquals(ACCEPTED, fast.addRestingOrder(2, BUY, px("1.00"), 100), "same far price, same level");
        assertThrows(IllegalStateException.class, () -> fast.addRestingOrder(3, BUY, px("2.00"), 100));
        assertEquals(REJECTED_PRICE, fast.addRestingOrder(4, BUY, px("2.005"), 100), "still off the price tick");
    }

    @Test
    void ordersAtBothEndsOfTheLadderWork() {
        assertEquals(px("100.00"), fast.minPrice());
        assertEquals(px("199.99"), fast.maxPrice());
        assertEquals(ACCEPTED, book.addLimitOrder(1, BUY, px("100.00"), 100));
        assertEquals(ACCEPTED, book.addLimitOrder(2, SELL, px("199.99"), 100));
        assertEquals(px("100.00"), book.bestBid());
        assertEquals(px("199.99"), book.bestAsk());

        book.addMarketOrder(3, BUY, 100);
        book.addMarketOrder(4, SELL, 100);

        assertEquals(2, trades.drain().size());
        assertEquals(Book.NO_BID, book.bestBid());
        assertEquals(Book.NO_ASK, book.bestAsk());
    }

    @Test
    void touchFindsTheNextLevelAcrossBitsetWordBoundaries() {
        // Ladder indexes 0, 63, 64, 5000 and 9999.
        String[] prices = {"100.00", "100.63", "100.64", "150.00", "199.99"};

        for (int i = 0; i < prices.length; i++) book.addLimitOrder(i + 1, BUY, px(prices[i]), 100);
        for (int i = prices.length - 1; i >= 0; i--) {
            assertEquals(px(prices[i]), book.bestBid());
            book.cancel(i + 1);
        }
        assertEquals(Book.NO_BID, book.bestBid());

        for (int i = 0; i < prices.length; i++) book.addLimitOrder(i + 11, SELL, px(prices[i]), 100);
        for (int i = 0; i < prices.length; i++) {
            assertEquals(px(prices[i]), book.bestAsk());
            book.cancel(i + 11);
        }
        assertEquals(Book.NO_ASK, book.bestAsk());
    }

    @Test
    void arrivalSequenceFollowsTimePriority() {
        book.addLimitOrder(1, BUY, px("150.00"), 300);
        book.addLimitOrder(2, BUY, px("150.00"), 100);
        long first = fast.arrivalSeq(1);
        assertTrue(first < fast.arrivalSeq(2));

        book.reduce(1, 50);
        book.addLimitOrder(3, SELL, px("150.00"), 50);
        assertEquals(first, fast.arrivalSeq(1), "reduce and partial fill keep priority");

        book.replace(1, 11, px("150.00"), 200);
        assertTrue(fast.arrivalSeq(11) > fast.arrivalSeq(2), "replace goes to the back");
        assertEquals(-1, fast.arrivalSeq(1));
    }

    @Test
    void reportsPriceSideAndArrivalPointOfRestingOrders() {
        long before = fast.nextArrivalSeq();
        book.addLimitOrder(1, BUY, px("150.00"), 100);
        book.addLimitOrder(2, SELL, px("150.02"), 100);

        assertEquals(px("150.00"), fast.priceOf(1));
        assertEquals(BUY, fast.sideOf(1));
        assertEquals(px("150.02"), fast.priceOf(2));
        assertEquals(SELL, fast.sideOf(2));
        assertTrue(fast.arrivalSeq(1) >= before && fast.arrivalSeq(2) < fast.nextArrivalSeq());

        book.cancel(1);
        assertEquals(-1, fast.priceOf(1));
        assertEquals(-1, fast.sideOf(1));
        assertEquals(-1, fast.priceOf(99));
    }

    @Test
    void freedSlotsAreReused() {
        OrderBook small = new OrderBook(BASE, Prices.CENT, LEVELS, 4, TradeListener.NONE);

        for (long id = 1; id <= 100_000; id++) {
            small.addLimitOrder(id, BUY, px("150.00"), 100);
            if (id % 2 == 0) small.addLimitOrder(-id, SELL, px("150.00"), 100);   // fills the bid
            else small.cancel(id);
        }

        assertEquals(0, small.orderCount());
        BookValidator.validate(small, false);
    }

    @Test
    void exhaustedPoolFailsLoudlyWithoutCorruptingTheBook() {
        OrderBook small = new OrderBook(BASE, Prices.CENT, LEVELS, 2, TradeListener.NONE);
        small.addLimitOrder(1, BUY, px("150.00"), 100);
        small.addLimitOrder(2, BUY, px("149.99"), 100);

        assertThrows(IllegalStateException.class, () -> small.addLimitOrder(3, BUY, px("149.98"), 100));
        BookValidator.validate(small, false);

        // An order that fills completely needs no slot.
        assertEquals(ACCEPTED, small.addLimitOrder(4, SELL, px("150.00"), 100));
        assertEquals(1, small.orderCount());
        assertFalse(small.contains(3));
        BookValidator.validate(small, false);
    }

    @Test
    void rejectsImpossibleConfigurations() {
        TradeListener none = TradeListener.NONE;
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(BASE, 0, LEVELS, 16, none));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(0, Prices.CENT, LEVELS, 16, none));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(BASE + 1, Prices.CENT, LEVELS, 16, none));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(BASE, Prices.CENT, 0, 16, none));
        assertThrows(IllegalArgumentException.class, () -> new OrderBook(BASE, Prices.CENT, LEVELS, 0, none));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderBook(Long.MAX_VALUE / 100 * 100, Prices.CENT, LEVELS, 16, none));
        assertThrows(NullPointerException.class, () -> new OrderBook(BASE, Prices.CENT, LEVELS, 16, null));
    }
}
