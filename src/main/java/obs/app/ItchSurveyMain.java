package obs.app;

import obs.feed.itch.ItchFile;
import obs.mem.LongIntMap;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

import static obs.feed.itch.ItchLayout.*;

/**
 * One pass over a whole ITCH 5.0 day, every stock: message counts by type, and for each stock its
 * order messages, price range and the most orders it had resting at once. Those last two size a
 * fast book's ladder and pool. Run with {@code gradlew itchSurvey -PsurveyArgs="<file> [top]"}.
 *
 * Every order is tracked through the day (reference to stock and remaining shares), so the survey
 * also checks that the file is self-consistent: no message may refer to an order that isn't live.
 * Writes every stock's row to build/reports/real/survey.csv and prints the busiest.
 */
public final class ItchSurveyMain {

    private static final int LOCATES = 1 << 16;
    private static final int MAX_LIVE = 1 << 25;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) throw new IllegalArgumentException("usage: ItchSurveyMain <file> [top]");
        Path file = Path.of(args[0]);
        int top = args.length > 1 ? Integer.parseInt(args[1]) : 25;

        Survey survey = new Survey();
        long start = System.nanoTime();
        long messages;
        try (ItchFile itch = ItchFile.open(file)) {
            survey.itch = itch;
            messages = itch.forEach(survey);
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("%s: %,d bytes, %,d messages, surveyed in %.1f s (%,.0f messages/sec, %.0f MB/s)%n",
                file, Files.size(file), messages, seconds, messages / seconds, Files.size(file) / seconds / 1e6);
        System.out.print("By type:");
        for (int t = 0; t < 128; t++) {
            if (survey.byType[t] > 0) System.out.printf(" %c=%,d", (char) t, survey.byType[t]);
        }
        System.out.println();
        long orderMessages = Arrays.stream(survey.orderMessages).sum();
        System.out.printf("%,d stocks in the directory, %,d with order messages; %,d order messages in total%n",
                survey.directoryEntries, IntStream.range(0, LOCATES).filter(l -> survey.orderMessages[l] > 0).count(),
                orderMessages);
        System.out.printf("Most orders resting at once, all stocks: %,d. Resting at the end of the file: %,d%n",
                survey.maxLiveTotal, survey.liveTotal);
        System.out.printf("Messages referring to an order that isn't live: %,d; executions/cancels larger than the order: %,d%n",
                survey.unknownRefs, survey.oversized);

        Integer[] busiest = IntStream.range(0, LOCATES).filter(l -> survey.orderMessages[l] > 0).boxed()
                .sorted(Comparator.comparingLong((Integer l) -> survey.orderMessages[l]).reversed())
                .toArray(Integer[]::new);

        System.out.printf("%nBusiest %d stocks by order messages:%n", Math.min(top, busiest.length));
        System.out.printf("%-8s %6s %12s %11s %10s %11s %10s %10s %9s %13s %13s %9s%n", "ticker", "locate",
                "order msgs", "adds", "execs", "deletes", "cancels", "replaces", "max live", "min price", "max price", "off-cent");
        for (int i = 0; i < Math.min(top, busiest.length); i++) {
            int l = busiest[i];
            System.out.printf("%-8s %6d %,12d %,11d %,10d %,11d %,10d %,10d %,9d %13s %13s %,9d%n", survey.tickers[l], l,
                    survey.orderMessages[l], survey.adds[l], survey.executions[l], survey.deletes[l], survey.cancels[l],
                    survey.replaces[l], survey.maxLive[l], price(survey.minPrice[l]), price(survey.maxPrice[l]),
                    survey.offCent[l]);
        }

        Path csv = Path.of("build/reports/real/survey.csv");
        Files.createDirectories(csv.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(csv))) {
            out.println("ticker,locate,order_messages,adds,executions,deletes,cancels,replaces,trades,max_live,min_price,max_price,off_cent_prices");
            for (int l : busiest) {
                out.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n", survey.tickers[l], l, survey.orderMessages[l],
                        survey.adds[l], survey.executions[l], survey.deletes[l], survey.cancels[l], survey.replaces[l],
                        survey.trades[l], survey.maxLive[l], survey.minPrice[l], survey.maxPrice[l], survey.offCent[l]);
            }
        }
        System.out.printf("%nWrote %s%n", csv);
    }

    private static String price(long p) {
        return p == Long.MAX_VALUE || p < 0 ? "-" : String.format("%d.%04d", p / 10_000, p % 10_000);
    }

    private static final class Survey implements ItchFile.Visitor {
        ItchFile itch;
        final long[] byType = new long[128];
        final String[] tickers = new String[LOCATES];
        final long[] orderMessages = new long[LOCATES];
        final long[] adds = new long[LOCATES];
        final long[] executions = new long[LOCATES];
        final long[] deletes = new long[LOCATES];
        final long[] cancels = new long[LOCATES];
        final long[] replaces = new long[LOCATES];
        final long[] trades = new long[LOCATES];
        final long[] offCent = new long[LOCATES];
        final int[] live = new int[LOCATES];
        final int[] maxLive = new int[LOCATES];
        final long[] minPrice = new long[LOCATES];
        final long[] maxPrice = new long[LOCATES];
        int directoryEntries;
        long liveTotal;
        long maxLiveTotal;
        long unknownRefs;
        long oversized;

        // Every live order: reference -> slot, and each slot's stock and remaining shares.
        final LongIntMap refToSlot = new LongIntMap(MAX_LIVE);
        final int[] slotLocate = new int[MAX_LIVE];
        final int[] slotShares = new int[MAX_LIVE];
        final int[] freeSlots = new int[MAX_LIVE];
        int freeCount;
        int nextSlot;

        Survey() {
            Arrays.fill(tickers, "");
            Arrays.fill(minPrice, Long.MAX_VALUE);
            Arrays.fill(maxPrice, -1);
        }

        @Override
        public void message(long m, byte type, int length, int locate) {
            byType[type & 0x7F]++;
            switch (type) {
                case STOCK_DIRECTORY -> {
                    tickers[locate] = itch.alphaAt(m + DIRECTORY_STOCK, 8);
                    directoryEntries++;
                }
                case ADD_ORDER, ADD_ORDER_MPID -> {
                    orderMessages[locate]++;
                    adds[locate]++;
                    add(itch.longAt(m + ORDER_REF), locate, itch.intAt(m + ADD_SHARES), itch.priceAt(m + ADD_PRICE));
                }
                case ORDER_EXECUTED, ORDER_EXECUTED_WITH_PRICE -> {
                    orderMessages[locate]++;
                    executions[locate]++;
                    reduce(itch.longAt(m + ORDER_REF), itch.intAt(m + EXECUTED_SHARES));
                }
                case ORDER_CANCEL -> {
                    orderMessages[locate]++;
                    cancels[locate]++;
                    reduce(itch.longAt(m + ORDER_REF), itch.intAt(m + CANCELLED_SHARES));
                }
                case ORDER_DELETE -> {
                    orderMessages[locate]++;
                    deletes[locate]++;
                    int slot = refToSlot.get(itch.longAt(m + ORDER_REF));
                    if (slot == LongIntMap.NOT_FOUND) unknownRefs++; else remove(itch.longAt(m + ORDER_REF), slot);
                }
                case ORDER_REPLACE -> {
                    orderMessages[locate]++;
                    replaces[locate]++;
                    long oldRef = itch.longAt(m + REPLACE_ORIGINAL_REF);
                    int slot = refToSlot.get(oldRef);
                    if (slot == LongIntMap.NOT_FOUND) {
                        unknownRefs++;
                    } else {
                        remove(oldRef, slot);
                    }
                    add(itch.longAt(m + REPLACE_NEW_REF), locate, itch.intAt(m + REPLACE_SHARES), itch.priceAt(m + REPLACE_PRICE));
                }
                case TRADE -> trades[locate]++;
                default -> { }
            }
        }

        private void add(long ref, int locate, int shares, long price) {
            int slot = freeCount > 0 ? freeSlots[--freeCount] : nextSlot++;
            slotLocate[slot] = locate;
            slotShares[slot] = shares;
            refToSlot.put(ref, slot);
            if (++live[locate] > maxLive[locate]) maxLive[locate] = live[locate];
            if (++liveTotal > maxLiveTotal) maxLiveTotal = liveTotal;
            if (price < minPrice[locate]) minPrice[locate] = price;
            if (price > maxPrice[locate]) maxPrice[locate] = price;
            if (price % 100 != 0) offCent[locate]++;
        }

        private void reduce(long ref, int shares) {
            int slot = refToSlot.get(ref);
            if (slot == LongIntMap.NOT_FOUND) {
                unknownRefs++;
                return;
            }
            if (shares > slotShares[slot]) oversized++;
            slotShares[slot] -= shares;
            if (slotShares[slot] <= 0) remove(ref, slot);
        }

        private void remove(long ref, int slot) {
            refToSlot.remove(ref);
            live[slotLocate[slot]]--;
            liveTotal--;
            freeSlots[freeCount++] = slot;
        }
    }
}
