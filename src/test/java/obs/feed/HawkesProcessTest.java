package obs.feed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statistical checks with fixed seeds, so they are deterministic. Tolerances are several standard
 * deviations wide for the simulated durations.
 */
class HawkesProcessTest {

    /** Event counts per dimension in consecutive windows of {@code width} seconds. */
    private static long[][] countWindows(HawkesProcess process, double width, int windows) {
        long[][] counts = new long[process.dimensions()][windows];
        while (true) {
            int type = process.next();
            int window = (int) (process.time() / width);
            if (window >= windows) return counts;
            counts[type][window]++;
        }
    }

    private static double mean(long[] counts) {
        double sum = 0;
        for (long c : counts) sum += c;
        return sum / counts.length;
    }

    /** Variance divided by mean: 1 for a Poisson process, above 1 when arrivals cluster. */
    private static double fanoFactor(long[] counts) {
        double mean = mean(counts);
        double squares = 0;
        for (long c : counts) squares += (c - mean) * (c - mean);
        return squares / (counts.length - 1) / mean;
    }

    @Test
    void withoutExcitationItIsAPoissonProcess() {
        HawkesProcess poisson = new HawkesProcess(new double[] {200}, new double[][] {{0}}, new double[] {50}, 1);

        long[] counts = countWindows(poisson, 1.0, 2000)[0];

        assertEquals(200, mean(counts), 200 * 0.02);
        double fano = fanoFactor(counts);
        assertTrue(fano > 0.85 && fano < 1.15, "Poisson counts should have variance ≈ mean, Fano factor was " + fano);
    }

    @Test
    void selfExcitationRaisesTheRateToTheStationaryValueAndClustersArrivals() {
        // Branching ratio 60/100 = 0.6, so the long-run rate is 100 / (1 − 0.6) = 250 per second, and for
        // windows much longer than 1/β the Fano factor tends to 1 / (1 − 0.6)² = 6.25.
        HawkesProcess hawkes = new HawkesProcess(new double[] {100}, new double[][] {{60}}, new double[] {100}, 2);
        assertEquals(250, hawkes.stationaryRates()[0], 1e-9);

        long[] counts = countWindows(hawkes, 1.0, 2000)[0];

        assertEquals(250, mean(counts), 250 * 0.03);
        double fano = fanoFactor(counts);
        assertTrue(fano > 4 && fano < 9, "expected strong clustering near 6.25, Fano factor was " + fano);
    }

    @Test
    void crossExcitationReachesEachDimensionsStationaryRate() {
        double[] base = {50, 20};
        double[][] alpha = {{20, 40}, {10, 30}};
        double[] decay = {100, 100};
        HawkesProcess process = new HawkesProcess(base, alpha, decay, 3);
        double[] expected = process.stationaryRates();

        long[][] counts = countWindows(process, 1.0, 1000);

        // (I − G) Λ = μ with G = [[0.2, 0.4], [0.1, 0.3]]: det(I − G) = 0.52,
        // Λ₀ = (0.7·50 + 0.4·20) / 0.52 = 82.69..., Λ₁ = (0.8·20 + 0.1·50) / 0.52 = 40.38...
        assertEquals(43 / 0.52, expected[0], 1e-9);
        assertEquals(21 / 0.52, expected[1], 1e-9);
        assertEquals(expected[0], mean(counts[0]), expected[0] * 0.04);
        assertEquals(expected[1], mean(counts[1]), expected[1] * 0.04);
    }

    @Test
    void sameSeedGivesTheSameEvents() {
        double[][] alpha = {{20, 40}, {10, 30}};
        HawkesProcess a = new HawkesProcess(new double[] {50, 20}, alpha, new double[] {100, 100}, 42);
        HawkesProcess b = new HawkesProcess(new double[] {50, 20}, alpha, new double[] {100, 100}, 42);

        for (int i = 0; i < 10_000; i++) {
            assertEquals(a.next(), b.next());
            assertEquals(a.time(), b.time());
        }
    }

    @Test
    void timeOnlyMovesForward() {
        HawkesProcess process = new HawkesProcess(new double[] {1000}, new double[][] {{900}}, new double[] {1000}, 4);
        double previous = 0;
        for (int i = 0; i < 100_000; i++) {
            process.next();
            assertTrue(process.time() > previous);
            previous = process.time();
        }
    }

    @Test
    void rejectsInvalidOrUnstableParameters() {
        double[] one = {1};
        assertThrows(IllegalArgumentException.class, () -> new HawkesProcess(new double[] {10}, new double[][] {{100}}, new double[] {100}, 1),
                "branching ratio of exactly 1 is unstable");
        assertThrows(IllegalArgumentException.class, () -> new HawkesProcess(new double[] {-1}, new double[][] {{0}}, one, 1));
        assertThrows(IllegalArgumentException.class, () -> new HawkesProcess(new double[] {0}, new double[][] {{0}}, one, 1));
        assertThrows(IllegalArgumentException.class, () -> new HawkesProcess(one, new double[][] {{0}}, new double[] {0}, 1));
        assertThrows(IllegalArgumentException.class, () -> new HawkesProcess(one, new double[][] {{-1}}, one, 1));
        assertThrows(IllegalArgumentException.class, () -> new HawkesProcess(new double[] {1, 1}, new double[][] {{0}}, new double[] {1, 1}, 1));
    }

    @Test
    void branchingMatrixDividesExcitationByTheReceivingDimensionsDecay() {
        HawkesProcess process = new HawkesProcess(new double[] {1, 1}, new double[][] {{10, 20}, {30, 40}}, new double[] {100, 200}, 1);

        double[][] g = process.branchingMatrix();

        assertArrayEquals(new double[] {0.1, 0.2}, g[0], 1e-12);
        assertArrayEquals(new double[] {0.15, 0.2}, g[1], 1e-12);
    }
}
