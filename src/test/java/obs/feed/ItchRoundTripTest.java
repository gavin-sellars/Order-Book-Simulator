package obs.feed;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import obs.core.Book;
import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.Side;
import obs.feed.itch.ItchLayout;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden test for the synthetic feed. The generator matches orders in the reference book and
 * writes ITCH; replaying that ITCH through {@link ItchReader} and {@link BookBuilder} into the fast
 * {@link OrderBook} must reproduce the reference book after every generated event (touch and order
 * count every event, full depth every {@value #DEPTH_EVERY} events), and the same executions in the
 * same order.
 *
 * Two independent implementations have to agree: the reference book decides what happens, and
 * the fast book only ever sees the ITCH bytes.
 */
class ItchRoundTripTest {

    private static final int DEPTH_EVERY = 250;

    @Test
    void tenMinuteSessionWrittenToAFileReplaysExactly(@TempDir Path dir) throws IOException {
        FlowConfig config = FlowConfig.defaults(20260914L).withDuration(600 * FlowConfig.NANOS_PER_SECOND);
        Path file = dir.resolve("session.itch");

        Expected expected = new Expected();
        SyntheticItchGenerator.Summary summary;
        try (ItchWriter writer = ItchWriter.create(file, config.stockLocate(), config.ticker())) {
            summary = SyntheticItchGenerator.generate(config, writer, expected);
        }

        replayAndCompare(config, ItchReader.open(file), expected);
        assertTrue(summary.events() > 30_000, "events: " + summary.events());
        assertTrue(summary.executionMessages() > 1_000, "executions: " + summary.executionMessages());
        System.out.println("ItchRoundTripTest: " + summary);
    }

    @Property(tries = 25)
    void shortSessionsFromAnySeedReplayExactly(@ForAll long seed) throws IOException {
        FlowConfig config = FlowConfig.defaults(seed).withDuration(20 * FlowConfig.NANOS_PER_SECOND);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Expected expected = new Expected();
        try (ItchWriter writer = new ItchWriter(Channels.newChannel(bytes), config.stockLocate(), config.ticker())) {
            SyntheticItchGenerator.generate(config, writer, expected);
        }

        replayAndCompare(config, new ItchReader(ByteBuffer.wrap(bytes.toByteArray())), expected);
    }

    private static void replayAndCompare(FlowConfig config, ItchReader reader, Expected expected) {
        Replay replay = new Replay(config, expected);
        reader.replay(config.ticker(), replay);

        assertEquals(expected.events, replay.nextEvent, "every generated event boundary was reached");
        assertEquals(0, replay.builder.rejectedMessages(), "messages the fast book could not apply");
        BookValidator.validate(replay.book, false);

        assertEquals(expected.executions.size(), replay.executions.size(), "execution count");
        for (int i = 0; i < expected.executions.size(); i++) {
            assertArrayEquals(expected.executions.get(i), replay.executions.get(i), "execution " + i + " {ref, shares, price, match}");
        }
        assertEquals(List.of(ItchLayout.START_OF_MESSAGES, ItchLayout.START_OF_SYSTEM_HOURS, ItchLayout.START_OF_MARKET_HOURS,
                ItchLayout.END_OF_MARKET_HOURS, ItchLayout.END_OF_SYSTEM_HOURS, ItchLayout.END_OF_MESSAGES), replay.systemEvents);
    }

    /** The reference book's state after each generated event, recorded while generating. */
    private static final class Expected implements SyntheticItchGenerator.Observer {
        int events;
        long[] boundaries = new long[1 << 16];      // order messages written by the end of each event
        long[] bids = new long[1 << 16];
        long[] asks = new long[1 << 16];
        int[] orderCounts = new int[1 << 16];
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

    /** Replays into the fast book and checks it at every recorded event boundary. */
    private static final class Replay implements MessageHandler {
        final Expected expected;
        final OrderBook book;
        final BookBuilder builder;
        final List<long[]> executions = new ArrayList<>();
        final List<Byte> systemEvents = new ArrayList<>();
        long lastTradePrice;
        int nextEvent;

        Replay(FlowConfig config, Expected expected) {
            this.expected = expected;
            this.book = new OrderBook(config.minPrice(), config.tickSize(), config.ladderLevels(), 1 << 16,
                    (aggressorId, restingId, price, qty, side) -> lastTradePrice = price);
            this.builder = new BookBuilder(book);
            checkBoundaries();                      // events that wrote nothing before the first message
        }

        @Override
        public void onSystemEvent(long ts, byte code) {
            systemEvents.add(code);
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

        private void checkBoundaries() {
            long written = builder.orderMessages();
            while (nextEvent < expected.events && expected.boundaries[nextEvent] == written) {
                int e = nextEvent;
                assertEquals(expected.bids[e], book.bestBid(), "best bid after event " + e);
                assertEquals(expected.asks[e], book.bestAsk(), "best ask after event " + e);
                assertEquals(expected.orderCounts[e], book.orderCount(), "order count after event " + e);
                if (e % DEPTH_EVERY == 0) {
                    long[][] want = expected.depth.get(e / DEPTH_EVERY);
                    long[][] got = depthOf(book);
                    for (int row = 0; row < want.length; row++) assertArrayEquals(want[row], got[row], "depth row " + row + " after event " + e);
                }
                nextEvent++;
            }
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
