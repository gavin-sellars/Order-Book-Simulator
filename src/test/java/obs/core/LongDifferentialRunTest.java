package obs.core;

import obs.ref.RefOrderBook;
import obs.testutil.TradeRecorder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static obs.testutil.BookAssertions.assertSameBook;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One long seeded session of a million messages in matching mode, cancel-heavy like real order
 * flow. The random properties explore many short sessions; this explores a book that has been
 * running for a long time, with slots, map entries and levels reused over and over.
 */
class LongDifferentialRunTest {

    private static final long MID = Prices.parse("150.00");
    private static final long BASE = Prices.parse("149.00");
    private static final int LEVELS = 200;
    private static final int MESSAGES = 1_000_000;

    @Test
    void millionMessageSessionAgreesWithReference() {
        SplittableRandom random = new SplittableRandom(20260914);
        TradeRecorder refTrades = new TradeRecorder();
        TradeRecorder fastTrades = new TradeRecorder();
        RefOrderBook ref = new RefOrderBook(Prices.CENT, refTrades);
        OrderBook fast = new OrderBook(BASE, Prices.CENT, LEVELS, 1 << 16, fastTrades);

        List<Long> candidates = new ArrayList<>();      // ids that were resting when last seen
        long nextId = 1;

        for (int i = 0; i < MESSAGES; i++) {
            int roll = random.nextInt(100);
            byte side = random.nextBoolean() ? Side.BUY : Side.SELL;
            int refResult;
            int fastResult;

            if (roll < 45) {
                long id = nextId++;
                long price = MID + random.nextInt(-10, 11) * Prices.CENT;
                int qty = 1 + random.nextInt(500);
                refResult = ref.addLimitOrder(id, side, price, qty);
                fastResult = fast.addLimitOrder(id, side, price, qty);
                if (fast.contains(id)) candidates.add(id);
            } else if (roll < 50) {
                long id = nextId++;
                int qty = 1 + random.nextInt(1000);
                refResult = ref.addMarketOrder(id, side, qty);
                fastResult = fast.addMarketOrder(id, side, qty);
            } else {
                long id = pickLiveId(candidates, fast, random, roll < 90);
                if (roll < 90) {
                    refResult = ref.cancel(id) ? 1 : 0;
                    fastResult = fast.cancel(id) ? 1 : 0;
                } else {
                    int by = 1 + random.nextInt(200);
                    refResult = ref.reduce(id, by) ? 1 : 0;
                    fastResult = fast.reduce(id, by) ? 1 : 0;
                }
            }

            assertEquals(refResult, fastResult, "message " + i);
            assertEquals(refTrades.drain(), fastTrades.drain(), "message " + i);
            assertEquals(ref.bestBid(), fast.bestBid(), "message " + i);
            assertEquals(ref.bestAsk(), fast.bestAsk(), "message " + i);

            if (i % 1000 == 0) {
                assertSameBook(ref, fast, LEVELS, "message " + i);
                BookValidator.validate(fast, false);
            }
        }

        assertSameBook(ref, fast, LEVELS, "end");
        BookValidator.validate(fast, false);
    }

    /**
     * Picks a random candidate, discarding ones that have since filled, so cancels mostly hit
     * live orders. Returns an id that was never issued when nothing is live.
     */
    private static long pickLiveId(List<Long> candidates, OrderBook book, SplittableRandom random, boolean removing) {
        while (!candidates.isEmpty()) {
            int index = random.nextInt(candidates.size());
            long id = candidates.get(index);
            if (book.contains(id)) {
                if (removing) swapRemove(candidates, index);
                return id;
            }
            swapRemove(candidates, index);
        }
        return 0;
    }

    private static void swapRemove(List<Long> list, int index) {
        int last = list.size() - 1;
        list.set(index, list.get(last));
        list.remove(last);
    }
}
