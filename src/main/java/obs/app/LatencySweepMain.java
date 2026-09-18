package obs.app;

import obs.core.Prices;
import obs.core.TradeListener;
import obs.feed.FlowConfig;
import obs.feed.StockProfile;
import obs.feed.SyntheticItchGenerator;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;
import obs.sim.LatencyModel;
import obs.sim.Simulation;
import obs.strategy.SampleMarketMaker;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the sample market maker on one session at several latencies and reports how P&L and fill
 * rate change (Part 6 of the guide). Run with
 * {@code gradlew latencySweep -PsweepArgs="[seed] [minutes]"} for a synthetic session, or
 * {@code gradlew latencySweep -PsweepArgs="real <file> <ticker>"} for a stock in a real ITCH file,
 * whose book is sized from a {@link StockProfile}.
 *
 * Writes pnl_vs_latency.csv (one row per latency) and pnl_timeseries.csv (P&L and position per
 * second, per latency) to build/reports/sim, or build/reports/sim/TICKER for a real stock;
 * tools/plot_pnl.py draws both.
 */
public final class LatencySweepMain {

    /** One-way latencies to test, in nanoseconds; jitter averages 10% of the base. */
    private static final long[] LATENCIES = {0, 10_000, 100_000, 1_000_000, 10_000_000, 50_000_000};
    private static final int QUOTE_SIZE = 100;
    private static final long MAX_POSITION = 1_000;
    private static final long MAKER_REBATE = 20;        // $0.0020 per share
    private static final long TAKER_FEE = 30;           // $0.0030 per share
    private static final long DEFAULT_SEED = 20260914L;

    public static void main(String[] args) throws IOException {
        Path dir;
        ItchReader reader;
        String ticker;
        long seed;
        Factory factory;

        if (args.length > 0 && args[0].equals("real")) {
            if (args.length < 3) throw new IllegalArgumentException("usage: LatencySweepMain real <file> <ticker>");
            Path file = Path.of(args[1]);
            ticker = args[2];
            seed = DEFAULT_SEED;
            dir = Path.of("build", "reports", "sim", ticker);
            reader = ItchReader.open(file);
            StockProfile profile = StockProfile.measure(reader, ticker);
            System.out.printf("Session: %s%n", profile);
            factory = (strategy, config) -> new Simulation(profile.newBook(TradeListener.NONE), strategy, config);
        } else {
            seed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_SEED;
            long minutes = args.length > 1 ? Long.parseLong(args[1]) : 390;
            FlowConfig flow = FlowConfig.defaults(seed).withDuration(minutes * 60 * FlowConfig.NANOS_PER_SECOND);
            ticker = flow.ticker();
            dir = Path.of("build", "reports", "sim");
            Files.createDirectories(dir);
            Path session = dir.resolve("session-" + seed + "-" + minutes + "m.itch");
            try (ItchWriter writer = ItchWriter.create(session, flow.stockLocate(), flow.ticker())) {
                SyntheticItchGenerator.Summary summary = SyntheticItchGenerator.generate(flow, writer, SyntheticItchGenerator.Observer.NONE);
                System.out.printf("Session: seed %d, %d minutes, %,d messages, %,d executions%n",
                        seed, minutes, summary.totalMessages(), summary.executionMessages());
            }
            reader = ItchReader.open(session);
            factory = (strategy, config) -> Simulation.forFlow(flow, strategy, config);
        }
        Files.createDirectories(dir);
        System.out.printf("Strategy: quote %d shares a side, position limit %,d, rebate $%s, fee $%s per share%n%n",
                QUOTE_SIZE, MAX_POSITION, Prices.format(MAKER_REBATE), Prices.format(TAKER_FEE));

        System.out.printf("%12s %12s %12s %10s %10s %9s %9s %9s %10s %9s%n",
                "latency", "P&L $", "fees $", "volume", "fills", "orders", "fill %", "rejected", "max |pos|", "run s");
        try (PrintWriter summaryCsv = new PrintWriter(Files.newBufferedWriter(dir.resolve("pnl_vs_latency.csv")));
             PrintWriter seriesCsv = new PrintWriter(Files.newBufferedWriter(dir.resolve("pnl_timeseries.csv")))) {
            summaryCsv.println("latency_ns,pnl_dollars,net_fees_dollars,volume,fills,orders_sent,fill_rate,share_fill_rate,max_abs_position,final_position");
            seriesCsv.println("latency_ns,seconds_since_open,pnl_dollars,position");

            for (long latencyNanos : LATENCIES) {
                LatencyModel latency = new LatencyModel(latencyNanos, latencyNanos, latencyNanos / 10, seed);
                Simulation simulation = factory.create(new SampleMarketMaker(QUOTE_SIZE, MAX_POSITION),
                        new Simulation.Config(latency, MAKER_REBATE, TAKER_FEE, FlowConfig.NANOS_PER_SECOND));

                long start = System.nanoTime();
                Simulation.Result r = simulation.run(reader, ticker);
                double runSeconds = (System.nanoTime() - start) / 1e9;

                System.out.printf("%12s %12s %12s %,10d %,10d %,9d %8.1f%% %,9d %,10d %9.2f%n",
                        label(latencyNanos), Prices.format(r.markToMarket()), Prices.format(r.netFees()), r.volume(),
                        r.fills(), r.ordersSent(), 100 * r.fillRate(), r.rejectedOrders(), r.maxAbsPosition(), runSeconds);
                summaryCsv.printf("%d,%s,%s,%d,%d,%d,%.6f,%.6f,%d,%d%n", latencyNanos, Prices.format(r.markToMarket()),
                        Prices.format(r.netFees()), r.volume(), r.fills(), r.ordersSent(), r.fillRate(), r.shareFillRate(),
                        r.maxAbsPosition(), r.position());
                for (Simulation.Sample s : r.samples()) {
                    seriesCsv.printf("%d,%d,%s,%d%n", latencyNanos, (s.time() - FlowConfig.MARKET_OPEN) / FlowConfig.NANOS_PER_SECOND,
                            Prices.format(s.markToMarket()), s.position());
                }
            }
        }
        System.out.printf("%nWrote %s and %s%n", dir.resolve("pnl_vs_latency.csv"), dir.resolve("pnl_timeseries.csv"));
    }

    /** Builds a fresh simulation for each latency. */
    @FunctionalInterface
    private interface Factory {
        Simulation create(SampleMarketMaker strategy, Simulation.Config config);
    }

    private static String label(long nanos) {
        if (nanos == 0) return "0";
        if (nanos < 1_000_000) return nanos / 1_000 + " us";
        return nanos / 1_000_000 + " ms";
    }
}
