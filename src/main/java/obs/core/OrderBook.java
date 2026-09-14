package obs.core;

import obs.mem.LongBitSet;
import obs.mem.LongIntMap;

import java.util.Arrays;
import java.util.Objects;

/**
 * The fast order book from Part 3 of the guide. Behaves exactly like the reference book
 * (RefOrderBook), which the differential tests check, but with no allocation after construction:
 * <ul>
 *   <li>Price levels are a flat array indexed by (price - basePrice) / tickSize, so finding a
 *       level is arithmetic rather than a tree walk.</li>
 *   <li>Each level is an intrusive doubly-linked list threaded through the {@link OrderPool}
 *       arrays, so cancelling from the middle of a queue is O(1).</li>
 *   <li>Order ids map to pool slots through a {@link LongIntMap}, with no boxing.</li>
 *   <li>A {@link LongBitSet} of non-empty levels finds the next best level 64 levels at a time.</li>
 * </ul>
 *
 * Per-side state is stored as {@code [side][level]} so that {@link Side#BUY} (0) and
 * {@link Side#SELL} (1) select the row directly.
 *
 * Prices outside the ladder, or not on a tick, are rejected with {@link OrderResult#REJECTED_PRICE}.
 * Single-threaded: exactly one thread may use a book.
 */
public final class OrderBook implements Book {

    /** Marks an empty level, the end of a list, or "no best level". Equal to LongBitSet's "not found". */
    static final int EMPTY = -1;

    final long basePrice;
    final long tickSize;
    final int levels;

    // The price ladder. A level is empty exactly when its head is EMPTY.
    final int[][] levelHead = new int[2][];      // slot of the oldest order
    final int[][] levelTail = new int[2][];      // slot of the newest order
    final long[][] levelQty = new long[2][];     // total remaining quantity
    final int[][] levelCount = new int[2][];     // number of orders
    final LongBitSet[] occupied = new LongBitSet[2];
    final int[] nonEmptyLevels = new int[2];

    int bestBidIdx = EMPTY;
    int bestAskIdx = EMPTY;

    final OrderPool pool;
    final LongIntMap idToSlot;
    private final TradeListener listener;
    private long nextArrivalSeq = 1;
    private final boolean linearScan;

    /** How to find the next best level when the best one empties. */
    public enum TouchSearch {
        /** Jump through the occupied-level bitset, 64 levels per step. The default. */
        BITSET,
        /** Step one level at a time, as in the guide's first version. Kept so the difference can be benchmarked. */
        LINEAR_SCAN
    }

    /**
     * @param basePrice    lowest price on the ladder; a positive multiple of tickSize
     * @param tickSize     price increment between levels
     * @param levels       number of price levels, so the highest price is basePrice + (levels - 1) * tickSize
     * @param poolCapacity most orders that can rest at once
     */
    public OrderBook(long basePrice, long tickSize, int levels, int poolCapacity, TradeListener listener) {
        this(basePrice, tickSize, levels, poolCapacity, listener, TouchSearch.BITSET);
    }

    /** As above, choosing how the next best level is found. */
    public OrderBook(long basePrice, long tickSize, int levels, int poolCapacity, TradeListener listener,
                     TouchSearch touchSearch) {
        if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive");
        if (basePrice <= 0 || basePrice % tickSize != 0) {
            throw new IllegalArgumentException("basePrice must be a positive multiple of tickSize");
        }
        if (levels <= 0) throw new IllegalArgumentException("levels must be positive");
        if (poolCapacity <= 0) throw new IllegalArgumentException("poolCapacity must be positive");
        try {
            Math.addExact(basePrice, Math.multiplyExact(tickSize, (long) levels));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ladder prices overflow a long", e);
        }

        this.basePrice = basePrice;
        this.tickSize = tickSize;
        this.levels = levels;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.linearScan = Objects.requireNonNull(touchSearch, "touchSearch") == TouchSearch.LINEAR_SCAN;

        for (int s = Side.BUY; s <= Side.SELL; s++) {
            levelHead[s] = filledWithEmpty(levels);
            levelTail[s] = filledWithEmpty(levels);
            levelQty[s] = new long[levels];
            levelCount[s] = new int[levels];
            occupied[s] = new LongBitSet(levels);
        }
        pool = new OrderPool(poolCapacity);
        idToSlot = new LongIntMap(poolCapacity);
    }

    private static int[] filledWithEmpty(int n) {
        int[] a = new int[n];
        Arrays.fill(a, EMPTY);
        return a;
    }

    // ---------------------------------------------------------------- matching mode

    @Override
    public int addLimitOrder(long id, byte side, long price, int qty) {
        int idx = indexOf(price);
        int check = validateNew(id, side, idx, qty);
        if (check != OrderResult.ACCEPTED) return check;

        int remaining = match(id, side, idx, qty);
        if (remaining > 0) rest(id, side, idx, remaining);
        return OrderResult.ACCEPTED;
    }

    @Override
    public int addMarketOrder(long id, byte side, int qty) {
        if (!Side.isValid(side)) return OrderResult.REJECTED_SIDE;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (idToSlot.get(id) != LongIntMap.NOT_FOUND) return OrderResult.REJECTED_DUP_ID;

        // A limit at the far end of the ladder crosses every level.
        match(id, side, side == Side.BUY ? levels - 1 : 0, qty);
        return OrderResult.ACCEPTED;
    }

    /** Crosses against the opposite side. Returns the unfilled quantity. */
    private int match(long aggressorId, byte side, int limitIdx, int qty) {
        boolean buying = side == Side.BUY;
        byte restingSide = Side.opposite(side);
        int[] head = levelHead[restingSide];
        long[] lvQty = levelQty[restingSide];

        while (qty > 0) {
            int best = buying ? bestAskIdx : bestBidIdx;
            // A buyer crosses when the best ask is at or below their limit; a seller the reverse.
            if (best == EMPTY || (buying ? best > limitIdx : best < limitIdx)) break;

            long price = toPrice(best);
            do {
                int slot = head[best];                  // FIFO: oldest order first
                int fill = Math.min(qty, pool.qty[slot]);
                long restingId = pool.id[slot];

                qty -= fill;
                pool.qty[slot] -= fill;
                lvQty[best] -= fill;
                if (pool.qty[slot] == 0) removeOrder(slot);     // moves the touch if the level empties

                listener.onTrade(aggressorId, restingId, price, fill, side);
            } while (qty > 0 && head[best] != EMPTY);
        }
        return qty;
    }

    // ---------------------------------------------------------------- book-builder mode

    @Override
    public int addRestingOrder(long id, byte side, long price, int qty) {
        int idx = indexOf(price);
        int check = validateNew(id, side, idx, qty);
        if (check != OrderResult.ACCEPTED) return check;

        rest(id, side, idx, qty);
        return OrderResult.ACCEPTED;
    }

    @Override
    public boolean execute(long id, int qty) {
        int slot = idToSlot.get(id);
        if (slot == LongIntMap.NOT_FOUND || qty <= 0 || qty > pool.qty[slot]) return false;

        byte side = pool.side[slot];
        int idx = pool.levelIdx[slot];
        pool.qty[slot] -= qty;
        levelQty[side][idx] -= qty;
        if (pool.qty[slot] == 0) removeOrder(slot);

        listener.onTrade(TradeListener.UNKNOWN_ID, id, toPrice(idx), qty, Side.opposite(side));
        return true;
    }

    @Override
    public int replace(long oldId, long newId, long price, int qty) {
        int slot = idToSlot.get(oldId);
        if (slot == LongIntMap.NOT_FOUND) return OrderResult.REJECTED_UNKNOWN_ID;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        int idx = indexOf(price);
        if (idx == EMPTY) return OrderResult.REJECTED_PRICE;
        if (newId != oldId && idToSlot.get(newId) != LongIntMap.NOT_FOUND) return OrderResult.REJECTED_DUP_ID;

        byte side = pool.side[slot];
        removeOrder(slot);
        rest(newId, side, idx, qty);
        return OrderResult.ACCEPTED;
    }

    // ---------------------------------------------------------------- both modes

    @Override
    public boolean cancel(long id) {
        int slot = idToSlot.get(id);
        if (slot == LongIntMap.NOT_FOUND) return false;
        removeOrder(slot);
        return true;
    }

    @Override
    public boolean reduce(long id, int by) {
        if (by <= 0) return false;
        int slot = idToSlot.get(id);
        if (slot == LongIntMap.NOT_FOUND) return false;

        if (by >= pool.qty[slot]) {
            removeOrder(slot);
        } else {
            pool.qty[slot] -= by;                       // stays where it is in the queue
            levelQty[pool.side[slot]][pool.levelIdx[slot]] -= by;
        }
        return true;
    }

    // ---------------------------------------------------------------- structure

    private void rest(long id, byte side, int idx, int qty) {
        int slot = pool.allocate();
        pool.id[slot] = id;
        pool.qty[slot] = qty;
        pool.side[slot] = side;
        pool.levelIdx[slot] = idx;
        pool.seq[slot] = nextArrivalSeq++;

        // Append at the tail: the newest order has the worst time priority.
        int[] tail = levelTail[side];
        int t = tail[idx];
        pool.prev[slot] = t;
        pool.next[slot] = EMPTY;
        if (t == EMPTY) {
            levelHead[side][idx] = slot;
            occupied[side].set(idx);
            nonEmptyLevels[side]++;
        } else {
            pool.next[t] = slot;
        }
        tail[idx] = slot;

        levelQty[side][idx] += qty;
        levelCount[side][idx]++;
        idToSlot.put(id, slot);

        if (side == Side.BUY) {
            if (bestBidIdx == EMPTY || idx > bestBidIdx) bestBidIdx = idx;
        } else if (bestAskIdx == EMPTY || idx < bestAskIdx) {
            bestAskIdx = idx;
        }
    }

    /** Unlinks a resting order in O(1), frees its slot, and moves the touch if that emptied the best level. */
    private void removeOrder(int slot) {
        byte side = pool.side[slot];
        int idx = pool.levelIdx[slot];
        int[] head = levelHead[side];
        int[] tail = levelTail[side];

        int p = pool.prev[slot];
        int n = pool.next[slot];
        if (p != EMPTY) pool.next[p] = n; else head[idx] = n;
        if (n != EMPTY) pool.prev[n] = p; else tail[idx] = p;

        levelQty[side][idx] -= pool.qty[slot];
        levelCount[side][idx]--;
        idToSlot.remove(pool.id[slot]);
        pool.release(slot);

        if (head[idx] == EMPTY) {
            occupied[side].clear(idx);
            nonEmptyLevels[side]--;
            // Both searches return -1 (EMPTY) when no level is left on that side.
            if (side == Side.BUY) {
                if (idx == bestBidIdx) {
                    bestBidIdx = linearScan ? scanDown(head, idx - 1) : occupied[side].prevSetBit(idx - 1);
                }
            } else if (idx == bestAskIdx) {
                bestAskIdx = linearScan ? scanUp(head, idx + 1) : occupied[side].nextSetBit(idx + 1);
            }
        }
    }

    /** Highest non-empty level at or below {@code from}, one level at a time, or EMPTY. */
    private static int scanDown(int[] head, int from) {
        int i = from;
        while (i >= 0 && head[i] == EMPTY) i--;
        return i;
    }

    /** Lowest non-empty level at or above {@code from}, one level at a time, or EMPTY. */
    private int scanUp(int[] head, int from) {
        int i = from;
        while (i < levels && head[i] == EMPTY) i++;
        return i < levels ? i : EMPTY;
    }

    private int validateNew(long id, byte side, int idx, int qty) {
        if (!Side.isValid(side)) return OrderResult.REJECTED_SIDE;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (idx == EMPTY) return OrderResult.REJECTED_PRICE;
        if (idToSlot.get(id) != LongIntMap.NOT_FOUND) return OrderResult.REJECTED_DUP_ID;
        return OrderResult.ACCEPTED;
    }

    /** Ladder index of a price, or EMPTY if the price is off the ladder or not on a tick. */
    private int indexOf(long price) {
        long offset = price - basePrice;      // a wrapped result for extreme prices is caught by the range check
        if (offset < 0) return EMPTY;
        long idx = offset / tickSize;
        if (idx >= levels || idx * tickSize != offset) return EMPTY;
        return (int) idx;
    }

    private long toPrice(int idx) {
        return basePrice + idx * tickSize;
    }

    private static byte checkSide(byte side) {
        if (!Side.isValid(side)) throw new IllegalArgumentException("invalid side " + side);
        return side;
    }

    // ---------------------------------------------------------------- queries

    @Override
    public long bestBid() {
        return bestBidIdx == EMPTY ? NO_BID : toPrice(bestBidIdx);
    }

    @Override
    public long bestAsk() {
        return bestAskIdx == EMPTY ? NO_ASK : toPrice(bestAskIdx);
    }

    @Override
    public boolean isCrossed() {
        return bestBidIdx != EMPTY && bestAskIdx != EMPTY && bestBidIdx >= bestAskIdx;
    }

    @Override
    public long bidQtyAt(long price) {
        int idx = indexOf(price);
        return idx == EMPTY ? 0 : levelQty[Side.BUY][idx];
    }

    @Override
    public long askQtyAt(long price) {
        int idx = indexOf(price);
        return idx == EMPTY ? 0 : levelQty[Side.SELL][idx];
    }

    @Override
    public boolean contains(long id) {
        return idToSlot.get(id) != LongIntMap.NOT_FOUND;
    }

    @Override
    public int restingQty(long id) {
        int slot = idToSlot.get(id);
        return slot == LongIntMap.NOT_FOUND ? 0 : pool.qty[slot];
    }

    @Override
    public int orderCount() {
        return pool.liveCount();
    }

    @Override
    public int levelCount(byte side) {
        return nonEmptyLevels[checkSide(side)];
    }

    @Override
    public int depth(byte side, int n, long[] prices, long[] qtys, int[] counts) {
        checkSide(side);
        boolean bids = side == Side.BUY;
        int idx = bids ? bestBidIdx : bestAskIdx;
        int i = 0;
        while (i < n && idx != EMPTY) {
            prices[i] = toPrice(idx);
            qtys[i] = levelQty[side][idx];
            if (counts != null) counts[i] = levelCount[side][idx];
            i++;
            idx = bids ? occupied[side].prevSetBit(idx - 1) : occupied[side].nextSetBit(idx + 1);
        }
        return i;
    }

    @Override
    public long[] queueAt(byte side, long price) {
        checkSide(side);
        int idx = indexOf(price);
        if (idx == EMPTY) return new long[0];

        long[] ids = new long[levelCount[side][idx]];
        int i = 0;
        for (int s = levelHead[side][idx]; s != EMPTY; s = pool.next[s]) ids[i++] = pool.id[s];
        return ids;
    }

    /**
     * Arrival sequence number of a resting order, or -1 if it isn't in the book. Lower means
     * earlier time priority. Kept by reduce and partial fills; a replace gets a new one.
     */
    public long arrivalSeq(long id) {
        int slot = idToSlot.get(id);
        return slot == LongIntMap.NOT_FOUND ? -1 : pool.seq[slot];
    }

    public long minPrice() {
        return basePrice;
    }

    public long maxPrice() {
        return toPrice(levels - 1);
    }

    public long tickSize() {
        return tickSize;
    }

    public int poolCapacity() {
        return pool.capacity();
    }
}
