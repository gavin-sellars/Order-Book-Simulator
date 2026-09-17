package obs.core;

/**
 * Walks an entire {@link OrderBook} and checks every structural invariant (Part 8 of the guide).
 * Costs O(levels + orders): call it after every message in tests, never in benchmarks.
 *
 * Throws IllegalStateException describing the first violation. Uses explicit checks rather than
 * {@code assert}, so it works whether or not assertions are enabled.
 */
public final class BookValidator {

    private BookValidator() {}

    /** @param allowCrossed pass false for a book driven only in matching mode, where crossing is a bug */
    public static void validate(OrderBook book, boolean allowCrossed) {
        if (!allowCrossed && book.isCrossed()) {
            fail("crossed book: best bid %d >= best ask %d", book.bestBid(), book.bestAsk());
        }

        int orders = validateSide(book, Side.BUY) + validateSide(book, Side.SELL);

        if (orders != book.pool.liveCount()) {
            fail("pool has %d live slots but the levels hold %d orders", book.pool.liveCount(), orders);
        }
        if (orders != book.idToSlot.size()) {
            fail("id map has %d entries but the levels hold %d orders", book.idToSlot.size(), orders);
        }
    }

    /** Checks one side, ladder and far levels, and returns the number of orders on it. */
    private static int validateSide(OrderBook book, byte side) {
        final int empty = OrderBook.EMPTY;
        String name = Side.name(side);
        int[] head = book.levelHead[side];
        int[] tail = book.levelTail[side];
        long[] qty = book.levelQty[side];
        int[] count = book.levelCount[side];

        int best = empty;
        int nonEmpty = 0;
        int orders = 0;

        for (int idx = 0; idx < book.levels; idx++) {
            boolean isEmpty = head[idx] == empty;
            if (isEmpty == book.occupied[side].get(idx)) {
                fail("%s level %d: bitset says %s but head is %d", name, idx, isEmpty ? "occupied" : "empty", head[idx]);
            }
            if (isEmpty) {
                if (tail[idx] != empty || qty[idx] != 0 || count[idx] != 0) {
                    fail("%s level %d: empty but tail=%d qty=%d count=%d", name, idx, tail[idx], qty[idx], count[idx]);
                }
                continue;
            }

            nonEmpty++;
            if (side == Side.BUY || best == empty) best = idx;      // bids: highest level; asks: lowest
            orders += validateQueue(book, side, idx);
        }

        orders += validateFarLevels(book, side);
        nonEmpty += book.farCount[side];

        int recordedBest = side == Side.BUY ? book.bestBidIdx : book.bestAskIdx;
        if (recordedBest != best) fail("%s touch is level %d but the best non-empty level is %d", name, recordedBest, best);
        if (nonEmpty != book.nonEmptyLevels[side]) {
            fail("%s has %d non-empty levels but the counter says %d", name, nonEmpty, book.nonEmptyLevels[side]);
        }
        return orders;
    }

    /** Checks one side's far levels: sorted, distinct, valid, off the ladder, non-empty. Returns their orders. */
    private static int validateFarLevels(OrderBook book, byte side) {
        String name = Side.name(side);
        int count = book.farCount[side];
        if (count < 0 || count > book.farCapacity) fail("%s far level count %d out of range", name, count);

        boolean[] live = new boolean[book.farCapacity];
        long previous = Long.MIN_VALUE;
        int orders = 0;
        for (int k = 0; k < count; k++) {
            int far = book.farSorted[side][k];
            if (far < 0 || far >= book.farCapacity || live[far]) fail("%s far slot %d repeated or out of range", name, far);
            live[far] = true;

            long price = book.farPrice[side][far];
            if (price <= previous) fail("%s far levels out of price order at %d", name, price);
            previous = price;
            if (price <= 0 || price % book.priceTick != 0) fail("%s far level price %d is invalid", name, price);
            long offset = price - book.basePrice;
            if (offset >= 0 && offset % book.tickSize == 0 && offset / book.tickSize < book.levels) {
                fail("%s far level at %d belongs on the ladder", name, price);
            }

            int idx = book.levels + far;
            if (book.levelHead[side][idx] == OrderBook.EMPTY) fail("%s far level at %d is empty", name, price);
            orders += validateQueue(book, side, idx);
        }

        for (int far = 0; far < book.farCapacity; far++) {
            int idx = book.levels + far;
            if (!live[far] && (book.levelHead[side][idx] != OrderBook.EMPTY || book.levelTail[side][idx] != OrderBook.EMPTY
                    || book.levelQty[side][idx] != 0 || book.levelCount[side][idx] != 0)) {
                fail("%s free far slot %d is not empty", name, far);
            }
        }
        return orders;
    }

    /** Checks a non-empty level's queue: links, sides, time priority, map entries and cached totals. Returns its orders. */
    private static int validateQueue(OrderBook book, byte side, int idx) {
        final int empty = OrderBook.EMPTY;
        OrderPool pool = book.pool;
        String name = Side.name(side);

        long sum = 0;
        int n = 0;
        int prev = empty;
        long prevSeq = Long.MIN_VALUE;
        for (int s = book.levelHead[side][idx]; s != empty; s = pool.next[s]) {
            if (s < 0 || s >= pool.capacity()) fail("%s level %d: slot index %d out of range", name, idx, s);
            if (++n > pool.capacity()) fail("%s level %d: cycle in the order list", name, idx);
            if (pool.prev[s] != prev) fail("%s level %d: slot %d back-link is %d, expected %d", name, idx, s, pool.prev[s], prev);
            if (pool.side[s] != side) fail("%s level %d: slot %d has side %d", name, idx, s, pool.side[s]);
            if (pool.levelIdx[s] != idx) fail("%s level %d: slot %d thinks it is at level %d", name, idx, s, pool.levelIdx[s]);
            if (pool.qty[s] <= 0) fail("%s level %d: slot %d has quantity %d", name, idx, s, pool.qty[s]);
            if (pool.seq[s] <= prevSeq) fail("%s level %d: slot %d is out of time priority", name, idx, s);
            int mapped = book.idToSlot.get(pool.id[s]);
            if (mapped != s) fail("%s level %d: id %d maps to slot %d, not %d", name, idx, pool.id[s], mapped, s);

            sum += pool.qty[s];
            prevSeq = pool.seq[s];
            prev = s;
        }
        if (book.levelTail[side][idx] != prev) {
            fail("%s level %d: tail is %d but the last order is slot %d", name, idx, book.levelTail[side][idx], prev);
        }
        if (sum != book.levelQty[side][idx]) {
            fail("%s level %d: cached quantity %d but orders sum to %d", name, idx, book.levelQty[side][idx], sum);
        }
        if (n != book.levelCount[side][idx]) {
            fail("%s level %d: cached count %d but the list has %d orders", name, idx, book.levelCount[side][idx], n);
        }
        return n;
    }

    private static void fail(String format, Object... args) {
        throw new IllegalStateException(String.format(format, args));
    }
}
