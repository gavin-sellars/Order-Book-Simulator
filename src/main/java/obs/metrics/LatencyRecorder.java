package obs.metrics;

import org.HdrHistogram.Histogram;

/**
 * Latency percentiles backed by HdrHistogram: constant memory, constant recording cost, and no
 * allocation after construction. Report percentiles and the max, never a mean (Part 7 of the guide).
 */
public final class LatencyRecorder {

    private static final long DEFAULT_HIGHEST_NANOS = 10_000_000_000L;      // 10 seconds

    private final String name;
    private final long highestNanos;
    private final Histogram histogram;

    public LatencyRecorder(String name) {
        this(name, DEFAULT_HIGHEST_NANOS);
    }

    public LatencyRecorder(String name, long highestTrackableNanos) {
        this.name = name;
        this.highestNanos = highestTrackableNanos;
        this.histogram = new Histogram(1, highestTrackableNanos, 3);     // 3 significant digits
    }

    /** Records one latency in nanoseconds. Values outside the trackable range are clamped, not rejected. */
    public void record(long nanos) {
        histogram.recordValue(Math.clamp(nanos, 1L, highestNanos));
    }

    /**
     * Records latency measured from when the operation was <em>supposed</em> to start. If the
     * system stalls, every operation scheduled during the stall is charged for its wait. Measuring
     * from the actual start instead hides those waits: that is coordinated omission.
     */
    public void recordSinceIntendedStart(long intendedStartNanos, long endNanos) {
        record(endNanos - intendedStartNanos);
    }

    public String name() {
        return name;
    }

    public long count() {
        return histogram.getTotalCount();
    }

    public long percentile(double percentile) {
        return histogram.getValueAtPercentile(percentile);
    }

    public long max() {
        return histogram.getMaxValue();
    }

    public void reset() {
        histogram.reset();
    }

    public static String tableHeader() {
        return String.format("%-26s %12s %9s %9s %9s %10s %12s",
                "latency in ns", "count", "p50", "p99", "p99.9", "p99.99", "max");
    }

    public String tableRow() {
        return String.format("%-26s %,12d %,9d %,9d %,9d %,10d %,12d",
                name, count(), percentile(50), percentile(99), percentile(99.9), percentile(99.99), max());
    }
}
