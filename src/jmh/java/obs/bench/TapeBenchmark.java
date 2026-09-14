package obs.bench;

import obs.core.Book;
import obs.core.TradeListener;
import obs.workload.MessageTape;
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
 * The realistic mix: one message at a time from a cancel-heavy {@link MessageTape}, looping
 * forever. The tape restores the book at the end of each pass, so the loop is steady-state.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
public class TapeBenchmark {

    @Param({"fast", "ref"})
    public String impl;

    private MessageTape tape;
    private Book book;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() {
        tape = MessageTape.generate(Workload.SEED, 1_000_000);
        book = Workload.newBook(impl, TradeListener.NONE);
        Workload.prefill(book);
    }

    @Benchmark
    public int replayOneMessage() {
        int i = cursor;
        cursor = (i + 1 == tape.length()) ? 0 : i + 1;
        return tape.apply(book, i);
    }
}
