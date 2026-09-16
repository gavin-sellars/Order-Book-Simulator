package obs.workload;

import obs.core.Book;
import obs.core.Prices;
import obs.core.Side;
import obs.core.TradeListener;
import obs.ref.RefOrderBook;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A pre-generated sequence of order book messages for benchmarks and latency runs.
 *
 * Messages are stored as parallel primitive arrays and generated before any measurement, so
 * replaying does no generation work and allocates nothing. The flow is cancel-heavy, like real
 * order flow: about as many cancels as adds, because every order is eventually cancelled or
 * filled, plus a few reduces and crossing orders. {@code gradlew latency} prints the exact mix.
 *
 * The book stays at a realistic depth. The flow never has more than {@link #MAX_LIVE_FLOW_ORDERS}
 * orders resting on top of the prefilled ones, about the depth of the synthetic ITCH session; at
 * that limit an add becomes a cancel. Cancels and reduces only target orders that are still
 * resting: the generator runs the flow through a {@link RefOrderBook} as it goes, so it knows which
 * orders have filled. (An earlier version let resting orders pile up to about 87,000, with queues
 * thousands deep, which made the reference book's O(queue) cancel look far worse than it is on
 * realistic flow.)
 *
 * Every tape ends with a cleanup section that cancels every order the flow added and resets the
 * prefilled orders from {@link Workload#prefill} to their original queues. Replaying the whole
 * tape against a prefilled book leaves it exactly as it started, so a tape can loop forever
 * without the book drifting or the order pool filling up.
 */
public final class MessageTape {

    /** Limit order priced away from the mid. Usually rests. */
    public static final byte ADD = 0;
    /** Limit order priced through the mid. Usually trades. */
    public static final byte CROSS = 1;
    public static final byte CANCEL = 2;
    public static final byte REDUCE = 3;
    public static final int TYPES = 4;

    /** Most flow orders resting at once, on top of {@link Workload#PREFILL_ORDERS}. */
    public static final int MAX_LIVE_FLOW_ORDERS = 4_000;

    // Chances before the depth limit. Adds outnumber removals so the book grows until the limit
    // turns further adds into cancels; from then on adds and cancels come out about equal.
    private static final int ADD_PERCENT = 55;
    private static final int CANCEL_PERCENT = 36;
    private static final int REDUCE_PERCENT = 5;      // the remaining 4% cross

    private final byte[] type;
    private final byte[] side;
    private final long[] id;
    private final long[] price;
    private final int[] qty;
    private final int length;

    private MessageTape(byte[] type, byte[] side, long[] id, long[] price, int[] qty, int length) {
        this.type = type;
        this.side = side;
        this.id = id;
        this.price = price;
        this.qty = qty;
        this.length = length;
    }

    /** Applies message {@code i}. Returns the OrderResult code for adds, or 1/0 for cancels and reduces. */
    public int apply(Book book, int i) {
        return switch (type[i]) {
            case ADD, CROSS -> book.addLimitOrder(id[i], side[i], price[i], qty[i]);
            case CANCEL -> book.cancel(id[i]) ? 1 : 0;
            case REDUCE -> book.reduce(id[i], qty[i]) ? 1 : 0;
            default -> throw new IllegalStateException("bad message type " + type[i] + " at " + i);
        };
    }

    public int length() {
        return length;
    }

    public byte type(int i) {
        return type[i];
    }

    public byte side(int i) {
        return side[i];
    }

    public long id(int i) {
        return id[i];
    }

    public long price(int i) {
        return price[i];
    }

    public int qty(int i) {
        return qty[i];
    }

    public int count(byte messageType) {
        int n = 0;
        for (int i = 0; i < length; i++) if (type[i] == messageType) n++;
        return n;
    }

    public static String typeName(byte messageType) {
        return switch (messageType) {
            case ADD -> "limit add (passive)";
            case CROSS -> "limit add (crossing)";
            case CANCEL -> "cancel";
            case REDUCE -> "reduce";
            default -> "type " + messageType;
        };
    }

    /**
     * Generates {@code flowMessages} of random flow followed by the cleanup section, so the tape
     * is somewhat longer than flowMessages. The same seed always gives the same tape.
     */
    public static MessageTape generate(long seed, int flowMessages) {
        if (flowMessages <= 0) throw new IllegalArgumentException("flowMessages must be positive");

        Builder tape = new Builder(2 * flowMessages + 2 * Workload.PREFILL_ORDERS);
        SplittableRandom random = new SplittableRandom(seed);
        RefOrderBook book = Workload.newRefBook(TradeListener.NONE);     // knows which flow orders are still resting
        Workload.prefill(book);
        long[] live = new long[Math.min(flowMessages, MAX_LIVE_FLOW_ORDERS)];   // flow orders last seen resting
        int liveCount = 0;
        long nextId = Workload.FIRST_FLOW_ID;

        for (int n = 0; n < flowMessages; n++) {
            int roll = random.nextInt(100);
            byte s = random.nextBoolean() ? Side.BUY : Side.SELL;

            boolean adding = roll < ADD_PERCENT || roll >= ADD_PERCENT + CANCEL_PERCENT + REDUCE_PERCENT;
            if (adding && liveCount >= MAX_LIVE_FLOW_ORDERS) roll = ADD_PERCENT;     // at the depth limit: cancel instead

            int k = -1;
            if (roll >= ADD_PERCENT && roll < ADD_PERCENT + CANCEL_PERCENT + REDUCE_PERCENT) {
                while (liveCount > 0) {
                    int pick = random.nextInt(liveCount);
                    if (book.contains(live[pick])) {
                        k = pick;
                        break;
                    }
                    live[pick] = live[--liveCount];              // filled since it was added
                }
                if (k < 0) roll = 0;                             // nothing left to cancel: add instead
            }

            if (roll < ADD_PERCENT) {
                // Distance from the mid is roughly exponential, so most orders land near the touch.
                int ticks = 1 + Math.min(9, (int) (-Math.log(1.0 - random.nextDouble()) * 2.0));
                long px = s == Side.BUY ? Workload.MID - ticks * Prices.CENT : Workload.MID + ticks * Prices.CENT;
                int q = 100 * (1 + random.nextInt(5));
                tape.add(ADD, s, nextId, px, q);
                book.addLimitOrder(nextId, s, px, q);
                if (book.contains(nextId)) live[liveCount++] = nextId;
                nextId++;
            } else if (roll < ADD_PERCENT + CANCEL_PERCENT) {
                tape.add(CANCEL, s, live[k], 0, 0);
                book.cancel(live[k]);
                live[k] = live[--liveCount];
            } else if (roll < ADD_PERCENT + CANCEL_PERCENT + REDUCE_PERCENT) {
                tape.add(REDUCE, s, live[k], 0, 100);
                book.reduce(live[k], 100);
                if (!book.contains(live[k])) live[k] = live[--liveCount];
            } else {
                int ticks = 1 + random.nextInt(2);
                long px = s == Side.BUY ? Workload.MID + ticks * Prices.CENT : Workload.MID - ticks * Prices.CENT;
                int q = 100 * (1 + random.nextInt(5));
                tape.add(CROSS, s, nextId, px, q);
                book.addLimitOrder(nextId, s, px, q);
                if (book.contains(nextId)) live[liveCount++] = nextId;    // an unfilled remainder rests
                nextId++;
            }
        }

        // Cleanup: remove everything the flow left resting (a no-op for orders that have filled
        // since last checked), then take every prefilled order out and put it back in its original order.
        for (int k = 0; k < liveCount; k++) tape.add(CANCEL, Side.BUY, live[k], 0, 0);
        for (int p = 0; p < Workload.PREFILL_ORDERS; p++) tape.add(CANCEL, Side.BUY, Workload.prefillId(p), 0, 0);
        for (int p = 0; p < Workload.PREFILL_ORDERS; p++) {
            tape.add(ADD, Workload.prefillSide(p), Workload.prefillId(p), Workload.prefillPrice(p), Workload.PREFILL_QTY);
        }
        return tape.build();
    }

    private static final class Builder {
        private final byte[] type;
        private final byte[] side;
        private final long[] id;
        private final long[] price;
        private final int[] qty;
        private int length;

        Builder(int capacity) {
            type = new byte[capacity];
            side = new byte[capacity];
            id = new long[capacity];
            price = new long[capacity];
            qty = new int[capacity];
        }

        void add(byte t, byte s, long orderId, long px, int q) {
            type[length] = t;
            side[length] = s;
            id[length] = orderId;
            price[length] = px;
            qty[length] = q;
            length++;
        }

        MessageTape build() {
            return new MessageTape(Arrays.copyOf(type, length), Arrays.copyOf(side, length),
                    Arrays.copyOf(id, length), Arrays.copyOf(price, length), Arrays.copyOf(qty, length), length);
        }
    }
}
