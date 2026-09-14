package obs.bench;

import obs.core.Book;
import obs.core.Prices;
import obs.core.Side;
import obs.workload.Workload;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Single operations on the fast and reference books, against the prefilled book from
 * {@link Workload} (50 levels a side, 10 orders of 100 per level, $150.00 mid).
 *
 * Every benchmark puts the book back the way it found it, so however many times an operation
 * runs, it always sees the same steady state. (The guide's version keeps adding or crossing and
 * ends up measuring a different book, or running out of pool slots.)
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class OrderBookBenchmark {

    private static final long CENT = Prices.CENT;
    private static final long DEEP_PRICE = Workload.MID - 60 * CENT;       // below the prefilled bids
    private static final int DEEP_ORDERS = 1000;
    private static final long DEEP_FIRST_ID = 500_000L;
    private static final int LEVEL_QTY = Workload.PREFILL_ORDERS_PER_LEVEL * Workload.PREFILL_QTY;

    @Param({"fast", "ref"})
    public String impl;

    /** Fast book only: the pool, and so the id map, are sized for this many resting orders. */
    @Param({"1048576"})
    public int poolCapacity;

    private Book book;
    private long nextId = 10_000_000L;
    private long tradedQty;
    private final int[] deepPicks = new int[1 << 16];
    private int pickCursor;

    @Setup(Level.Trial)
    public void setUp() {
        book = Workload.newBook(impl, (aggressorId, restingId, price, qty, side) -> tradedQty += qty, poolCapacity);
        Workload.prefill(book);
        for (int k = 0; k < DEEP_ORDERS; k++) book.addRestingOrder(DEEP_FIRST_ID + k, Side.BUY, DEEP_PRICE, 100);

        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < deepPicks.length; i++) deepPicks[i] = random.nextInt(DEEP_ORDERS);
    }

    /** Join the back of a busy level three ticks from the touch, then cancel from the back. */
    @Benchmark
    public boolean addPassiveThenCancel() {
        long id = nextId++;
        book.addLimitOrder(id, Side.BUY, Workload.MID - 3 * CENT, 100);
        return book.cancel(id);
    }

    /** Cancel a random order from a queue of 1,000 and rejoin at the back. O(1) for fast, O(queue) for ref. */
    @Benchmark
    public boolean cancelFromDeepQueueAndRejoin() {
        long id = DEEP_FIRST_ID + deepPicks[pickCursor++ & (deepPicks.length - 1)];
        boolean cancelled = book.cancel(id);
        book.addRestingOrder(id, Side.BUY, DEEP_PRICE, 100);
        return cancelled;
    }

    /** Take exactly the front order at the best ask, then replace it at the back of the level. */
    @Benchmark
    public long crossOneOrderAndReplenish() {
        book.addLimitOrder(nextId++, Side.BUY, Workload.MID + CENT, Workload.PREFILL_QTY);
        book.addLimitOrder(nextId++, Side.SELL, Workload.MID + CENT, Workload.PREFILL_QTY);
        return tradedQty;
    }

    /** Market buy through all of the five best ask levels (50 fills), then rebuild them (50 adds). */
    @Benchmark
    public long sweepFiveLevelsAndReplenish() {
        book.addMarketOrder(nextId++, Side.BUY, 5 * LEVEL_QTY);
        for (int level = 1; level <= 5; level++) {
            for (int k = 0; k < Workload.PREFILL_ORDERS_PER_LEVEL; k++) {
                book.addLimitOrder(nextId++, Side.SELL, Workload.MID + level * CENT, Workload.PREFILL_QTY);
            }
        }
        return tradedQty;
    }
}
