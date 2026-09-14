package obs.feed;

import obs.core.Book;
import obs.feed.itch.ItchWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated flow should look like an active, liquid stock, not a degenerate book. */
class SyntheticFlowTest {

    @Test
    void tenMinutesOfDefaultFlowLooksLikeAnActiveMarket() throws IOException {
        FlowConfig config = FlowConfig.defaults(99).withDuration(600 * FlowConfig.NANOS_PER_SECOND);
        MarketShape shape = new MarketShape(config.tickSize());

        SyntheticItchGenerator.Summary summary;
        try (ItchWriter discard = new ItchWriter(Channels.newChannel(OutputStream.nullOutputStream()), 1, "SYNTH")) {
            summary = SyntheticItchGenerator.generate(config, discard, shape);
        }

        String report = summary + String.format(" two-sided=%.4f spread<=3 ticks=%.4f mean resting=%.0f",
                shape.twoSidedFraction(), shape.tightSpreadFraction(), shape.meanRestingOrders());
        System.out.println("SyntheticFlowTest: " + report);

        assertTrue(summary.cancelToTradeRatio() > 10, "cancel-to-trade ratio: " + report);
        assertTrue(shape.twoSidedFraction() > 0.99, "book should almost always have both sides: " + report);
        assertTrue(shape.tightSpreadFraction() > 0.90, "spread should usually be 1-3 ticks: " + report);
        assertTrue(summary.executionMessages() > 1_000, "the stock should trade: " + report);
        assertEquals(summary.orderMessages() + 7, summary.totalMessages());
    }

    @Test
    void sameSeedGivesTheSameSession() throws IOException {
        FlowConfig config = FlowConfig.defaults(5).withDuration(30 * FlowConfig.NANOS_PER_SECOND);
        assertEquals(generate(config), generate(config));
    }

    private static SyntheticItchGenerator.Summary generate(FlowConfig config) throws IOException {
        try (ItchWriter discard = new ItchWriter(Channels.newChannel(OutputStream.nullOutputStream()), 1, "SYNTH")) {
            return SyntheticItchGenerator.generate(config, discard, SyntheticItchGenerator.Observer.NONE);
        }
    }

    private static final class MarketShape implements SyntheticItchGenerator.Observer {
        private final long tick;
        private long events, twoSided, tightSpread, restingSum;

        MarketShape(long tick) {
            this.tick = tick;
        }

        @Override
        public void afterEvent(long orderMessages, Book book) {
            events++;
            restingSum += book.orderCount();
            if (book.bestBid() != Book.NO_BID && book.bestAsk() != Book.NO_ASK) {
                twoSided++;
                if (book.bestAsk() - book.bestBid() <= 3 * tick) tightSpread++;
            }
        }

        double twoSidedFraction() {
            return (double) twoSided / events;
        }

        double tightSpreadFraction() {
            return (double) tightSpread / events;
        }

        double meanRestingOrders() {
            return (double) restingSum / events;
        }
    }
}
