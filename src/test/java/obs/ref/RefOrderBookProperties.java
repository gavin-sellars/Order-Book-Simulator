package obs.ref;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import obs.core.Prices;
import obs.core.Side;
import obs.testutil.TradeRecorder;
import obs.testutil.TradeRecorder.Trade;

import java.util.List;

import static obs.core.OrderResult.ACCEPTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Random matching-mode sessions against the reference book. Book-builder mode is covered by unit tests. */
class RefOrderBookProperties {

    private static final long MID = Prices.parse("150.00");

    sealed interface Op permits Limit, Market, Cancel, Reduce {}
    record Limit(byte side, int ticksFromMid, int qty) implements Op {}
    record Market(byte side, int qty) implements Op {}
    record Cancel(int pick) implements Op {}
    record Reduce(int pick, int by) implements Op {}

    @Property(tries = 500)
    void matchingNeverCrossesTheBookAndConservesEveryShare(@ForAll("sessions") List<Op> ops) {
        TradeRecorder trades = new TradeRecorder();
        RefOrderBook book = new RefOrderBook(Prices.CENT, trades);

        int maxId = ops.size();
        long[] original = new long[maxId + 1];
        long[] accounted = new long[maxId + 1];     // shares filled or cancelled, per order id
        boolean[] market = new boolean[maxId + 1];
        long nextId = 1;

        for (Op op : ops) {
            switch (op) {
                case Limit l -> {
                    long id = nextId++;
                    long limit = MID + l.ticksFromMid() * Prices.CENT;
                    original[(int) id] = l.qty();
                    assertEquals(ACCEPTED, book.addLimitOrder(id, l.side(), limit, l.qty()));
                    for (Trade t : trades.drain()) {
                        assertTrue(l.side() == Side.BUY ? t.price() <= limit : t.price() >= limit,
                                "trade at " + t.price() + " is outside limit " + limit);
                        account(t, accounted);
                    }
                }
                case Market m -> {
                    long id = nextId++;
                    original[(int) id] = m.qty();
                    market[(int) id] = true;
                    assertEquals(ACCEPTED, book.addMarketOrder(id, m.side(), m.qty()));
                    for (Trade t : trades.drain()) account(t, accounted);
                }
                case Cancel c -> {
                    if (nextId > 1) {
                        long id = c.pick() % (nextId - 1) + 1;
                        int before = book.restingQty(id);
                        assertEquals(before > 0, book.cancel(id));
                        accounted[(int) id] += before;
                    }
                }
                case Reduce r -> {
                    if (nextId > 1) {
                        long id = r.pick() % (nextId - 1) + 1;
                        int before = book.restingQty(id);
                        assertEquals(before > 0, book.reduce(id, r.by()));
                        accounted[(int) id] += Math.min(before, r.by());
                        assertEquals(Math.max(0, before - r.by()), book.restingQty(id));
                    }
                }
            }
            assertFalse(book.isCrossed(), "crossed after " + op);
        }

        long restingTotal = 0;
        int live = 0;
        for (int id = 1; id < nextId; id++) {
            int resting = book.restingQty(id);
            if (market[id]) {
                assertEquals(0, resting, "market order #" + id + " rested");
                assertTrue(accounted[id] <= original[id], "market order #" + id + " overfilled");
            } else {
                assertEquals(original[id], accounted[id] + resting, "shares lost or created on order #" + id);
            }
            restingTotal += resting;
            if (resting > 0) live++;
        }
        assertEquals(live, book.orderCount());
        assertEquals(restingTotal, depthTotal(book, Side.BUY) + depthTotal(book, Side.SELL));
    }

    private static void account(Trade t, long[] accounted) {
        assertTrue(t.qty() > 0);
        accounted[(int) t.aggressorId()] += t.qty();
        accounted[(int) t.restingId()] += t.qty();
    }

    private static long depthTotal(RefOrderBook book, byte side) {
        long[] prices = new long[64];
        long[] qtys = new long[64];
        int n = book.depth(side, 64, prices, qtys, null);
        long sum = 0;
        for (int i = 0; i < n; i++) sum += qtys[i];
        return sum;
    }

    @Provide
    Arbitrary<List<Op>> sessions() {
        Arbitrary<Byte> side = Arbitraries.of(Side.BUY, Side.SELL);
        Arbitrary<Integer> pick = Arbitraries.integers().between(0, 10_000);

        Arbitrary<Op> limit = Combinators.combine(side, Arbitraries.integers().between(-5, 5),
                Arbitraries.integers().between(1, 500)).as(Limit::new);
        Arbitrary<Op> marketOrder = Combinators.combine(side, Arbitraries.integers().between(1, 800)).as(Market::new);
        Arbitrary<Op> cancel = pick.map(Cancel::new);
        Arbitrary<Op> reduce = Combinators.combine(pick, Arbitraries.integers().between(1, 300)).as(Reduce::new);

        return Arbitraries.frequencyOf(
                Tuple.of(6, limit),
                Tuple.of(1, marketOrder),
                Tuple.of(4, cancel),
                Tuple.of(2, reduce)).list().ofMaxSize(300);
    }
}
