package obs.app;

import obs.core.OrderBook;
import obs.core.TradeListener;
import obs.workload.MessageTape;
import obs.workload.Workload;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.stream.Collectors;

/**
 * Proof of zero allocation that doesn't rely on a profiler. Run with {@code gradlew epsilonSmoke},
 * which uses Epsilon GC: it hands out memory and never collects, so the JVM dies as soon as the
 * heap fills. At 16 bytes per message, the default 200 million messages would need about 3 GB
 * against a 512 MB heap. Finishing at all shows the replay loop allocates nothing.
 */
public final class EpsilonSmokeMain {

    public static void main(String[] args) {
        long targetMessages = args.length > 0 ? Long.parseLong(args[0]) : 200_000_000L;

        String gcs = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", "));
        System.out.printf("GC: %s, max heap %,d MB%n", gcs.isEmpty() ? "Epsilon (no collector beans)" : gcs,
                Runtime.getRuntime().maxMemory() >> 20);

        MessageTape tape = MessageTape.generate(Workload.SEED, 500_000);
        long[] tradedQty = new long[1];
        OrderBook book = Workload.newFastBook((aggressorId, restingId, price, qty, side) -> tradedQty[0] += qty);
        Workload.prefill(book);

        long heapBefore = usedHeap();
        long start = System.nanoTime();
        long messages = 0;
        while (messages < targetMessages) {
            for (int i = 0; i < tape.length(); i++) tape.apply(book, i);
            messages += tape.length();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long heapAfter = usedHeap();

        System.out.printf("Replayed %,d messages in %.1f s (%,.0f messages/sec), %,d shares traded%n",
                messages, seconds, messages / seconds, tradedQty[0]);
        System.out.printf("Heap used: %,d KB before, %,d KB after: %,d bytes allocated across the whole run%n",
                heapBefore >> 10, heapAfter >> 10, heapAfter - heapBefore);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
