package obs.app;

import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.feed.BookBuilder;
import obs.feed.FlowConfig;
import obs.feed.PipelinedReplay;
import obs.feed.SyntheticItchGenerator;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Does splitting parsing from book building across two threads make ITCH replay faster?
 * Run with {@code gradlew pipelinedReplay -PpipelineArgs="[file] [runs]"}.
 *
 * Replays the same file single-threaded and pipelined with each idle strategy, several times each
 * after a warm-up, and reports the median and best throughput. Every run's final book is checked
 * against the single-threaded one. If the file doesn't exist, a full synthetic session is written first.
 */
public final class PipelinedReplayMain {

    private static final int RING_CAPACITY = 1 << 16;
    private static final int WARMUP_RUNS = 3;

    private interface Replay {
        long run(ItchReader reader, BookBuilder builder) throws InterruptedException;
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args.length > 0 ? args[0] : "build/session.itch");
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 7;
        FlowConfig flow = FlowConfig.defaults(20260914L);

        if (!Files.exists(file)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (ItchWriter writer = ItchWriter.create(file, flow.stockLocate(), flow.ticker())) {
                SyntheticItchGenerator.generate(flow, writer, SyntheticItchGenerator.Observer.NONE);
            }
        }
        ItchReader reader = ItchReader.open(file);
        System.out.printf("File: %s (%,d bytes), %d warm-up runs then %d measured runs per mode, %d logical CPUs%n%n",
                file, Files.size(file), WARMUP_RUNS, runs, Runtime.getRuntime().availableProcessors());

        OrderBook reference = newBook(flow);
        long messages = reader.replay(flow.ticker(), new BookBuilder(reference));

        System.out.printf("%-26s %14s %14s %12s%n", "mode", "median msg/s", "best msg/s", "median ns");
        measure("single thread", flow, reader, reference, messages, runs,
                (r, b) -> r.replay(flow.ticker(), b));
        for (PipelinedReplay.IdleStrategy idle : PipelinedReplay.IdleStrategy.values()) {
            measure("two threads, " + idle.name().toLowerCase(), flow, reader, reference, messages, runs,
                    (r, b) -> PipelinedReplay.run(r, flow.ticker(), b, RING_CAPACITY, idle));
        }
    }

    private static void measure(String name, FlowConfig flow, ItchReader reader, OrderBook reference, long messages,
                                int runs, Replay replay) throws InterruptedException {
        double[] rates = new double[runs];
        for (int i = -WARMUP_RUNS; i < runs; i++) {
            OrderBook book = newBook(flow);
            BookBuilder builder = new BookBuilder(book);

            long start = System.nanoTime();
            long delivered = replay.run(reader, builder);
            long elapsed = System.nanoTime() - start;

            if (delivered != messages || builder.rejectedMessages() != 0 || book.bestBid() != reference.bestBid()
                    || book.bestAsk() != reference.bestAsk() || book.orderCount() != reference.orderCount()) {
                throw new IllegalStateException(name + " built a different book");
            }
            if (i >= 0) rates[i] = messages * 1e9 / elapsed;
        }
        Arrays.sort(rates);
        double median = rates[runs / 2];
        System.out.printf("%-26s %,14.0f %,14.0f %12.1f%n", name, median, rates[runs - 1], 1e9 / median);
    }

    private static OrderBook newBook(FlowConfig flow) {
        return new OrderBook(flow.minPrice(), flow.tickSize(), flow.ladderLevels(), 1 << 20, TradeListener.NONE);
    }
}
