package obs.core;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
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

    private static void runSession(List<Op> ops, OrderBook.TouchSearch touchSearch, boolean allowCrossed) {
        TradeRecorder refTrades = new TradeRecorder();
        TradeRecorder fastTrades = new TradeRecorder();
        RefOrderBook ref = new RefOrderBook(Prices.CENT, refTrades);
        OrderBook fast = new OrderBook(BASE, Prices.CENT, LEVELS, POOL, fastTrades, touchSearch);
        Ids ids = new Ids();

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            long[] resolved = ids.resolve(op);
            String step = "step " + i + " " + op + " ids=" + Arrays.toString(resolved);

            assertEquals(apply(ref, op, resolved), apply(fast, op, resolved), step + ": result");
            assertEquals(refTrades.drain(), fastTrades.drain(), step + ": trades");
            assertSameBook(ref, fast, LEVELS, step);
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
