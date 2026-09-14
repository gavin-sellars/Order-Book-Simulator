package obs.workload;

import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.ref.RefOrderBook;
import obs.testutil.TradeRecorder;
import org.junit.jupiter.api.Test;

import static obs.testutil.BookAssertions.assertSameBook;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTapeTest {

    private static final MessageTape TAPE = MessageTape.generate(Workload.SEED, 100_000);

    @Test
    void replayingTheWholeTapeRestoresThePrefilledBookExactly() {
        OrderBook fresh = Workload.newFastBook(TradeListener.NONE);
        Workload.prefill(fresh);
        OrderBook replayed = Workload.newFastBook(TradeListener.NONE);
        Workload.prefill(replayed);

        for (int cycle = 1; cycle <= 3; cycle++) {
            for (int i = 0; i < TAPE.length(); i++) TAPE.apply(replayed, i);

            assertSameBook(fresh, replayed, Workload.LEVELS, "after cycle " + cycle);
            BookValidator.validate(replayed, false);
            for (int p = 0; p < Workload.PREFILL_ORDERS; p += 2 * Workload.PREFILL_ORDERS_PER_LEVEL) {
                byte side = Workload.prefillSide(p);
                long price = Workload.prefillPrice(p);
                assertArrayEquals(fresh.queueAt(side, price), replayed.queueAt(side, price), "queue order at " + price);
            }
        }
    }

    @Test
    void fastAndReferenceBooksAgreeOnEveryMessage() {
        TradeRecorder fastTrades = new TradeRecorder();
        TradeRecorder refTrades = new TradeRecorder();
        OrderBook fast = Workload.newFastBook(fastTrades);
        RefOrderBook ref = Workload.newRefBook(refTrades);
        Workload.prefill(fast);
        Workload.prefill(ref);
        long traded = 0;

        for (int i = 0; i < TAPE.length(); i++) {
            assertEquals(TAPE.apply(ref, i), TAPE.apply(fast, i), "message " + i);
            var expected = refTrades.drain();
            assertEquals(expected, fastTrades.drain(), "message " + i);
            traded += expected.size();
        }
        assertTrue(traded > 1000, "the tape should trade regularly, traded " + traded);
    }

    @Test
    void flowIsCancelHeavy() {
        int adds = TAPE.count(MessageTape.ADD) + TAPE.count(MessageTape.CROSS);
        int cancels = TAPE.count(MessageTape.CANCEL);
        int crosses = TAPE.count(MessageTape.CROSS);

        assertTrue(cancels > 10 * crosses, "cancels " + cancels + " vs crossing orders " + crosses);
        assertTrue(cancels >= adds - 1, "every added order is eventually cancelled or cleaned up");
    }

    @Test
    void sameSeedGivesTheSameTapeAndDifferentSeedsDiffer() {
        MessageTape again = MessageTape.generate(Workload.SEED, 100_000);
        assertEquals(TAPE.length(), again.length());
        for (int i = 0; i < TAPE.length(); i++) {
            assertEquals(TAPE.type(i), again.type(i));
            assertEquals(TAPE.side(i), again.side(i));
            assertEquals(TAPE.id(i), again.id(i));
            assertEquals(TAPE.price(i), again.price(i));
            assertEquals(TAPE.qty(i), again.qty(i));
        }

        MessageTape other = MessageTape.generate(Workload.SEED + 1, 100_000);
        boolean differs = other.length() != TAPE.length();
        for (int i = 0; !differs && i < TAPE.length(); i++) differs = TAPE.price(i) != other.price(i);
        assertFalse(!differs, "a different seed should give a different tape");
    }
}
