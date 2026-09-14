package obs.bench;

import obs.core.OrderBook;
import obs.core.Prices;
import obs.core.Side;
import obs.core.TradeListener;
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

import java.util.concurrent.TimeUnit;

/**
 * The worst case for tracking the touch: the best bid empties and the next bid is
 * {@code gapLevels} levels below it. Compares the bitset search with a level-by-level scan.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class TouchSearchBenchmark {

    private static final int TOP_LEVEL = 10_000;
    private static final long TOP_PRICE = Workload.BASE_PRICE + TOP_LEVEL * Prices.CENT;
    private static final long TOP_ID = 2;

    @Param({"BITSET", "LINEAR_SCAN"})
    public OrderBook.TouchSearch touchSearch;

    @Param({"1", "100", "10000"})
    public int gapLevels;

    private OrderBook book;

    @Setup(Level.Trial)
    public void setUp() {
        book = new OrderBook(Workload.BASE_PRICE, Prices.CENT, Workload.LEVELS, 1 << 16, TradeListener.NONE, touchSearch);
        book.addRestingOrder(1, Side.BUY, TOP_PRICE - gapLevels * Prices.CENT, 100);
        book.addRestingOrder(TOP_ID, Side.BUY, TOP_PRICE, 100);
    }

    @Benchmark
    public long cancelBestBidAndRestore() {
        book.cancel(TOP_ID);                    // the touch must find the level gapLevels below
        long next = book.bestBid();
        book.addRestingOrder(TOP_ID, Side.BUY, TOP_PRICE, 100);
        return next;
    }
}
