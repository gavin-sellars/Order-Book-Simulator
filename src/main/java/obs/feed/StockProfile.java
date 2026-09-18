package obs.feed;

import obs.core.OrderBook;
import obs.core.Prices;
import obs.core.TradeListener;
import obs.feed.itch.ItchFile;
import obs.feed.itch.ItchLayout;
import obs.feed.itch.ItchReader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of one stock's order flow in an ITCH file, and the fast book that fits it.
 *
 * A synthetic session knows its own price range and depth ({@link FlowConfig}); a real one has to
 * be measured. {@link #measure} replays the stock twice, outside any timing:
 * <ol>
 *   <li>The prices it traded at, and the most orders resting at once. The ladder window runs from
 *       20% below the lowest traded price to 25% above the highest, in whole cents (or in the feed's
 *       $0.0001 steps for a stock that traded under $1).</li>
 *   <li>With the window fixed, the most far price levels each side held at once, and how many order
 *       messages touched a far order.</li>
 * </ol>
 * Real books hold orders nowhere near the market: most busy Nasdaq stocks have resting orders from
 * $0.0001 to $199,999. Those go on the book's far levels ({@link OrderBook}). The pool and far
 * levels are sized like the synthetic session's pool: twice the most seen, rounded up to a power of two.
 */
public record StockProfile(String ticker, long messages, long orderMessages, long minPrice, long maxPrice,
                           long minTraded, long maxTraded, long ladderTick, long basePrice, int ladderLevels,
                           int maxLiveOrders, int maxFarLevels, long farOrderMessages) {

    /** Widest ladder a book is built with: 4,000,000 levels is about 160 MB of level arrays. */
    public static final int MAX_LEVELS = 4_000_000;

    /** One cent in {@link Prices} units. Nasdaq quotes stocks at $1 and over in whole cents. */
    public static final long CENT = 100;

    /** The feed's price precision, $0.0001: every positive ITCH price is a multiple of it. */
    public static final long PRICE_TICK = 1;

    /** Twice the most resting orders, rounded up to a power of two, as {@link FlowConfig#poolCapacity}. */
    public int poolCapacity() {
        return ceilPowerOfTwo(2 * maxLiveOrders);
    }

    /** Twice the most far levels either side held, rounded up to a power of two. */
    public int farCapacity() {
        return ceilPowerOfTwo(2 * maxFarLevels);
    }

    public long maxLadderPrice() {
        return basePrice + (ladderLevels - 1) * ladderTick;
    }

    public OrderBook newBook(TradeListener listener) {
        return new OrderBook(basePrice, ladderTick, ladderLevels, poolCapacity(), listener,
                OrderBook.TouchSearch.BITSET, PRICE_TICK, farCapacity());
    }

    private static int ceilPowerOfTwo(int n) {
        return Integer.highestOneBit(Math.max(8, n) - 1) << 1;
    }

    @Override
    public String toString() {
        return String.format("%s: %,d messages (%,d order messages); orders %s to %s, traded %s to %s; "
                        + "most resting %,d; ladder %s to %s (%,d levels), pool %,d, far levels %,d (most used %,d), "
                        + "%.3f%% of order messages on far levels",
                ticker, messages, orderMessages, Prices.format(minPrice), Prices.format(maxPrice),
                Prices.format(minTraded), Prices.format(maxTraded), maxLiveOrders, Prices.format(basePrice),
                Prices.format(maxLadderPrice()), ladderLevels, poolCapacity(), farCapacity(), maxFarLevels,
                100.0 * farOrderMessages / orderMessages);
    }

    /** Replays {@code ticker} twice and measures it. Allocates freely; call it before timing anything. */
    public static StockProfile measure(ItchReader reader, String ticker) {
        Pass first = new Pass(null);
        long messages = reader.replay(ticker, first);
        if (first.orderMessages == 0) throw new IllegalArgumentException("no order messages for " + ticker);

        Pass second = new Pass(first.ladder(MAX_LEVELS));
        reader.replay(ticker, second);
        return of(ticker, messages, first, second);
    }

    /**
     * Measures every stock in a whole day at once: two passes over the file, each message routed to
     * its stock by locate code. Returns profiles indexed by locate code; null where a stock has no
     * order messages. {@code maxLevels} caps each ladder, which bounds the memory of a book per stock.
     */
    public static StockProfile[] measureAll(ItchFile itch, int maxLevels) {
        String[] tickers = new String[1 << 16];
        long[] messages = new long[1 << 16];
        Pass[] first = new Pass[1 << 16];
        itch.forEach((m, type, length, locate) -> {
            if (type == ItchLayout.STOCK_DIRECTORY) {
                tickers[locate] = itch.alphaAt(m + ItchLayout.DIRECTORY_STOCK, 8);
                first[locate] = new Pass(null);
            } else if (isOrderMessage(type) && first[locate] != null) {
                messages[locate]++;
                itch.deliver(m, first[locate]);
            }
        });

        Pass[] second = new Pass[1 << 16];
        for (int l = 0; l < second.length; l++) {
            if (first[l] != null && first[l].orderMessages > 0) second[l] = new Pass(first[l].ladder(maxLevels));
        }
        itch.forEach((m, type, length, locate) -> {
            if (isOrderMessage(type) && second[locate] != null) itch.deliver(m, second[locate]);
        });

        StockProfile[] profiles = new StockProfile[1 << 16];
        for (int l = 0; l < profiles.length; l++) {
            if (second[l] != null) profiles[l] = of(tickers[l], messages[l], first[l], second[l]);
        }
        return profiles;
    }

    /** The message types a book replay applies, plus hidden trades: what ItchReader delivers for a stock. */
    public static boolean isOrderMessage(byte type) {
        return switch (type) {
            case ItchLayout.ADD_ORDER, ItchLayout.ADD_ORDER_MPID, ItchLayout.ORDER_EXECUTED,
                 ItchLayout.ORDER_EXECUTED_WITH_PRICE, ItchLayout.ORDER_CANCEL, ItchLayout.ORDER_DELETE,
                 ItchLayout.ORDER_REPLACE, ItchLayout.TRADE -> true;
            default -> false;
        };
    }

    private static StockProfile of(String ticker, long messages, Pass first, Pass second) {
        long[] ladder = second.ladder;
        return new StockProfile(ticker, messages, first.orderMessages, first.minPrice, first.maxPrice,
                first.lowTraded(), first.highTraded(), ladder[1], ladder[0], (int) ladder[2], first.maxLive,
                Math.max(second.maxFar[0], second.maxFar[1]), second.farMessages);
    }

    /** Follows every order through the day. With a ladder given, also tracks the far levels. */
    private static final class Pass implements MessageHandler {
        private record Live(byte side, long price, int[] shares) {}

        final long[] ladder;            // {base, tick, levels}, or null on the first pass
        final Map<Long, Live> live = new HashMap<>();
        final List<Map<Long, int[]>> farOrders = List.of(new HashMap<>(), new HashMap<>());   // per side: far price -> orders there
        final int[] maxFar = new int[2];
        long orderMessages;
        long farMessages;
        long minPrice = Long.MAX_VALUE;
        long maxPrice = Long.MIN_VALUE;
        long minTraded = Long.MAX_VALUE;
        long maxTraded = -1;
        long firstPrice = -1;
        int maxLive;

        Pass(long[] ladder) {
            this.ladder = ladder;
        }

        @Override
        public void onAdd(long ts, long ref, byte side, int shares, long price) {
            orderMessages++;
            if (isFar(price)) farMessages++;
            add(ref, side, shares, price);
        }

        @Override
        public void onExecute(long ts, long ref, int shares, long match, long price) {
            orderMessages++;
            Live order = live.get(ref);
            if (order == null) return;
            minTraded = Math.min(minTraded, order.price);
            maxTraded = Math.max(maxTraded, order.price);
            reduce(ref, order, shares);
        }

        @Override
        public void onCancel(long ts, long ref, int shares) {
            orderMessages++;
            Live order = live.get(ref);
            if (order != null) reduce(ref, order, shares);
        }

        @Override
        public void onDelete(long ts, long ref) {
            orderMessages++;
            Live order = live.get(ref);
            if (order == null) return;
            if (isFar(order.price)) farMessages++;
            remove(ref, order);
        }

        @Override
        public void onReplace(long ts, long oldRef, long newRef, int shares, long price) {
            orderMessages++;
            Live order = live.get(oldRef);
            if (order == null) return;
            if (isFar(order.price) || isFar(price)) farMessages++;
            remove(oldRef, order);
            add(newRef, order.side, shares, price);
        }

        private void add(long ref, byte side, int shares, long price) {
            live.put(ref, new Live(side, price, new int[] {shares}));
            maxLive = Math.max(maxLive, live.size());
            minPrice = Math.min(minPrice, price);
            maxPrice = Math.max(maxPrice, price);
            if (firstPrice < 0) firstPrice = price;
            if (isFar(price)) {
                int[] count = farOrders.get(side).computeIfAbsent(price, p -> new int[1]);
                if (count[0]++ == 0) maxFar[side] = Math.max(maxFar[side], farOrders.get(side).size());
            }
        }

        private void reduce(long ref, Live order, int shares) {
            if (isFar(order.price)) farMessages++;
            if ((order.shares[0] -= shares) <= 0) remove(ref, order);
        }

        private void remove(long ref, Live order) {
            live.remove(ref);
            if (!isFar(order.price)) return;
            Map<Long, int[]> side = farOrders.get(order.side);
            if (--side.get(order.price)[0] == 0) side.remove(order.price);
        }

        /** Lowest traded price, or the first order's price for a stock that never traded. */
        long lowTraded() {
            return minTraded == Long.MAX_VALUE ? firstPrice : minTraded;
        }

        long highTraded() {
            return maxTraded < 0 ? firstPrice : maxTraded;
        }

        /**
         * {base, tick, levels}: 20% below the lowest trade to 25% above the highest. If that needs
         * more than {@code maxLevels}, just the traded range; if that still does, as much of it as fits.
         */
        long[] ladder(int maxLevels) {
            long lo = lowTraded();
            long hi = highTraded();
            long low = lo * 4 / 5;
            long tick = low >= Prices.parse("1.00") ? CENT : PRICE_TICK;
            long[] window = window(low, hi + (hi + 3) / 4, tick);
            if (window[2] > maxLevels) window = window(lo, hi, tick);
            if (window[2] > maxLevels) window[2] = maxLevels;
            return window;
        }

        private static long[] window(long low, long high, long tick) {
            long base = Math.max(tick, low / tick * tick);
            long top = Math.max(base, (high + tick - 1) / tick * tick);
            return new long[] {base, tick, (top - base) / tick + 1};
        }

        private boolean isFar(long price) {
            if (ladder == null) return false;
            long offset = price - ladder[0];
            return offset < 0 || offset % ladder[1] != 0 || offset / ladder[1] >= ladder[2];
        }
    }
}
