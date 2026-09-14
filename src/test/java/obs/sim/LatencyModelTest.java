package obs.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyModelTest {

    @Test
    void withoutJitterDelaysAreExact() {
        LatencyModel model = new LatencyModel(50_000, 100_000, 0, 1);

        assertEquals(1_050_000, model.marketDataDelivery(1_000_000));
        assertEquals(1_100_000, model.orderArrival(1_000_000));
        assertEquals(1_100_000, model.responseDelivery(1_000_000));
        assertEquals(7, LatencyModel.zero().orderArrival(7));
    }

    @Test
    void jitterIsNeverNegativeAndHasTheConfiguredMean() {
        LatencyModel model = new LatencyModel(0, 20_000, 1_000, 2);
        int samples = 200_000;
        long sum = 0;

        for (int i = 0; i < samples; i++) {
            long sentAt = i * 1_000_000_000L;               // far apart, so ordering never holds a message back
            long jitter = model.orderArrival(sentAt) - sentAt - 20_000;
            assertTrue(jitter >= 0, "negative jitter " + jitter);
            sum += jitter;
        }

        assertEquals(1_000, (double) sum / samples, 1_000 * 0.03);
    }

    @Test
    void messagesOnOneChannelNeverOvertakeEachOther() {
        LatencyModel model = new LatencyModel(10_000, 10_000, 50_000, 3);
        long previousMarketData = Long.MIN_VALUE;
        long previousOrder = Long.MIN_VALUE;

        for (long sentAt = 0; sentAt < 100_000; sentAt++) {     // one message per nanosecond, jitter far larger
            long marketData = model.marketDataDelivery(sentAt);
            long order = model.orderArrival(sentAt);
            assertTrue(marketData >= previousMarketData && marketData >= sentAt + 10_000);
            assertTrue(order >= previousOrder && order >= sentAt + 10_000);
            previousMarketData = marketData;
            previousOrder = order;
        }
    }

    @Test
    void sameSeedGivesTheSameDelays() {
        LatencyModel a = new LatencyModel(1_000, 2_000, 5_000, 42);
        LatencyModel b = new LatencyModel(1_000, 2_000, 5_000, 42);

        for (long t = 0; t < 10_000_000; t += 1_000) {
            assertEquals(a.marketDataDelivery(t), b.marketDataDelivery(t));
            assertEquals(a.orderArrival(t), b.orderArrival(t));
        }
    }

    @Test
    void rejectsNegativeLatency() {
        assertThrows(IllegalArgumentException.class, () -> new LatencyModel(-1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LatencyModel(0, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new LatencyModel(0, 0, -1, 1));
    }
}
