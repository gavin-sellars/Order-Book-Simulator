package obs.core;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import obs.ref.RefLinkedOrderBook;
import obs.ref.RefOrderBook;
import obs.testutil.TradeRecorder;

import java.util.Arrays;
import java.util.List;

import static obs.testutil.BookAssertions.assertSameBook;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential testing (Part 8 of the guide): random message sequences go to both the fast book
 * and the reference book, and after every message the result code, the trades, and the whole
 * book must be identical. The fast book's structure is also validated after every message.
 * When jqwik finds a mismatch it shrinks the sequence to a minimal reproduction.
 */
class DifferentialProperties {

    private static final long MID = Prices.parse("150.00");
    private static final long BASE = Prices.parse("149.00");
    private static final int LEVELS = 200;                   // $149.00 to $150.99
    private static final int POOL = 4096;

    private static final long WINDOW_BASE = Prices.parse("149.95");
    private static final int WINDOW_LEVELS = 10;             // $149.95 to $150.04
    private static final long HALF_CENT = Prices.CENT / 2;

    /** idPick meaning "a brand-new id". Any other value picks a recently issued id, which may be dead. */
    private static final int FRESH = -1;

    sealed interface Op permits Limit, Market, Rest, Cancel, Reduce, Execute, Replace {}
    record Limit(byte side, long price, int qty, int idPick) implements Op {}
    record Market(byte side, int qty, int idPick) implements Op {}
    record Rest(byte side, long price, int qty, int idPick) implements Op {}
    record Cancel(int idPick) implements Op {}
    record Reduce(int idPick, int by) implements Op {}
    record Execute(int idPick, int qty) implements Op {}
    record Replace(int idPick, int newIdPick, long price, int qty) implements Op {}

    /** Matching mode only: the book must also never cross. */
    @Property(tries = 1000)
    void matchingSessionsAgreeWithReference(@ForAll("matchingSessions") List<Op> ops,
                                            @ForAll OrderBook.TouchSearch touchSearch) {
        runSession(ops, touchSearch, false);
    }

    /** Every operation mixed, including book-builder operations that are allowed to cross the book. */
    @Property(tries = 1000)
    void mixedSessionsAgreeWithReference(@ForAll("mixedSessions") List<Op> ops,
                                         @ForAll OrderBook.TouchSearch touchSearch) {
        runSession(ops, touchSearch, true);
    }

    /**
     * A ten-level ladder ($149.95 to $150.04) with half-cent prices allowed, so most resting orders
     * land on far levels: below or above the ladder, between two of its levels, or at the extremes of
     * the ITCH price range. Limit orders stay on the ladder, as matching mode requires, and trade
     * against far orders too.
     */
    @Property(tries = 1000)
    void farLevelSessionsAgreeWithReference(@ForAll("farSessions") List<Op> ops,
                                            @ForAll OrderBook.TouchSearch touchSearch) {
        TradeRecorder fastTrades = new TradeRecorder();
        OrderBook fast = new OrderBook(WINDOW_BASE, Prices.CENT, WINDOW_LEVELS, POOL, fastTrades, touchSearch,
                HALF_CENT, 64);
        runSession(ops, fast, fastTrades, HALF_CENT, true);
    }

    private static void runSession(List<Op> ops, OrderBook.TouchSearch touchSearch, boolean allowCrossed) {
        TradeRecorder fastTrades = new TradeRecorder();
        OrderBook fast = new OrderBook(BASE, Prices.CENT, LEVELS, POOL, fastTrades, touchSearch);
        runSession(ops, fast, fastTrades, Prices.CENT, allowCrossed);
    }

    /** The linked-list variant of the reference book, used to measure the O(1) cancel on its own, is held to the same standard. */
    private static void runSession(List<Op> ops, OrderBook fast, TradeRecorder fastTrades, long refTick,
                                   boolean allowCrossed) {
        TradeRecorder refTrades = new TradeRecorder();
        TradeRecorder linkedTrades = new TradeRecorder();
        RefOrderBook ref = new RefOrderBook(refTick, refTrades);
        RefLinkedOrderBook linked = new RefLinkedOrderBook(refTick, linkedTrades);
        Ids ids = new Ids();

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            long[] resolved = ids.resolve(op);
            String step = "step " + i + " " + op + " ids=" + Arrays.toString(resolved);

            int expected = apply(ref, op, resolved);
            assertEquals(expected, apply(fast, op, resolved), step + ": result");
            assertEquals(expected, apply(linked, op, resolved), step + ": linked result");
            var expectedTrades = refTrades.drain();
            assertEquals(expectedTrades, fastTrades.drain(), step + ": trades");
            assertEquals(expectedTrades, linkedTrades.drain(), step + ": linked trades");
            assertSameBook(ref, fast, LEVELS, step);
            assertSameBook(ref, linked, LEVELS, step + " (linked)");
            BookValidator.validate(fast, allowCrossed);
        }
    }

    /** Booleans become 1 or 0 so every operation's outcome compares as an int. */
    private static int apply(Book book, Op op, long[] ids) {
        return switch (op) {
            case Limit o -> book.addLimitOrder(ids[0], o.side(), o.price(), o.qty());
            case Market o -> book.addMarketOrder(ids[0], o.side(), o.qty());
            case Rest o -> book.addRestingOrder(ids[0], o.side(), o.price(), o.qty());
            case Cancel o -> book.cancel(ids[0]) ? 1 : 0;
            case Reduce o -> book.reduce(ids[0], o.by()) ? 1 : 0;
            case Execute o -> book.execute(ids[0], o.qty()) ? 1 : 0;
            case Replace o -> book.replace(ids[0], ids[1], o.price(), o.qty());
        };
    }

    /** Turns id picks into concrete ids, once per operation, so both books receive identical messages. */
    private static final class Ids {
        private long next = 1;

        long forNew(int pick) {
            return pick == FRESH ? next++ : existing(pick);
        }

        /** One of the last 32 ids issued (usually still resting), or the next unissued id (never known). */
        long existing(int pick) {
            long back = pick % (Math.min(next, 32) + 1);
            return next - back;
        }

        long[] resolve(Op op) {
            return switch (op) {
                case Limit o -> new long[] {forNew(o.idPick()), 0};
                case Market o -> new long[] {forNew(o.idPick()), 0};
                case Rest o -> new long[] {forNew(o.idPick()), 0};
                case Cancel o -> new long[] {existing(o.idPick()), 0};
                case Reduce o -> new long[] {existing(o.idPick()), 0};
                case Execute o -> new long[] {existing(o.idPick()), 0};
                case Replace o -> {
                    long old = existing(o.idPick());
                    yield new long[] {old, forNew(o.newIdPick())};
                }
            };
        }
    }

    // ---------------------------------------------------------------- generators

    @Provide
    Arbitrary<List<Op>> matchingSessions() {
        return Arbitraries.frequencyOf(
                Tuple.of(8, limit()),
                Tuple.of(1, market()),
                Tuple.of(5, cancel()),
                Tuple.of(2, reduce())).list().ofMaxSize(300);
    }

    @Provide
    Arbitrary<List<Op>> mixedSessions() {
        return Arbitraries.frequencyOf(
                Tuple.of(4, limit()),
                Tuple.of(1, market()),
                Tuple.of(4, rest()),
                Tuple.of(4, cancel()),
                Tuple.of(2, reduce()),
                Tuple.of(3, execute()),
                Tuple.of(2, replace())).list().ofMaxSize(300);
    }

    @Provide
    Arbitrary<List<Op>> farSessions() {
        Arbitrary<Op> windowLimit = Combinators.combine(side(), windowPrice(), qty(), newIdPick()).as(Limit::new);
        Arbitrary<Op> farRest = Combinators.combine(side(), farPrice(), qty(), newIdPick()).as(Rest::new);
        Arbitrary<Op> farReplace = Combinators.combine(existingPick(), newIdPick(), farPrice(), qty()).as(Replace::new);
        return Arbitraries.frequencyOf(
                Tuple.of(3, windowLimit),
                Tuple.of(1, market()),
                Tuple.of(6, farRest),
                Tuple.of(4, cancel()),
                Tuple.of(2, reduce()),
                Tuple.of(3, execute()),
                Tuple.of(3, farReplace)).list().ofMaxSize(300);
    }

    /** On the ten-level ladder. */
    private static Arbitrary<Long> windowPrice() {
        return Arbitraries.integers().between(0, WINDOW_LEVELS - 1).map(t -> WINDOW_BASE + t * Prices.CENT);
    }

    /**
     * Anywhere within ten cents of $150.00 in half-cent steps, so on the ladder, off either end, or
     * between two levels; now and then the ITCH extremes, and now and then invalid.
     */
    private static Arbitrary<Long> farPrice() {
        return Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.integers().between(-20, 20).map(t -> MID + t * HALF_CENT)),
                Tuple.of(2, Arbitraries.of(HALF_CENT, Prices.parse("199999.00"))),
                Tuple.of(1, Arbitraries.of(0L, -HALF_CENT, MID + 1)));
    }

    private static Arbitrary<Op> limit() {
        return Combinators.combine(side(), price(), qty(), newIdPick()).as(Limit::new);
    }

    private static Arbitrary<Op> market() {
        return Combinators.combine(side(), Arbitraries.integers().between(1, 1500), newIdPick()).as(Market::new);
    }

    private static Arbitrary<Op> rest() {
        return Combinators.combine(side(), price(), qty(), newIdPick()).as(Rest::new);
    }

    private static Arbitrary<Op> cancel() {
        return existingPick().map(Cancel::new);
    }

    private static Arbitrary<Op> reduce() {
        return Combinators.combine(existingPick(), Arbitraries.integers().between(-1, 400)).as(Reduce::new);
    }

    private static Arbitrary<Op> execute() {
        return Combinators.combine(existingPick(), Arbitraries.integers().between(-1, 600)).as(Execute::new);
    }

    private static Arbitrary<Op> replace() {
        return Combinators.combine(existingPick(), newIdPick(), price(), qty()).as(Replace::new);
    }

    /** Mostly valid sides, occasionally an invalid one. */
    private static Arbitrary<Byte> side() {
        return Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.of(Side.BUY, Side.SELL)),
                Tuple.of(1, Arbitraries.just((byte) 5)));
    }

    /** Within ten ticks of $150.00 so orders cross often; occasionally off the tick grid. */
    private static Arbitrary<Long> price() {
        return Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.integers().between(-10, 10).map(t -> MID + t * Prices.CENT)),
                Tuple.of(1, Arbitraries.just(MID + Prices.CENT / 2)));
    }

    /** Mostly valid sizes, occasionally zero or negative. */
    private static Arbitrary<Integer> qty() {
        return Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.integers().between(1, 500)),
                Tuple.of(1, Arbitraries.of(0, -1)));
    }

    /** Mostly a fresh id, occasionally one that may already be resting (a duplicate). */
    private static Arbitrary<Integer> newIdPick() {
        return Arbitraries.frequencyOf(
                Tuple.of(15, Arbitraries.just(FRESH)),
                Tuple.of(1, existingPick()));
    }

    private static Arbitrary<Integer> existingPick() {
        return Arbitraries.integers().between(0, 1_000_000);
    }
}
