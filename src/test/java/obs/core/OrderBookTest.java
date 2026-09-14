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

    @Override
    protected Book newBook(TradeListener listener) {
        fast = new OrderBook(BASE, Prices.CENT, LEVELS, 1024, listener);
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
