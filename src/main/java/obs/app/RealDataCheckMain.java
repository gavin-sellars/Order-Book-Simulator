package obs.app;

import obs.core.Book;
import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.Prices;
import obs.core.Side;
import obs.core.TradeListener;
import obs.feed.BookBuilder;
import obs.feed.MessageHandler;
import obs.feed.StockProfile;
import obs.feed.itch.ItchReader;
import obs.ref.RefOrderBook;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Replays real ITCH data for each ticker into the fast book and the reference book side by side,
 * and checks they agree. Run with {@code gradlew realCheck -PcheckArgs="<file or dir> TICKER..."};
 * with a directory, each ticker is read from {@code <dir>/<TICKER>.itch}.
 *
 * After every order message both books must give the same touch, order count and acceptance, and
 * any execution must report the same resting order, price and size. Every {@value #DEPTH_EVERY}
 * messages the full depth of both sides is compared, and every {@value #VALIDATE_EVERY} the fast
 * book's whole structure is validated. Neither book may reject a message or meet an unknown order:
 * the file starts from an empty book, so either would mean the replay has drifted from Nasdaq's.
 *
 * Also reports how often the replayed book was locked or crossed, which a correctly rebuilt
 * Nasdaq book should almost never be.
 */
public final class RealDataCheckMain {

    private static final int DEPTH_EVERY = 1_000;
    private static final int VALIDATE_EVERY = 250_000;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("usage: RealDataCheckMain <file or dir> TICKER...");
        Path source = Path.of(args[0]);
        boolean failed = false;
        for (String ticker : Arrays.asList(args).subList(1, args.length)) {
            Path file = source.toFile().isDirectory() ? source.resolve(ticker + ".itch") : source;
            failed |= !check(file, ticker);
        }
        if (failed) System.exit(1);
    }

    private static boolean check(Path file, String ticker) throws IOException {
        ItchReader reader = ItchReader.open(file);
        StockProfile profile = StockProfile.measure(reader, ticker);
        System.out.println(profile);

        Pair pair = new Pair(profile);
        long start = System.nanoTime();
        reader.replay(ticker, pair);
        double seconds = (System.nanoTime() - start) / 1e9;
        BookValidator.validate(pair.fast, true);
        pair.compareDepth();

        boolean ok = pair.mismatches() == 0
                && pair.fastBuilder.rejectedMessages() == 0 && pair.refBuilder.rejectedMessages() == 0;
        System.out.printf("  %,d order messages checked in %.1f s: %,d touch/count mismatches, %,d acceptance mismatches, "
                        + "%,d of %,d executions mismatched, %,d of %,d depth comparisons mismatched%n",
                pair.fastBuilder.orderMessages(), seconds, pair.touchMismatches, pair.acceptMismatches,
                pair.tradeMismatches, pair.executions, pair.depthMismatches, pair.depthChecks);
        System.out.printf("  rejected: fast %,d, reference %,d; unknown orders: fast %,d, reference %,d; "
                        + "structure validated %,d times%n",
                pair.fastBuilder.rejectedMessages(), pair.refBuilder.rejectedMessages(),
                pair.fastBuilder.unknownRefMessages(), pair.refBuilder.unknownRefMessages(), pair.validations + 1);
        System.out.printf("  book locked after %,d messages and crossed after %,d, of %,d with both sides quoted%n",
                pair.locked, pair.crossed, pair.twoSided);
        System.out.printf("  end of day: %s / %s, %,d resting orders, %,d bid levels, %,d ask levels%n",
                touch(pair.fast.bestBid(), Book.NO_BID), touch(pair.fast.bestAsk(), Book.NO_ASK), pair.fast.orderCount(),
                pair.fast.levelCount(Side.BUY), pair.fast.levelCount(Side.SELL));
        System.out.println(ok ? "  MATCH" : "  MISMATCH");
        return ok;
    }

    /** Feeds each message to both books and compares them. */
    private static final class Pair implements MessageHandler {
        final OrderBook fast;
        final RefOrderBook ref;
        final BookBuilder fastBuilder;
        final BookBuilder refBuilder;
        final long[] fastTrade = new long[3];
        final long[] refTrade = new long[3];
        long touchMismatches;
        long acceptMismatches;
        long tradeMismatches;
        long executions;
        long depthChecks;
        long depthMismatches;
        long validations;
        long locked;
        long crossed;
        long twoSided;

        Pair(StockProfile profile) {
            fast = profile.newBook(recorder(fastTrade));
            ref = new RefOrderBook(StockProfile.PRICE_TICK, recorder(refTrade));
            fastBuilder = new BookBuilder(fast);
            refBuilder = new BookBuilder(ref);
        }

        private static TradeListener recorder(long[] last) {
            return (aggressorId, restingId, price, qty, side) -> {
                last[0] = restingId;
                last[1] = price;
                last[2] = qty;
            };
        }

        @Override
        public void onAdd(long ts, long ref, byte side, int shares, long price) {
            fastBuilder.onAdd(ts, ref, side, shares, price);
            refBuilder.onAdd(ts, ref, side, shares, price);
            after();
        }

        @Override
        public void onExecute(long ts, long orderRef, int shares, long match, long price) {
            Arrays.fill(fastTrade, -1);
            Arrays.fill(refTrade, -1);
            fastBuilder.onExecute(ts, orderRef, shares, match, price);
            refBuilder.onExecute(ts, orderRef, shares, match, price);
            executions++;
            if (!Arrays.equals(fastTrade, refTrade) || fastTrade[0] != orderRef) tradeMismatches++;
            after();
        }

        @Override
        public void onCancel(long ts, long orderRef, int shares) {
            fastBuilder.onCancel(ts, orderRef, shares);
            refBuilder.onCancel(ts, orderRef, shares);
            after();
        }

        @Override
        public void onDelete(long ts, long orderRef) {
            fastBuilder.onDelete(ts, orderRef);
            refBuilder.onDelete(ts, orderRef);
            after();
        }

        @Override
        public void onReplace(long ts, long oldRef, long newRef, int shares, long price) {
            fastBuilder.onReplace(ts, oldRef, newRef, shares, price);
            refBuilder.onReplace(ts, oldRef, newRef, shares, price);
            after();
        }

        private void after() {
            if (fast.bestBid() != ref.bestBid() || fast.bestAsk() != ref.bestAsk() || fast.orderCount() != ref.orderCount()) {
                touchMismatches++;
            }
            if (fastBuilder.rejectedMessages() != refBuilder.rejectedMessages()) acceptMismatches++;
            if (fast.bestBid() != Book.NO_BID && fast.bestAsk() != Book.NO_ASK) {
                twoSided++;
                if (fast.bestBid() == fast.bestAsk()) locked++;
                else if (fast.bestBid() > fast.bestAsk()) crossed++;
            }
            long n = fastBuilder.orderMessages();
            if (n % DEPTH_EVERY == 0) compareDepth();
            if (n % VALIDATE_EVERY == 0) {
                BookValidator.validate(fast, true);
                validations++;
            }
        }

        void compareDepth() {
            depthChecks++;
            for (byte side = Side.BUY; side <= Side.SELL; side++) {
                if (!Arrays.deepEquals(depthOf(fast, side), depthOf(ref, side))) {
                    depthMismatches++;
                    return;
                }
            }
        }

        long mismatches() {
            return touchMismatches + acceptMismatches + tradeMismatches + depthMismatches;
        }
    }

    private static String touch(long price, long none) {
        return price == none ? "none" : Prices.format(price);
    }

    private static Object[] depthOf(Book book, byte side) {
        int n = book.levelCount(side);
        long[] prices = new long[n];
        long[] qtys = new long[n];
        int[] counts = new int[n];
        book.depth(side, n, prices, qtys, counts);
        return new Object[] {prices, qtys, counts};
    }
}
