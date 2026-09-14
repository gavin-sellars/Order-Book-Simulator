package obs.sim;

import obs.feed.FlowConfig;
import obs.feed.SyntheticItchGenerator;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;
import obs.strategy.SampleMarketMaker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replay determinism (Part 8 of the guide): the same session, strategy, latency and seed must give
 * exactly the same result every time, including every P&L sample.
 */
class SimulationDeterminismTest {

    private static final long MINUTES = 30;
    private static final FlowConfig FLOW = FlowConfig.defaults(7).withDuration(MINUTES * 60 * FlowConfig.NANOS_PER_SECOND);

    private static byte[] session(FlowConfig flow) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ItchWriter writer = new ItchWriter(Channels.newChannel(bytes), flow.stockLocate(), flow.ticker())) {
            SyntheticItchGenerator.generate(flow, writer, SyntheticItchGenerator.Observer.NONE);
        }
        return bytes.toByteArray();
    }

    private static Simulation.Result simulate(byte[] session, LatencyModel latency) {
        Simulation simulation = Simulation.forFlow(FLOW, new SampleMarketMaker(100, 1_000),
                new Simulation.Config(latency, 20, 30, FlowConfig.NANOS_PER_SECOND));
        return simulation.run(new ItchReader(ByteBuffer.wrap(session)), FLOW.ticker());
    }

    @Test
    void sameInputsGiveIdenticalResults() throws IOException {
        byte[] first = session(FLOW);
        byte[] second = session(FLOW);

        Simulation.Result a = simulate(first, new LatencyModel(100_000, 100_000, 20_000, 11));
        Simulation.Result b = simulate(second, new LatencyModel(100_000, 100_000, 20_000, 11));

        assertEquals(a, b);
        assertTrue(a.fills() > 0 && a.ordersSent() > 0, "the market maker should trade: " + a.fills() + " fills");
        assertEquals(MINUTES * 60, a.samples().size() - 1, "one sample per second of the session, plus the close");
    }

    /** Guards against a simulation that ignores its latency model and so is trivially "deterministic". */
    @Test
    void latencyChangesTheResult() throws IOException {
        byte[] bytes = session(FLOW);

        assertNotEquals(simulate(bytes, LatencyModel.zero()), simulate(bytes, new LatencyModel(50_000_000, 50_000_000, 0, 1)));
    }
}
