package obs.app;

import obs.core.Book;
import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.Prices;
import obs.core.Side;
import obs.core.TradeListener;
import obs.feed.BookBuilder;
import obs.feed.FlowConfig;
import obs.feed.MessageHandler;
import obs.feed.SyntheticItchGenerator;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Generates a synthetic ITCH session to a file, replays the file into the fast book, and checks the
 * replay against the generator's reference book after every generated event.
 * Run with {@code gradlew itchSession -PitchArgs="[seed] [minutes] [file]"}.
 *
 * The check is the full-session version of ItchRoundTripTest. The fast book only ever sees the
 * ITCH bytes, and after every event it must have the reference book's touch and order count;
 * every {@value #DEPTH_EVERY} events, its full depth on both sides; and every execution must match.
 * Mismatches are counted rather than stopping at the first, and so are order messages that refer
 * to an order the fast book doesn't hold.
 *
 * The replay is timed once, cold, including JIT warm-up. For warmed-up throughput and per-message
 * percentiles use {@code gradlew replayLatency}.
 *
 * Defaults: seed 20260914, a full 390-minute session (09:30 to 16:00), build/session.itch.
 */
public final class ItchSessionMain {

    private static final int DEPTH_EVERY = 1000;

    public static void main(String[] args) throws IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260914L;
        long minutes = args.length > 1 ? Long.parseLong(args[1]) : 390;
        Path file = Path.of(args.length > 2 ? args[2] : "build/session.itch");

        FlowConfig config = FlowConfig.defaults(seed).withDuration(minutes * 60 * FlowConfig.NANOS_PER_SECOND);
        Files.createDirectories(file.toAbsolutePath().getParent());

        Expected expected = new Expected();
        long start = System.nanoTime();
        SyntheticItchGenerator.Summary summary;
        try (ItchWriter writer = ItchWriter.create(file, config.stockLocate(), config.ticker())) {
            summary = SyntheticItchGenerator.generate(config, writer, expected);
        }
        double generateSeconds = (System.nanoTime() - start) / 1e9;
        long bytes = Files.size(file);

        System.out.printf("Generated %s: seed %d, %d minutes, %,d bytes%n", file, seed, minutes, bytes);
        System.out.printf("  %,d events -> %,d messages (%,d add, %,d execute, %,d cancel, %,d delete, %,d replace, %d session)%n",
                summary.events(), summary.totalMessages(), summary.addMessages(), summary.executionMessages(),
                summary.cancelMessages(), summary.deleteMessages(), summary.replaceMessages(), summary.sessionMessages());
        System.out.printf("  %,d shares traded, cancel-to-trade %.1f : 1, closing touch %s / %s with %,d resting orders%n",
                summary.sharesTraded(), summary.cancelToTradeRatio(), Prices.format(summary.bestBid()),
                Prices.format(summary.bestAsk()), summary.restingOrders());
        System.out.printf("  generation (reference book matching, ITCH writing, recording the reference book for the check): %.2f s, %,.0f messages/sec%n",
                generateSeconds, summary.totalMessages() / generateSeconds);

        ItchReader reader = ItchReader.open(file);
        OrderBook book = new OrderBook(config.minPrice(), config.tickSize(), config.ladderLevels(), config.poolCapacity(),
                TradeListener.NONE);
        BookBuilder builder = new BookBuilder(book);

        start = System.nanoTime();
        long delivered = reader.replay(config.ticker(), builder);
        double replaySeconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("Replayed into the fast book (pool %,d): %,d messages in %.3f s, %,.0f messages/sec (%.1f ns/message), one cold run%n",
                book.poolCapacity(), delivered, replaySeconds, delivered / replaySeconds, replaySeconds * 1e9 / delivered);

        Checker checker = new Checker(config, expected);
        reader.replay(config.ticker(), checker);
        checker.checkBoundaries();
        BookValidator.validate(checker.book, false);
        long executionMismatches = checker.executionMismatches();
        BookBuilder checked = checker.builder;

        System.out.println("Replayed again and checked against the generator's reference book:");
        System.out.printf("  %,d of %,d events: touch and order count, %,d mismatches%n",
                checker.nextEvent, expected.events, checker.touchMismatches);
        System.out.printf("  %,d full-depth comparisons (every %,d events), %,d mismatches%n",
                checker.depthChecks, DEPTH_EVERY, checker.depthMismatches);
        System.out.printf("  %,d of %,d executions, %,d mismatches%n",
                checker.executions.size(), expected.executions.size(), executionMismatches);
        System.out.printf("  %,d order messages (generator wrote %,d), %,d rejected, %,d referring to an unknown order%n",
                checked.orderMessages(), summary.orderMessages(), checked.rejectedMessages(), checked.unknownRefMessages());
        System.out.printf("  closing touch %s / %s with %,d resting orders; structure valid%n",
                Prices.format(checker.book.bestBid()), Prices.format(checker.book.bestAsk()), checker.book.orderCount());

        boolean matches = builder.rejectedMessages() == 0
                && builder.orderMessages() == summary.orderMessages()
                && checked.rejectedMessages() == 0
                && checker.nextEvent == expected.events
                && checker.touchMismatches == 0
                && checker.depthMismatches == 0
                && executionMismatches == 0
                && checker.book.bestBid() == summary.bestBid()
                && checker.book.bestAsk() == summary.bestAsk()
                && checker.book.orderCount() == summary.restingOrders();
        System.out.println(matches ? "MATCH: the replayed book matched the generator's book after every event"
                                   : "MISMATCH: the replayed book differs from the generator's book");
        if (!matches) System.exit(1);
    }

    /** The reference book's state after each generated event, recorded while generating. */
    private static final class Expected implements SyntheticItchGenerator.Observer {
        int events;
        long[] boundaries = new long[1 << 20];      // order messages written by the end of each event
        long[] bids = new long[1 << 20];
        long[] asks = new long[1 << 20];
        int[] orderCounts = new int[1 << 20];
        final List<long[][]> depth = new ArrayList<>();
        final List<long[]> executions = new ArrayList<>();

        @Override
        public void afterEvent(long orderMessages, Book book) {
            if (events == boundaries.length) {
                boundaries = Arrays.copyOf(boundaries, events * 2);
                bids = Arrays.copyOf(bids, events * 2);
                asks = Arrays.copyOf(asks, events * 2);
                orderCounts = Arrays.copyOf(orderCounts, events * 2);
            }
            boundaries[events] = orderMessages;
            bids[events] = book.bestBid();
            asks[events] = book.bestAsk();
            orderCounts[events] = book.orderCount();
            if (events % DEPTH_EVERY == 0) depth.add(depthOf(book));
            events++;
        }

        @Override
        public void onExecution(long restingRef, int shares, long price, long matchNumber) {
            executions.add(new long[] {restingRef, shares, price, matchNumber});
        }
    }

    /** Replays into a fresh fast book and compares it with the recording at every event boundary. */
    private static final class Checker implements MessageHandler {
        final Expected expected;
        final OrderBook book;
        final BookBuilder builder;
        final List<long[]> executions = new ArrayList<>();
        long lastTradePrice;
        int nextEvent;
        long touchMismatches;
        long depthChecks;
        long depthMismatches;

        Checker(FlowConfig config, Expected expected) {
            this.expected = expected;
            this.book = new OrderBook(config.minPrice(), config.tickSize(), config.ladderLevels(), config.poolCapacity(),
                    (aggressorId, restingId, price, qty, side) -> lastTradePrice = price);
            this.builder = new BookBuilder(book);
            checkBoundaries();                      // events that wrote nothing before the first message
        }

        @Override
        public void onAdd(long ts, long ref, byte side, int shares, long price) {
            builder.onAdd(ts, ref, side, shares, price);
            checkBoundaries();
        }

        @Override
        public void onExecute(long ts, long ref, int shares, long match, long price) {
            lastTradePrice = -1;
            builder.onExecute(ts, ref, shares, match, price);
            executions.add(new long[] {ref, shares, lastTradePrice, match});
            checkBoundaries();
        }

        @Override
        public void onCancel(long ts, long ref, int shares) {
            builder.onCancel(ts, ref, shares);
            checkBoundaries();
        }

        @Override
        public void onDelete(long ts, long ref) {
            builder.onDelete(ts, ref);
            checkBoundaries();
        }

        @Override
        public void onReplace(long ts, long oldRef, long newRef, int shares, long price) {
            builder.onReplace(ts, oldRef, newRef, shares, price);
            checkBoundaries();
        }

        void checkBoundaries() {
            long written = builder.orderMessages();
            while (nextEvent < expected.events && expected.boundaries[nextEvent] == written) {
                int e = nextEvent;
                if (expected.bids[e] != book.bestBid() || expected.asks[e] != book.bestAsk()
                        || expected.orderCounts[e] != book.orderCount()) {
                    touchMismatches++;
                }
                if (e % DEPTH_EVERY == 0) {
                    depthChecks++;
                    if (!Arrays.deepEquals(expected.depth.get(e / DEPTH_EVERY), depthOf(book))) depthMismatches++;
                }
                nextEvent++;
            }
        }

        long executionMismatches() {
            long mismatches = Math.abs(expected.executions.size() - executions.size());
            int n = Math.min(expected.executions.size(), executions.size());
            for (int i = 0; i < n; i++) {
                if (!Arrays.equals(expected.executions.get(i), executions.get(i))) mismatches++;
            }
            return mismatches;
        }
    }

    /** Every level on both sides: {bid prices, bid quantities, bid order counts, ask prices, ask quantities, ask order counts}. */
    private static long[][] depthOf(Book book) {
        long[][] rows = new long[6][];
        for (byte side = Side.BUY; side <= Side.SELL; side++) {
            int n = book.levelCount(side);
            long[] prices = new long[n];
            long[] qtys = new long[n];
            int[] counts = new int[n];
            book.depth(side, n, prices, qtys, counts);
            rows[3 * side] = prices;
            rows[3 * side + 1] = qtys;
            rows[3 * side + 2] = Arrays.stream(counts).asLongStream().toArray();
        }
        return rows;
    }
}
