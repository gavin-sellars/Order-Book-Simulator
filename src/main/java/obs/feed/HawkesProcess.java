package obs.feed;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A multivariate Hawkes process with exponential kernels, simulated exactly with Ogata's thinning.
 *
 * Dimension i has intensity
 * <pre>  λ_i(t) = μ_i + Σ_j Σ_{type-j events at t_k before t} α_ij · exp(−β_i (t − t_k))</pre>
 * so every type-j event raises type i's intensity by α_ij, and the boost decays at rate β_i.
 * Unlike a Poisson process, arrivals cluster (activity begets activity), which is what real order
 * flow does and what stresses a system in bursts.
 *
 * With exponential kernels the whole history sum is one number per dimension that just decays
 * between events, so each step costs O(dimensions), not O(history). And because intensities only
 * decay between events, the total intensity now bounds it for the rest of the wait, which is what
 * makes thinning exact rather than approximate.
 *
 * Time is in seconds from the start of the process; rates are events per second. Deterministic
 * for a given seed.
 */
public final class HawkesProcess {

    private final int dimensions;
    private final double[] baseRates;
    private final double[][] alpha;
    private final double[] decay;
    private final double[] excitation;          // the history sum for each dimension, as of `time`
    private final SplittableRandom random;
    private double time;

    /**
     * @param baseRates  μ_i, events per second with no recent activity
     * @param excitation α_ij, the jump in dimension i's rate caused by one event of dimension j
     * @param decay      β_i, how fast dimension i's excitation fades, per second
     */
    public HawkesProcess(double[] baseRates, double[][] excitation, double[] decay, long seed) {
        this.dimensions = baseRates.length;
        if (dimensions == 0 || excitation.length != dimensions || decay.length != dimensions) {
            throw new IllegalArgumentException("baseRates, excitation rows and decay must all have the same, non-zero length");
        }
        double totalBase = 0;
        for (int i = 0; i < dimensions; i++) {
            if (!(baseRates[i] >= 0) || !(decay[i] > 0) || excitation[i].length != dimensions) {
                throw new IllegalArgumentException("need baseRates >= 0, decay > 0 and a square excitation matrix");
            }
            for (double a : excitation[i]) {
                if (!(a >= 0)) throw new IllegalArgumentException("excitation must be non-negative");
            }
            totalBase += baseRates[i];
        }
        if (!(totalBase > 0)) throw new IllegalArgumentException("at least one base rate must be positive");

        this.baseRates = baseRates.clone();
        this.alpha = new double[dimensions][];
        for (int i = 0; i < dimensions; i++) this.alpha[i] = excitation[i].clone();
        this.decay = decay.clone();
        this.excitation = new double[dimensions];
        this.random = new SplittableRandom(seed);

        double bound = stabilityBound();
        if (bound >= 1) {
            throw new IllegalArgumentException("unstable: the branching matrix α_ij/β_i has row and column sums up to "
                    + bound + "; they must stay below 1 or activity grows without limit");
        }
    }

    /** Advances to the next event and returns its dimension. {@link #time()} is then that event's time. */
    public int next() {
        while (true) {
            double upper = totalIntensity();
            advance(-Math.log(1.0 - random.nextDouble()) / upper);
            double actual = totalIntensity();
            if (random.nextDouble() * upper <= actual) {            // accept with probability actual / upper
                int type = pickDimension(actual);
                for (int i = 0; i < dimensions; i++) excitation[i] += alpha[i][type];
                return type;
            }
        }
    }

    public double time() {
        return time;
    }

    public int dimensions() {
        return dimensions;
    }

    /** λ_i at the current time. */
    public double intensity(int dimension) {
        return baseRates[dimension] + excitation[dimension];
    }

    /** G_ij = α_ij / β_i: how many type-i events one type-j event triggers directly, on average. */
    public double[][] branchingMatrix() {
        double[][] g = new double[dimensions][dimensions];
        for (int i = 0; i < dimensions; i++) {
            for (int j = 0; j < dimensions; j++) g[i][j] = alpha[i][j] / decay[i];
        }
        return g;
    }

    /** Long-run average rate of each dimension: the solution of (I − G) Λ = μ. */
    public double[] stationaryRates() {
        double[][] g = branchingMatrix();
        double[][] a = new double[dimensions][dimensions + 1];      // augmented matrix [I − G | μ]
        for (int i = 0; i < dimensions; i++) {
            for (int j = 0; j < dimensions; j++) a[i][j] = (i == j ? 1 : 0) - g[i][j];
            a[i][dimensions] = baseRates[i];
        }
        for (int col = 0; col < dimensions; col++) {                // Gaussian elimination, partial pivoting
            int pivot = col;
            for (int r = col + 1; r < dimensions; r++) if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) pivot = r;
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            for (int r = 0; r < dimensions; r++) {
                if (r == col) continue;
                double factor = a[r][col] / a[col][col];
                for (int c = col; c <= dimensions; c++) a[r][c] -= factor * a[col][c];
            }
        }
        double[] rates = new double[dimensions];
        for (int i = 0; i < dimensions; i++) rates[i] = a[i][dimensions] / a[i][i];
        return rates;
    }

    private void advance(double dt) {
        time += dt;
        for (int i = 0; i < dimensions; i++) excitation[i] *= Math.exp(-decay[i] * dt);
    }

    private double totalIntensity() {
        double total = 0;
        for (int i = 0; i < dimensions; i++) total += baseRates[i] + excitation[i];
        return total;
    }

    private int pickDimension(double total) {
        double u = random.nextDouble() * total;
        for (int i = 0; i < dimensions - 1; i++) {
            u -= baseRates[i] + excitation[i];
            if (u < 0) return i;
        }
        return dimensions - 1;
    }

    /**
     * A sufficient condition for stability: the spectral radius of G is at most both its largest
     * row sum and its largest column sum, so if either is below 1 the process is stable.
     */
    private double stabilityBound() {
        double[][] g = branchingMatrix();
        double maxRow = 0;
        double maxColumn = 0;
        for (int i = 0; i < dimensions; i++) {
            maxRow = Math.max(maxRow, Arrays.stream(g[i]).sum());
            double column = 0;
            for (int r = 0; r < dimensions; r++) column += g[r][i];
            maxColumn = Math.max(maxColumn, column);
        }
        return Math.min(maxRow, maxColumn);
    }
}
