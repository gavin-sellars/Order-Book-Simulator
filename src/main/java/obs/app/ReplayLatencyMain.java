package obs.app;

import obs.core.Book;
import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.feed.BookBuilder;
import obs.feed.FlowConfig;
import obs.feed.SyntheticItchGenerator;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;
import obs.metrics.LatencyRecorder;
import obs.ref.RefLinkedOrderBook;
import obs.ref.RefOrderBook;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Warmed-up replay throughput and per-message latency percentiles for one book over a full ITCH
 * session file. Run with {@code gradlew replayLatency -PreplayArgs="<book> [runs] [file]"}, where
 * the book is fast (pool sized by {@link FlowConfig#poolCapacity}), fast:POOL, ref or refLinked.
 *
 * One book per JVM: replaying two in the same JVM would make BookBuilder's calls into Book
 * megamorphic and slow down whichever runs second.
 *
 * Throughput: {@code runs} full replays, each into a fresh book on a freshly mapped file whose pages
 * are touched first, so page faults on the mapping aren't charged to the book. Run 1 includes JIT
 * warm-up and is reported but excluded; the result is the median of the rest.
 *
 * Latency: one more replay with System.nanoTime() around each message's decode and apply, recorded
 * in an HdrHistogram. nanoTime's own cost and resolution are printed too: on Windows it moves in
 * 100 ns steps, so percentiles of a few hundred ns are mostly clock.
 *
 * GC collections and JIT compilation time during each timed section are printed, to tell a GC pause
 * or a compilation apart from anything else in the tail.
 */
public final class ReplayLatencyMain {

    public static void main(String[] args) throws IOException {
        String impl = args.length > 0 ? args[0] : "fast";
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        Path file = Path.of(args.length > 2 ? args[2] : "build/session.itch");
        if (runs < 2) throw new IllegalArgumentException("need at least 2 runs: the first is warm-up");
        FlowConfig flow = FlowConfig.defaults(20260914L);

        if (!Files.exists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (ItchWriter writer = ItchWriter.create(file, flow.stockLocate(), flow.ticker())) {
                SyntheticItchGenerator.generate(flow, writer, SyntheticItchGenerator.Observer.NONE);
            }
        }

        System.out.printf("JVM: %s %s, args %s, %d logical CPUs%n", System.getProperty("java.vm.name"),
                System.getProperty("java.runtime.version"), ManagementFactory.getRuntimeMXBean().getInputArguments(),
                Runtime.getRuntime().availableProcessors());
        System.out.printf("File: %s (%,d bytes). Book: %s%n%n", file, Files.size(file), describe(impl, flow));

        double[] rates = new double[runs];
        for (int r = 0; r < runs; r++) {
            ItchReader reader = mapAndTouch(file, flow);
            BookBuilder builder = new BookBuilder(newBook(impl, flow));

            long[] before = jvmCounters();
            long start = System.nanoTime();
            long delivered = reader.replay(flow.ticker(), builder);
            long elapsed = System.nanoTime() - start;
            long[] after = jvmCounters();

            requireClean(builder);
            rates[r] = delivered * 1e9 / elapsed;
            System.out.printf("run %d%s: %,d messages in %.3f s, %,.0f msg/s (%.1f ns/msg); GC %d collections, %d ms; JIT %d ms%n",
                    r + 1, r == 0 ? " (warm-up, excluded)" : "", delivered, elapsed / 1e9, rates[r], elapsed / (double) delivered,
                    after[0] - before[0], after[1] - before[1], after[2] - before[2]);
        }
        double[] measured = Arrays.copyOfRange(rates, 1, runs);
        Arrays.sort(measured);
        int mid = measured.length / 2;
        double median = measured.length % 2 == 1 ? measured[mid] : (measured[mid - 1] + measured[mid]) / 2;
        System.out.printf("Median of runs 2-%d: %,.0f msg/s (%.1f ns/msg)%n%n", runs, median, 1e9 / median);

        ItchReader reader = mapAndTouch(file, flow);
        int[] offsets = new int[Math.toIntExact(reader.scan(flow.ticker(), offset -> { }))];
        int[] cursor = new int[1];
        reader.scan(flow.ticker(), offset -> offsets[cursor[0]++] = offset);
        BookBuilder builder = new BookBuilder(newBook(impl, flow));
        LatencyRecorder latency = new LatencyRecorder("decode + apply");

        long[] before = jvmCounters();
        for (int offset : offsets) {
            long t0 = System.nanoTime();
            reader.deliver(offset, builder);
            latency.record(System.nanoTime() - t0);
        }
        long[] after = jvmCounters();
        requireClean(builder);

        System.out.println("Per-message latency, System.nanoTime() around each message:");
        System.out.println(LatencyRecorder.tableHeader());
        System.out.println(latency.tableRow());
        System.out.printf("GC %d collections, %d ms; JIT %d ms during the pass. %s%n",
                after[0] - before[0], after[1] - before[1], after[2] - before[2], nanoTimeCost());
    }

    private static Book newBook(String impl, FlowConfig flow) {
        if (impl.equals("fast") || impl.startsWith("fast:")) {
            int pool = impl.equals("fast") ? flow.poolCapacity() : Integer.parseInt(impl.substring("fast:".length()));
            return new OrderBook(flow.minPrice(), flow.tickSize(), flow.ladderLevels(), pool, TradeListener.NONE);
        }
        return switch (impl) {
            case "ref" -> new RefOrderBook(flow.tickSize(), TradeListener.NONE);
            case "refLinked" -> new RefLinkedOrderBook(flow.tickSize(), TradeListener.NONE);
            default -> throw new IllegalArgumentException("unknown book: " + impl + " (fast, fast:POOL, ref or refLinked)");
        };
    }

    private static String describe(String impl, FlowConfig flow) {
        return newBook(impl, flow) instanceof OrderBook fast
                ? String.format("fast, pool %,d orders", fast.poolCapacity())
                : impl;
    }

    /** Maps the file and walks its framing once, which touches every page before anything is timed. */
    private static ItchReader mapAndTouch(Path file, FlowConfig flow) throws IOException {
        ItchReader reader = ItchReader.open(file);
        reader.scan(flow.ticker(), offset -> { });
        return reader;
    }

    private static void requireClean(BookBuilder builder) {
        if (builder.rejectedMessages() != 0) {
            throw new IllegalStateException(builder.rejectedMessages() + " messages were rejected; is this the default session file?");
        }
    }

    /** {GC collections, GC milliseconds, JIT compilation milliseconds} so far. */
    private static long[] jvmCounters() {
        long collections = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        return new long[] {collections, gcMillis, ManagementFactory.getCompilationMXBean().getTotalCompilationTime()};
    }

    private static String nanoTimeCost() {
        int calls = 5_000_000;
        long smallestStep = Long.MAX_VALUE;
        long first = System.nanoTime();
        long previous = first;
        for (int i = 0; i < calls; i++) {
            long now = System.nanoTime();
            if (now > previous && now - previous < smallestStep) smallestStep = now - previous;
            previous = now;
        }
        return String.format("nanoTime: %.1f ns per call, smallest observed step %d ns", (double) (previous - first) / calls, smallestStep);
    }
}
