package obs.workload;

import obs.core.Book;
import obs.core.Prices;
import obs.core.Side;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A pre-generated sequence of order book messages for benchmarks and latency runs.
 *
 * Messages are stored as parallel primitive arrays and generated before any measurement, so
 * replaying does no generation work and allocates nothing. The flow is cancel-heavy, like real
 * order flow: 48% limit adds away from the touch, 42% cancels, 5% reduces, 5% crossing orders.
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

    private static final int ADD_PERCENT = 48;
    private static final int CANCEL_PERCENT = 42;
    private static final int REDUCE_PERCENT = 5;      // the remaining 5% cross

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
        long[] live = new long[flowMessages];     // ids the flow added and hasn't cancelled (some may have filled)
        int liveCount = 0;
        long nextId = Workload.FIRST_FLOW_ID;

        for (int n = 0; n < flowMessages; n++) {
            int roll = random.nextInt(100);
            byte s = random.nextBoolean() ? Side.BUY : Side.SELL;

            if (roll < ADD_PERCENT || liveCount == 0) {
                // Distance from the mid is roughly exponential, so most orders land near the touch.
                int ticks = 1 + Math.min(9, (int) (-Math.log(1.0 - random.nextDouble()) * 2.0));
                long px = s == Side.BUY ? Workload.MID - ticks * Prices.CENT : Workload.MID + ticks * Prices.CENT;
                tape.add(ADD, s, nextId, px, 100 * (1 + random.nextInt(5)));
                live[liveCount++] = nextId++;
            } else if (roll < ADD_PERCENT + CANCEL_PERCENT) {
                int k = random.nextInt(liveCount);
                tape.add(CANCEL, s, live[k], 0, 0);
                live[k] = live[--liveCount];
            } else if (roll < ADD_PERCENT + CANCEL_PERCENT + REDUCE_PERCENT) {
                tape.add(REDUCE, s, live[random.nextInt(liveCount)], 0, 100);
            } else {
                int ticks = 1 + random.nextInt(2);
                long px = s == Side.BUY ? Workload.MID + ticks * Prices.CENT : Workload.MID - ticks * Prices.CENT;
                tape.add(CROSS, s, nextId, px, 100 * (1 + random.nextInt(5)));
                live[liveCount++] = nextId++;    // any unfilled remainder rests
            }
        }

        // Cleanup: remove everything the flow added (a no-op for orders that already filled),
        // then take every prefilled order out and put it back in its original order.
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
