package obs.feed;

import obs.core.Book;
import obs.core.OrderResult;
import obs.core.Side;
import obs.core.TradeListener;
import obs.feed.itch.ItchLayout;
import obs.feed.itch.ItchWriter;
import obs.ref.RefOrderBook;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Generates a synthetic trading session and writes it as Nasdaq ITCH 5.0.
 *
 * ITCH describes what the exchange did, not what participants sent, so the generator runs its own
 * matching engine. It uses the reference book, which keeps it independent of the fast book it is
 * used to test. Each Hawkes arrival becomes an order intent, the reference book matches it, and
 * the outcome becomes ITCH messages:
 * <ul>
 *   <li>passive order: 'A' (sometimes 'F', with a made-up participant id)</li>
 *   <li>marketable order: one 'E' per resting order it trades against, then an 'A' for any
 *       remainder that rests. An order that fills completely produces no 'A', exactly as on Nasdaq.</li>
 *   <li>delete: 'D'; partial cancel: 'X'</li>
 *   <li>replace: 'U', always to a passive price, so a replace never trades</li>
 * </ul>
 * The session is bracketed by system events (start of messages, system hours, market hours, and
 * their ends) and a stock directory entry marked as test data.
 *
 * Prices are placed relative to the current touch: mostly at or a few ticks behind it, sometimes
 * improving the spread. The number of resting orders is kept between the configured minimum and
 * maximum by turning a delete into an add, or an add into a delete, at the limits.
 */
public final class SyntheticItchGenerator {

    /** Hooks for tests and tools to see the reference book while the session is generated. */
    public interface Observer {
        Observer NONE = new Observer() { };

        /**
         * Called after each flow event has been fully written. {@code orderMessages} is the number of
         * order messages ('A', 'F', 'E', 'X', 'D', 'U') written so far.
         */
        default void afterEvent(long orderMessages, Book referenceBook) {}

        /** Each execution, in the order its 'E' message is written. */
        default void onExecution(long restingRef, int shares, long price, long matchNumber) {}
    }

    public record Summary(
            long events,
            long sessionMessages,
            long addMessages,
            long executionMessages,
            long cancelMessages,
            long deleteMessages,
            long replaceMessages,
            long sharesTraded,
            long skippedEvents,
            int restingOrders,
            long bestBid,
            long bestAsk) {

        public long orderMessages() {
            return addMessages + executionMessages + cancelMessages + deleteMessages + replaceMessages;
        }

        public long totalMessages() {
            return sessionMessages + orderMessages();
        }

        /** Cancel and delete messages per execution. Real equity markets are typically above 10. */
        public double cancelToTradeRatio() {
            return executionMessages == 0 ? Double.POSITIVE_INFINITY : (double) (cancelMessages + deleteMessages) / executionMessages;
        }
    }

    private static final double IMPROVE_SPREAD_PROBABILITY = 0.15;
    private static final double STOP_PROBABILITY = 0.35;     // chance a passive order stops at each tick back from the touch
    private static final int MAX_TICKS_AWAY = 20;
    private static final double MPID_PROBABILITY = 0.1;
    private static final byte[][] MPIDS = {
            ItchLayout.alpha("SIMA", 4), ItchLayout.alpha("SIMB", 4), ItchLayout.alpha("SIMC", 4)};
    private static final int ROUND_LOT = 100;

    private final FlowConfig config;
    private final ItchWriter writer;
    private final Observer observer;
    private final Fills fills = new Fills();
    private final RefOrderBook book;
    private final HawkesProcess arrivals;
    private final SplittableRandom random;

    // Orders this generator believes are resting; ones that have since filled are dropped lazily.
    private long[] liveRefs = new long[1 << 12];
    private byte[] liveSides = new byte[1 << 12];
    private int liveCount;

    private long nextRef = 1;
    private long matchNumber;
    private long lastTradePrice;
    private long lastTimestamp;

    private long events, addMessages, executionMessages, cancelMessages, deleteMessages, replaceMessages;
    private long sharesTraded, skippedEvents;

    private SyntheticItchGenerator(FlowConfig config, ItchWriter writer, Observer observer) {
        this.config = config;
        this.writer = writer;
        this.observer = observer;
        this.book = new RefOrderBook(config.tickSize(), fills);
        this.arrivals = new HawkesProcess(config.baseRates(), config.excitation(), config.decay(), config.seed());
        this.random = new SplittableRandom(config.seed() ^ 0x9E37_79B9_7F4A_7C15L);
        this.lastTradePrice = config.startPrice();
    }

    /** Writes a whole session and flushes the writer. The caller closes it. */
    public static Summary generate(FlowConfig config, ItchWriter writer, Observer observer) {
        return new SyntheticItchGenerator(config, writer, observer).run();
    }

    private Summary run() {
        long open = config.openNanos();
        long close = open + config.durationNanos();

        writer.systemEvent(open - 3 * FlowConfig.NANOS_PER_SECOND, ItchLayout.START_OF_MESSAGES);
        writer.stockDirectory(open - 2 * FlowConfig.NANOS_PER_SECOND, ROUND_LOT);
        writer.systemEvent(open - FlowConfig.NANOS_PER_SECOND, ItchLayout.START_OF_SYSTEM_HOURS);
        writer.systemEvent(open, ItchLayout.START_OF_MARKET_HOURS);
        lastTimestamp = open;

        while (true) {
            int stream = arrivals.next();
            long offset = (long) (arrivals.time() * FlowConfig.NANOS_PER_SECOND);
            if (offset >= config.durationNanos()) break;

            long timestamp = Math.max(lastTimestamp, open + offset);
            lastTimestamp = timestamp;
            events++;
            handle(stream, timestamp);
            observer.afterEvent(orderMessages(), book);
        }

        writer.systemEvent(close, ItchLayout.END_OF_MARKET_HOURS);
        writer.systemEvent(close + FlowConfig.NANOS_PER_SECOND, ItchLayout.END_OF_SYSTEM_HOURS);
        writer.systemEvent(close + 2 * FlowConfig.NANOS_PER_SECOND, ItchLayout.END_OF_MESSAGES);
        writer.flush();

        return new Summary(events, 7, addMessages, executionMessages, cancelMessages, deleteMessages, replaceMessages,
                sharesTraded, skippedEvents, book.orderCount(), book.bestBid(), book.bestAsk());
    }

    private long orderMessages() {
        return addMessages + executionMessages + cancelMessages + deleteMessages + replaceMessages;
    }

    private void handle(int stream, long ts) {
        int resting = book.orderCount();
        if (stream == FlowConfig.PASSIVE && resting >= config.maxLiveOrders()) {
            stream = FlowConfig.DELETE;
        } else if ((stream == FlowConfig.DELETE || stream == FlowConfig.MODIFY) && resting <= config.minLiveOrders()) {
            stream = FlowConfig.PASSIVE;
        }

        switch (stream) {
            case FlowConfig.PASSIVE -> addPassive(ts);
            case FlowConfig.MARKETABLE -> addMarketable(ts);
            case FlowConfig.DELETE -> deleteOrder(ts);
            default -> {
                if (random.nextBoolean()) partialCancel(ts);
                else replaceOrder(ts);
            }
        }
    }

    // ---------------------------------------------------------------- intents

    private void addPassive(long ts) {
        byte side = randomSide();
        long price = passivePrice(side);
        if (price < 0) {
            skippedEvents++;
            return;
        }
        submit(ts, side, price, restingSize());
    }

    private void addMarketable(long ts) {
        byte side = randomSide();
        long opposite = side == Side.BUY ? book.bestAsk() : book.bestBid();
        if (opposite == Book.NO_ASK || opposite == Book.NO_BID) {
            addPassive(ts);                             // nothing to trade against yet
            return;
        }
        long through = random.nextInt(3) * config.tickSize();
        long price = side == Side.BUY ? opposite + through : opposite - through;
        if (!inRange(price)) price = opposite;
        submit(ts, side, price, marketableSize());
    }

    private void deleteOrder(long ts) {
        int k = pickLive();
        if (k < 0) {
            skippedEvents++;
            return;
        }
        long ref = liveRefs[k];
        book.cancel(ref);
        writer.orderDelete(ts, ref);
        deleteMessages++;
        removeLive(k);
    }

    private void partialCancel(long ts) {
        int k = pickLive();
        if (k < 0) {
            skippedEvents++;
            return;
        }
        long ref = liveRefs[k];
        int remaining = book.restingQty(ref);
        if (remaining < 2) {
            deleteOrder(ts, k, ref);
            return;
        }
        int by = 1 + random.nextInt(remaining - 1);     // leaves at least one share
        book.reduce(ref, by);
        writer.orderCancel(ts, ref, by);
        cancelMessages++;
    }

    private void deleteOrder(long ts, int k, long ref) {
        book.cancel(ref);
        writer.orderDelete(ts, ref);
        deleteMessages++;
        removeLive(k);
    }

    private void replaceOrder(long ts) {
        int k = pickLive();
        if (k < 0) {
            skippedEvents++;
            return;
        }
        long oldRef = liveRefs[k];
        byte side = liveSides[k];
        long price = passivePrice(side);
        if (price < 0) {
            skippedEvents++;
            return;
        }
        int qty = restingSize();
        long newRef = nextRef++;
        int result = book.replace(oldRef, newRef, price, qty);
        if (result != OrderResult.ACCEPTED) throw new IllegalStateException("generator replace rejected: " + OrderResult.name(result));
        writer.orderReplace(ts, oldRef, newRef, qty, price);
        replaceMessages++;
        liveRefs[k] = newRef;
    }

    /** Matches an order in the reference book and writes what happened. */
    private void submit(long ts, byte side, long price, int qty) {
        long ref = nextRef++;
        fills.count = 0;
        int result = book.addLimitOrder(ref, side, price, qty);
        if (result != OrderResult.ACCEPTED) throw new IllegalStateException("generator order rejected: " + OrderResult.name(result));

        for (int i = 0; i < fills.count; i++) {
            long match = ++matchNumber;
            writer.orderExecuted(ts, fills.restingRef[i], fills.qty[i], match);
            executionMessages++;
            sharesTraded += fills.qty[i];
            lastTradePrice = fills.price[i];
            observer.onExecution(fills.restingRef[i], fills.qty[i], fills.price[i], match);
        }

        int rested = book.restingQty(ref);
        if (rested > 0) {
            if (random.nextDouble() < MPID_PROBABILITY) {
                writer.addOrderWithMpid(ts, ref, side, rested, price, MPIDS[random.nextInt(MPIDS.length)]);
            } else {
                writer.addOrder(ts, ref, side, rested, price);
            }
            addMessages++;
            addLive(ref, side);
        }
    }

    // ---------------------------------------------------------------- prices and sizes

    /** A price that rests without trading: at or behind this side's touch, or occasionally inside the spread. */
    private long passivePrice(byte side) {
        long tick = config.tickSize();
        long bid = book.bestBid();
        long ask = book.bestAsk();
        boolean haveBid = bid != Book.NO_BID;
        boolean haveAsk = ask != Book.NO_ASK;

        long price;
        if (haveBid && haveAsk && ask - bid > tick && random.nextDouble() < IMPROVE_SPREAD_PROBABILITY) {
            price = side == Side.BUY ? bid + tick : ask - tick;
        } else {
            long away = ticksAway() * tick;
            if (side == Side.BUY) {
                long best = haveBid ? bid : (haveAsk ? ask - tick : lastTradePrice - tick);
                price = best - away;
            } else {
                long best = haveAsk ? ask : (haveBid ? bid + tick : lastTradePrice + tick);
                price = best + away;
            }
        }

        if (side == Side.BUY && haveAsk && price >= ask) price = ask - tick;
        if (side == Side.SELL && haveBid && price <= bid) price = bid + tick;
        return inRange(price) ? price : -1;
    }

    private int ticksAway() {
        int k = 0;
        while (k < MAX_TICKS_AWAY && random.nextDouble() > STOP_PROBABILITY) k++;
        return k;
    }

    /** Mostly 1-5 round lots, some odd lots, occasionally a large order. */
    private int restingSize() {
        double r = random.nextDouble();
        if (r < 0.90) return ROUND_LOT * (1 + random.nextInt(5));
        if (r < 0.95) return 1 + random.nextInt(ROUND_LOT - 1);
        return ROUND_LOT * (10 + random.nextInt(41));
    }

    /** Mostly 1-3 round lots, sometimes 5-15, enough to take out a level or two. */
    private int marketableSize() {
        return random.nextDouble() < 0.90 ? ROUND_LOT * (1 + random.nextInt(3)) : ROUND_LOT * (5 + random.nextInt(11));
    }

    private byte randomSide() {
        return random.nextBoolean() ? Side.BUY : Side.SELL;
    }

    private boolean inRange(long price) {
        return price >= config.minPrice() && price <= config.maxPrice();
    }

    // ---------------------------------------------------------------- live order bookkeeping

    /** Index of a random order that is still resting, or -1. Orders that have filled are removed as they're found. */
    private int pickLive() {
        while (liveCount > 0) {
            int k = random.nextInt(liveCount);
            if (book.contains(liveRefs[k])) return k;
            removeLive(k);
        }
        return -1;
    }

    private void addLive(long ref, byte side) {
        if (liveCount == liveRefs.length) {
            liveRefs = Arrays.copyOf(liveRefs, liveCount * 2);
            liveSides = Arrays.copyOf(liveSides, liveCount * 2);
        }
        liveRefs[liveCount] = ref;
        liveSides[liveCount] = side;
        liveCount++;
    }

    private void removeLive(int k) {
        liveCount--;
        liveRefs[k] = liveRefs[liveCount];
        liveSides[k] = liveSides[liveCount];
    }

    /** Collects the reference book's fills for the order being matched. */
    private static final class Fills implements TradeListener {
        long[] restingRef = new long[64];
        int[] qty = new int[64];
        long[] price = new long[64];
        int count;

        @Override
        public void onTrade(long aggressorId, long restingId, long tradePrice, int tradeQty, byte aggressorSide) {
            if (count == restingRef.length) {
                restingRef = Arrays.copyOf(restingRef, count * 2);
                qty = Arrays.copyOf(qty, count * 2);
                price = Arrays.copyOf(price, count * 2);
            }
            restingRef[count] = restingId;
            qty[count] = tradeQty;
            price[count] = tradePrice;
            count++;
        }
    }
}
