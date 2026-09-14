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
            fail("crossed book: best bid level %d >= best ask level %d", book.bestBidIdx, book.bestAskIdx);
        }

        int orders = validateSide(book, Side.BUY) + validateSide(book, Side.SELL);

        if (orders != book.pool.liveCount()) {
            fail("pool has %d live slots but the levels hold %d orders", book.pool.liveCount(), orders);
        }
        if (orders != book.idToSlot.size()) {
            fail("id map has %d entries but the levels hold %d orders", book.idToSlot.size(), orders);
        }
    }

    /** Checks one side of the ladder and returns the number of orders on it. */
    private static int validateSide(OrderBook book, byte side) {
        final int empty = OrderBook.EMPTY;
        OrderPool pool = book.pool;
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

            long sum = 0;
            int n = 0;
            int prev = empty;
            long prevSeq = Long.MIN_VALUE;
            for (int s = head[idx]; s != empty; s = pool.next[s]) {
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
            if (tail[idx] != prev) fail("%s level %d: tail is %d but the last order is slot %d", name, idx, tail[idx], prev);
            if (sum != qty[idx]) fail("%s level %d: cached quantity %d but orders sum to %d", name, idx, qty[idx], sum);
            if (n != count[idx]) fail("%s level %d: cached count %d but the list has %d orders", name, idx, count[idx], n);
            orders += n;
        }

        int recordedBest = side == Side.BUY ? book.bestBidIdx : book.bestAskIdx;
        if (recordedBest != best) fail("%s touch is level %d but the best non-empty level is %d", name, recordedBest, best);
        if (nonEmpty != book.nonEmptyLevels[side]) {
            fail("%s has %d non-empty levels but the counter says %d", name, nonEmpty, book.nonEmptyLevels[side]);
        }
        return orders;
    }

    private static void fail(String format, Object... args) {
        throw new IllegalStateException(String.format(format, args));
    }
}
