package obs.feed;

import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

import static obs.testutil.BookAssertions.assertSameBook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelinedReplayTest {

    private static final FlowConfig FLOW = FlowConfig.defaults(3).withDuration(120 * FlowConfig.NANOS_PER_SECOND);

    private static ItchReader session() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ItchWriter writer = new ItchWriter(Channels.newChannel(bytes), FLOW.stockLocate(), FLOW.ticker())) {
            SyntheticItchGenerator.generate(FLOW, writer, SyntheticItchGenerator.Observer.NONE);
        }
        return new ItchReader(ByteBuffer.wrap(bytes.toByteArray()));
    }

    private static OrderBook newBook() {
        return new OrderBook(FLOW.minPrice(), FLOW.tickSize(), FLOW.ladderLevels(), 1 << 16, TradeListener.NONE);
    }

    @Test
    void twoThreadReplayBuildsExactlyTheSameBookAsOneThread() throws Exception {
        ItchReader reader = session();
        OrderBook expected = newBook();
        long expectedMessages = reader.replay(FLOW.ticker(), new BookBuilder(expected));

        for (PipelinedReplay.IdleStrategy idle : PipelinedReplay.IdleStrategy.values()) {
            // PARK sleeps about a millisecond per wait on Windows, so with a tiny ring it would take minutes.
            int[] capacities = idle == PipelinedReplay.IdleStrategy.PARK ? new int[] {1 << 16} : new int[] {2, 64, 1 << 16};
            for (int capacity : capacities) {
                OrderBook actual = newBook();
                BookBuilder builder = new BookBuilder(actual);

                long delivered = PipelinedReplay.run(reader, FLOW.ticker(), builder, capacity, idle);

                String context = idle + " with capacity " + capacity;
                assertEquals(expectedMessages, delivered, context);
                assertEquals(0, builder.rejectedMessages(), context);
                assertSameBook(expected, actual, FLOW.ladderLevels(), context);
                BookValidator.validate(actual, false);
            }
        }
    }

    @Test
    void aFailureOnTheBookThreadIsReportedToTheCaller() throws Exception {
        ItchReader reader = session();
        MessageHandler failing = new MessageHandler() {
            private int adds;

            @Override
            public void onAdd(long timestamp, long orderRef, byte side, int shares, long price) {
                if (++adds == 1000) throw new IllegalStateException("boom");
            }
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PipelinedReplay.run(reader, FLOW.ticker(), failing, 16, PipelinedReplay.IdleStrategy.SPIN));

        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        assertEquals("boom", root.getMessage());
    }
}
