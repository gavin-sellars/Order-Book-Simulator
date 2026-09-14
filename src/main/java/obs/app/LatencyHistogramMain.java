package obs.app;

import obs.core.Book;
import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.metrics.LatencyRecorder;
import obs.workload.MessageTape;
import obs.workload.Workload;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.stream.Collectors;

/**
 * Latency percentiles per message type for the fast and reference books, replay throughput, and
 * a demonstration of coordinated omission. Run with {@code gradlew latency}.
 *
 * Per-message timing wraps each call in System.nanoTime(), whose own cost and resolution are
 * printed first: on some platforms they are a large fraction of a fast operation. JMH gives the
 * more precise average cost per operation.
 */
public final class LatencyHistogramMain {

    private static final int FLOW_MESSAGES = 1_000_000;
    private static final int WARMUP_CYCLES = 5;

    public static void main(String[] args) throws InterruptedException {
        int cycles = args.length > 0 ? Integer.parseInt(args[0]) : 10;

        printEnvironment();
        MessageTape tape = MessageTape.generate(Workload.SEED, FLOW_MESSAGES);
        System.out.printf("Tape: %,d messages per cycle (%s). %d warm-up cycles, then %d measured.%n%n",
                tape.length(), mix(tape), WARMUP_CYCLES, cycles);

        for (String impl : new String[] {"fast", "ref"}) {
            measureBook(impl, tape, cycles);
        }
        coordinatedOmissionDemo(tape);
    }

    private static void measureBook(String impl, MessageTape tape, int cycles) {
        Book book = Workload.newBook(impl, TradeListener.NONE);
        Workload.prefill(book);
        for (int c = 0; c < WARMUP_CYCLES; c++) replay(book, tape);

        long start = System.nanoTime();
        for (int c = 0; c < cycles; c++) replay(book, tape);
        double seconds = (System.nanoTime() - start) / 1e9;
        long messages = (long) cycles * tape.length();

        LatencyRecorder[] byType = new LatencyRecorder[MessageTape.TYPES];
        for (byte t = 0; t < MessageTape.TYPES; t++) byType[t] = new LatencyRecorder(MessageTape.typeName(t));
        LatencyRecorder all = new LatencyRecorder("all messages");

        for (int c = 0; c < cycles; c++) {
            for (int i = 0; i < tape.length(); i++) {
                long t0 = System.nanoTime();
                tape.apply(book, i);
                long elapsed = System.nanoTime() - t0;
                byType[tape.type(i)].record(elapsed);
                all.record(elapsed);
            }
        }

        System.out.printf("== %s book ==%n", impl);
        System.out.printf("Throughput (untimed replay): %,.0f messages/sec, %.1f ns/message average%n",
                messages / seconds, seconds * 1e9 / messages);
        System.out.println("Service time per message (each call wrapped in nanoTime):");
        System.out.println(LatencyRecorder.tableHeader());
        for (LatencyRecorder r : byType) System.out.println(r.tableRow());
        System.out.println(all.tableRow());
        System.out.println();
    }

    /**
     * Sends messages on a fixed schedule and stalls once for 50 ms, standing in for a GC pause.
     * Service time measured from when each message actually started sees one slow sample.
     * Response time measured from when each message was due to start sees every message that
     * queued behind the stall.
     */
    private static void coordinatedOmissionDemo(MessageTape tape) throws InterruptedException {
        final long rate = 100_000;                          // messages per second
        final long interval = 1_000_000_000L / rate;
        final int total = 1_000_000;                        // 10 seconds
        final int stallAt = total / 2;
        final long stallMillis = 50;

        OrderBook book = Workload.newFastBook(TradeListener.NONE);
        Workload.prefill(book);
        for (int c = 0; c < WARMUP_CYCLES; c++) replay(book, tape);

        LatencyRecorder service = new LatencyRecorder("service time (naive)");
        LatencyRecorder response = new LatencyRecorder("response time (intended)");
        int cursor = 0;
        long start = System.nanoTime() + 10_000_000;

        for (int n = 0; n < total; n++) {
            long intended = start + n * interval;
            long begin;
            while ((begin = System.nanoTime()) < intended) Thread.onSpinWait();     // behind schedule: don't wait

            if (n == stallAt) Thread.sleep(stallMillis);
            tape.apply(book, cursor);
            if (++cursor == tape.length()) cursor = 0;
            long end = System.nanoTime();

            service.record(end - begin);
            response.recordSinceIntendedStart(intended, end);
        }

        System.out.printf("== Coordinated omission: fast book, %,d messages/sec, one %d ms stall ==%n", rate, stallMillis);
        System.out.println(LatencyRecorder.tableHeader());
        System.out.println(service.tableRow());
        System.out.println(response.tableRow());
        System.out.printf("The stall delayed about %,d messages; only the response-time histogram shows them.%n",
                stallMillis * rate / 1000);
    }

    private static void replay(Book book, MessageTape tape) {
        for (int i = 0; i < tape.length(); i++) tape.apply(book, i);
    }

    private static String mix(MessageTape tape) {
        StringBuilder sb = new StringBuilder();
        for (byte t = 0; t < MessageTape.TYPES; t++) {
            if (t > 0) sb.append(", ");
            sb.append(String.format("%.0f%% %s", 100.0 * tape.count(t) / tape.length(), MessageTape.typeName(t)));
        }
        return sb.toString();
    }

    private static void printEnvironment() {
        Runtime runtime = Runtime.getRuntime();
        String gcs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", "));

        System.out.printf("JVM:      %s %s%n", System.getProperty("java.vm.name"), System.getProperty("java.runtime.version"));
        System.out.printf("OS:       %s %s (%s), %d logical CPUs%n", System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch"), runtime.availableProcessors());
        System.out.printf("Heap:     %,d MB max; GC: %s%n", runtime.maxMemory() >> 20, gcs);
        System.out.printf("JVM args: %s%n", ManagementFactory.getRuntimeMXBean().getInputArguments());

        int calls = 5_000_000;
        long smallestStep = Long.MAX_VALUE;
        long first = System.nanoTime();
        long previous = first;
        for (int i = 0; i < calls; i++) {
            long now = System.nanoTime();
            if (now > previous && now - previous < smallestStep) smallestStep = now - previous;
            previous = now;
        }
        System.out.printf("nanoTime: %.1f ns per call, smallest observed step %d ns%n%n",
                (double) (previous - first) / calls, smallestStep);
    }
}
