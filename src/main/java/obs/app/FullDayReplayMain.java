package obs.app;

import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.feed.BookBuilder;
import obs.feed.StockProfile;
import obs.feed.itch.ItchFile;
import obs.feed.itch.ItchLayout;
import obs.metrics.LatencyRecorder;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Replays a whole real ITCH day, every stock, into one fast book per stock. Run with
 * {@code gradlew fullDayReplay -PdayArgs="<file> [runs] [chunk MB] [latency]"}.
 *
 * A real day (about 30 GB) doesn't fit in memory next to the books, and a laptop disk reads it far
 * slower than the books process it, so the file is read a chunk at a time and only the processing
 * of each chunk is timed: walking the framing, routing each message to its stock's book by locate
 * code, decoding it and applying it. Reading is reported separately. This is what a feed handler
 * does after the network has delivered a packet.
 *
 * Before timing, every stock is profiled in two untimed passes ({@link StockProfile#measureAll}) and
 * gets a book sized to it. After each run every book must have applied every message (no rejections,
 * no unknown orders), be structurally valid, and be empty: a Nasdaq day removes every order by the
 * end of the file. With {@code latency}, one more run records each order message's decode-and-apply
 * time with System.nanoTime().
 */
public final class FullDayReplayMain {

    /** Caps any one stock's ladder so a book per stock fits in memory; wider stocks use their traded range. */
    private static final int MAX_LEVELS = 250_000;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) throw new IllegalArgumentException("usage: FullDayReplayMain <file> [runs] [chunk MB] [latency]");
        Path file = Path.of(args[0]);
        int runs = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int chunkBytes = (args.length > 2 ? Integer.parseInt(args[2]) : 1024) << 20;
        boolean latency = args.length > 3 && args[3].equals("latency");

        System.out.printf("JVM: %s %s, args %s%n", System.getProperty("java.vm.name"),
                System.getProperty("java.runtime.version"), ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.printf("File: %s (%,d bytes), read in %,d MB chunks%n", file, Files.size(file), chunkBytes >> 20);

        long start = System.nanoTime();
        StockProfile[] profiles;
        try (ItchFile itch = ItchFile.open(file)) {
            profiles = StockProfile.measureAll(itch, MAX_LEVELS);
        }
        int stocks = 0;
        long levels = 0;
        long pool = 0;
        long far = 0;
        long farMessages = 0;
        long orderMessages = 0;
        int capped = 0;
        for (StockProfile p : profiles) {
            if (p == null) continue;
            stocks++;
            levels += p.ladderLevels();
            pool += p.poolCapacity();
            far += p.farCapacity();
            farMessages += p.farOrderMessages();
            orderMessages += p.orderMessages();
            if (p.ladderLevels() == MAX_LEVELS) capped++;
        }
        System.out.printf("Profiled %,d stocks in %.1f s: %,d order messages, %.3f%% of them on far levels; "
                        + "%,d ladder levels, %,d pool slots, %,d far levels a side in total; %d ladders capped at %,d levels%n%n",
                stocks, (System.nanoTime() - start) / 1e9, orderMessages, 100.0 * farMessages / orderMessages,
                levels, pool, far, capped, MAX_LEVELS);

        for (int run = 1; run <= runs + (latency ? 1 : 0); run++) {
            boolean timeEach = latency && run > runs;
            replay(file, profiles, chunkBytes, run, timeEach);
        }
    }

    private static void replay(Path file, StockProfile[] profiles, int chunkBytes, int run, boolean timeEach)
            throws IOException {
        OrderBook[] books = new OrderBook[profiles.length];
        BookBuilder[] builders = new BookBuilder[profiles.length];
        for (int l = 0; l < profiles.length; l++) {
            if (profiles[l] == null) continue;
            books[l] = profiles[l].newBook(TradeListener.NONE);
            builders[l] = new BookBuilder(books[l]);
        }
        System.gc();
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();

        Router router = new Router(builders, timeEach ? new LatencyRecorder("order message", 10_000_000_000L) : null);
        long readNanos = 0;
        long processNanos = 0;
        long bytes = 0;
        double slowestChunk = Double.MAX_VALUE;
        double fastestChunk = 0;
        long[] gcBefore = gcCounters();

        try (Arena arena = Arena.ofConfined();
             FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            MemorySegment buffer = arena.allocate(chunkBytes);
            ByteBuffer view = buffer.asByteBuffer();
            long carry = 0;
            while (true) {
                long t0 = System.nanoTime();
                view.clear().position((int) carry);
                boolean eof = false;
                while (view.hasRemaining()) {
                    if (channel.read(view) < 0) {
                        eof = true;
                        break;
                    }
                }
                long filled = view.position();
                long t1 = System.nanoTime();
                readNanos += t1 - t0;

                ItchFile chunk = ItchFile.view(buffer.asSlice(0, filled));
                router.itch = chunk;
                long before = router.orderMessages;
                long used = chunk.forEachComplete(router);
                long t2 = System.nanoTime();
                processNanos += t2 - t1;
                bytes += used;

                if (used > 0 && router.orderMessages - before > 1_000_000) {
                    double rate = (router.orderMessages - before) * 1e9 / (t2 - t1);
                    slowestChunk = Math.min(slowestChunk, rate);
                    fastestChunk = Math.max(fastestChunk, rate);
                }
                carry = filled - used;
                if (eof) {
                    if (carry != 0) throw new IllegalStateException(carry + " bytes of a cut-off message at the end of the file");
                    break;
                }
                MemorySegment.copy(buffer, used, buffer, 0, carry);
            }
        }
        long[] gcAfter = gcCounters();

        long rejected = 0;
        long unknown = 0;
        long resting = 0;
        for (int l = 0; l < books.length; l++) {
            if (books[l] == null) continue;
            rejected += builders[l].rejectedMessages();
            unknown += builders[l].unknownRefMessages();
            resting += books[l].orderCount();
            BookValidator.validate(books[l], true);
        }

        String label = timeEach ? "latency run" : "run " + run + (run == 1 ? " (includes JIT warm-up)" : "");
        System.out.printf("%s: %,d messages (%,d order messages) in %,d bytes%n", label, router.messages,
                router.orderMessages, bytes);
        System.out.printf("  processing %.2f s: %,.0f order messages/sec (%.1f ns each), %,.0f messages/sec overall; "
                        + "chunks ranged %,.0f to %,.0f order messages/sec%n",
                processNanos / 1e9, router.orderMessages * 1e9 / processNanos, (double) processNanos / router.orderMessages,
                router.messages * 1e9 / processNanos, slowestChunk, fastestChunk);
        System.out.printf("  reading %.1f s (%.0f MB/s, not included above); books use about %,d MB of heap; "
                        + "GC %d collections, %d ms%n",
                readNanos / 1e9, bytes / 1e6 / (readNanos / 1e9), heapUsed >> 20, gcAfter[0] - gcBefore[0], gcAfter[1] - gcBefore[1]);
        System.out.printf("  %,d rejected, %,d for unknown orders; %,d orders resting at the end; every book structurally valid%n",
                rejected, unknown, resting);
        if (router.latency != null) {
            System.out.println("  Per order message, System.nanoTime() around each decode and apply:");
            System.out.println("  " + LatencyRecorder.tableHeader());
            System.out.println("  " + router.latency.tableRow());
        }
        System.out.println();
        if (rejected != 0 || resting != 0) throw new IllegalStateException("the replay did not match the day");
    }

    /** Sends each order message to its stock's book. */
    private static final class Router implements ItchFile.Visitor {
        final BookBuilder[] builders;
        final LatencyRecorder latency;
        ItchFile itch;
        long messages;
        long orderMessages;

        Router(BookBuilder[] builders, LatencyRecorder latency) {
            this.builders = builders;
            this.latency = latency;
        }

        @Override
        public void message(long offset, byte type, int length, int locate) {
            messages++;
            BookBuilder builder = locate >= 0 ? builders[locate] : null;
            // Hidden-trade 'P' messages don't change the visible book, so they aren't order messages here.
            if (builder == null || type == ItchLayout.TRADE || !StockProfile.isOrderMessage(type)) return;
            orderMessages++;
            if (latency == null) {
                itch.deliver(offset, builder);
            } else {
                long t0 = System.nanoTime();
                itch.deliver(offset, builder);
                latency.record(System.nanoTime() - t0);
            }
        }
    }

    private static long[] gcCounters() {
        long collections = 0;
        long millis = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, gc.getCollectionCount());
            millis += Math.max(0, gc.getCollectionTime());
        }
        return new long[] {collections, millis};
    }
}
