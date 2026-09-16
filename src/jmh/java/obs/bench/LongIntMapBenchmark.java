package obs.bench;

import obs.mem.LongIntMap;
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

import java.util.HashMap;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * LongIntMap against HashMap&lt;Long, Integer&gt; holding 100,000 order ids. "Churn" adds the next
 * sequential id and removes the oldest, like orders arriving and leaving. "Get" looks up random
 * live ids, like cancels and executions finding their order.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class LongIntMapBenchmark {

    private static final int WINDOW = 100_000;
    private static final int PROBES = 1 << 16;

    /** Only affects the LongIntMap benchmarks. */
    @Param({"MIX", "IDENTITY"})
    public LongIntMap.Hashing hashing;

    /** Sizes the LongIntMap table: the window of 100,000 live keys, or a whole 1M-order pool. Only affects LongIntMap. */
    @Param({"100000", "1048576"})
    public int tableEntries;

    private LongIntMap longIntMap;
    private final HashMap<Long, Integer> hashMap = new HashMap<>(WINDOW * 2);
    private final long[] probeKeys = new long[PROBES];
    private long nextKey;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        longIntMap = new LongIntMap(tableEntries, hashing);
        for (long k = 0; k < WINDOW; k++) {
            longIntMap.put(k, (int) k);
            hashMap.put(k, (int) k);
        }
        nextKey = WINDOW;
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < PROBES; i++) probeKeys[i] = random.nextInt(WINDOW);
    }

    @Benchmark
    public int longIntMapChurn() {
        longIntMap.put(nextKey, (int) (nextKey & Integer.MAX_VALUE));
        return longIntMap.remove(nextKey++ - WINDOW);
    }

    @Benchmark
    public int hashMapChurn() {
        hashMap.put(nextKey, (int) (nextKey & Integer.MAX_VALUE));
        return hashMap.remove(nextKey++ - WINDOW);
    }

    @Benchmark
    public int longIntMapGet() {
        return longIntMap.get(probeKeys[cursor++ & (PROBES - 1)]);
    }

    @Benchmark
    public int hashMapGet() {
        return hashMap.get(probeKeys[cursor++ & (PROBES - 1)]);
    }
}
