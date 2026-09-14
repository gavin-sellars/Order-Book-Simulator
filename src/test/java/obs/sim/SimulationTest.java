package obs.sim;

import obs.core.Book;
import obs.core.OrderResult;
import obs.core.Prices;
import obs.core.Side;
import obs.feed.FlowConfig;
import obs.feed.itch.ItchLayout;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;
import obs.strategy.Strategy;
import obs.strategy.StrategyContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** End-to-end timing through hand-written ITCH sessions and scripted strategies. */
class SimulationTest {

    private static final long T = FlowConfig.MARKET_OPEN;
    private static final long MS = 1_000_000;
    private static final long US = 1_000;
    private static final long NO_FEES_SAMPLE_SECOND = FlowConfig.NANOS_PER_SECOND;

    private static long px(String price) {
        return Prices.parse(price);
    }

    /** A session: open, the given messages, close at T + 20 ms. */
    private static ItchReader session(Consumer<ItchWriter> messages) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ItchWriter w = new ItchWriter(Channels.newChannel(bytes), 1, "SYNTH")) {
            w.systemEvent(T - 3 * FlowConfig.NANOS_PER_SECOND, ItchLayout.START_OF_MESSAGES);
            w.stockDirectory(T - 2 * FlowConfig.NANOS_PER_SECOND, 100);
            w.systemEvent(T, ItchLayout.START_OF_MARKET_HOURS);
            messages.accept(w);
            w.systemEvent(T + 20 * MS, ItchLayout.END_OF_MARKET_HOURS);
        }
        return new ItchReader(ByteBuffer.wrap(bytes.toByteArray()));
    }

    private static Simulation.Result simulate(ItchReader reader, Strategy strategy, LatencyModel latency) {
        Simulation simulation = Simulation.forFlow(FlowConfig.defaults(1), strategy,
                new Simulation.Config(latency, 0, 0, NO_FEES_SAMPLE_SECOND));
        return simulation.run(reader, "SYNTH");
    }

    /** Records every callback with its time in microseconds after the open. */
    private abstract static class Recording implements Strategy {
        final List<String> log = new ArrayList<>();
        StrategyContext context;

        @Override
        public void init(StrategyContext context) {
            this.context = context;
        }

        static String price(long p, long none) {
            return p == none ? "none" : Prices.format(p);
        }

        @Override
        public void onBookUpdate(long time, long bid, long ask, long bidQty, long askQty) {
            log.add("update " + (time - T) / US + "us " + price(bid, Book.NO_BID) + " / " + price(ask, Book.NO_ASK));
        }

        @Override
        public void onTrade(long time, long price, int qty, byte aggressorSide) {
            log.add("trade " + (time - T) / US + "us " + qty + " @ " + Prices.format(price) + " " + Side.name(aggressorSide));
        }

        @Override
        public void onOwnFill(long time, long clientId, byte side, long price, int qty, int remaining) {
            log.add("fill " + (time - T) / US + "us " + Side.name(side) + " " + qty + " @ " + Prices.format(price) + ", " + remaining + " left");
        }

        @Override
        public void onOrderRejected(long time, long clientId, int reason) {
            log.add("rejected " + (time - T) / US + "us " + OrderResult.name(reason));
        }

        @Override
        public void onSessionEnd(long time) {
            log.add("end " + (time - T) / US + "us");
        }
    }

    @Test
    void everyMessageReachesTheStrategyAfterItsLatency() throws IOException {
        ItchReader reader = session(w -> {
            w.addOrder(T + MS, 1, Side.BUY, 500, px("150.00"));
            w.addOrder(T + 2 * MS, 2, Side.SELL, 500, px("150.02"));
            w.orderExecuted(T + 10 * MS, 1, 500, 1);            // the 500 shares ahead of our bid trade
            w.addOrder(T + 11 * MS, 4, Side.BUY, 200, px("150.00"));  // a new bid behind ours
            w.orderExecuted(T + 12 * MS, 4, 200, 2);            // it trades, so ours would have traded first
        });
        Recording strategy = new Recording() {
            boolean sent;

            @Override
            public void onBookUpdate(long time, long bid, long ask, long bidQty, long askQty) {
                super.onBookUpdate(time, bid, ask, bidQty, askQty);
                if (!sent && bid != Book.NO_BID && ask != Book.NO_ASK) {
                    sent = true;
                    context.sendLimit(Side.BUY, bid, 100);      // sent at 3 ms, reaches the exchange at 5 ms
                }
            }
        };

        Simulation.Result result = simulate(reader, strategy, new LatencyModel(MS, 2 * MS, 0, 1));

        assertEquals(List.of(
                "update 2000us 150.00 / none",
                "update 3000us 150.00 / 150.02",
                "trade 11000us 500 @ 150.00 SELL",
                "update 11000us none / 150.02",
                "update 12000us 150.00 / 150.02",
                "trade 13000us 200 @ 150.00 SELL",
                "update 13000us none / 150.02",
                "fill 14000us BUY 100 @ 150.00, 0 left",
                "end 21000us"), strategy.log);
        assertEquals(100, result.position());
        assertEquals(1, result.fills());
        assertEquals(1.0, result.fillRate());
        assertEquals(px("1.00"), result.markToMarket(), "bought 100 at 150.00, marked at the last mid of 150.01");
    }

    @Test
    void latencyTurnsAQuoteWorthTakingIntoOneThatIsAlreadyGone() throws IOException {
        Consumer<ItchWriter> quoteFlickers = w -> {
            w.addOrder(T + MS, 2, Side.SELL, 100, px("150.02"));
            w.orderDelete(T + 1500 * US, 2);                   // gone half a millisecond later
            w.addOrder(T + 5 * MS, 9, Side.SELL, 100, px("151.00"));
        };

        class Taker extends Recording {
            boolean sent;

            @Override
            public void onBookUpdate(long time, long bid, long ask, long bidQty, long askQty) {
                if (!sent && ask == px("150.02")) {
                    sent = true;
                    context.sendLimit(Side.BUY, ask, 100);
                }
            }

            @Override
            public void onSessionEnd(long time) {
                context.sendLimit(Side.BUY, px("150.00"), 100);  // too late: the market has closed
            }
        }

        Taker instant = new Taker();
        Simulation.Result zero = simulate(session(quoteFlickers), instant, LatencyModel.zero());
        Taker slow = new Taker();
        Simulation.Result oneMillisecond = simulate(session(quoteFlickers), slow, new LatencyModel(MS, MS, 0, 1));

        assertEquals(1, zero.fills(), "with no latency the order reaches the quote before it disappears");
        assertEquals(100, zero.position());
        assertEquals(0, oneMillisecond.fills(), "with 1 ms latency the quote was deleted before the order arrived");
        assertEquals(0, oneMillisecond.position());
        assertEquals(2, oneMillisecond.ordersSent());

        assertEquals(1, zero.rejectedOrders());
        assertEquals(List.of("fill 1000us BUY 100 @ 150.02, 0 left", "rejected 20000us REJECTED_MARKET_CLOSED"), instant.log);
        assertEquals(List.of("rejected 23000us REJECTED_MARKET_CLOSED"), slow.log);
    }
}
