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
 * <h2>Far levels</h2>
 * Real books hold orders nowhere near the market: a bid at $0.0001 or an offer at $199,999 in a
 * $180 stock. A ladder covering all of that would need billions of levels, so the ladder is a
 * window, and in book-builder mode an order priced outside it (or between two of its levels) goes
 * to a <em>far level</em> instead. Far levels are kept per side in a small array sorted by price;
 * their queues use the same pool links and level arrays, at indexes from {@code levels} upwards, so
 * cancels and executions work unchanged. Creating or removing a far level costs a binary search and
 * an array shift, which is fine because they are rare. The touch is the better of the window's best
 * level and the best far level. With the default of zero far levels the book rejects such prices,
 * as it always did. Matching mode still only accepts prices on the ladder, but it does trade against
 * far orders already resting.
 *
 * Per-side state is stored as {@code [side][level]} so that {@link Side#BUY} (0) and
 * {@link Side#SELL} (1) select the row directly.
 *
 * Prices that are not positive multiples of the price tick are rejected with
 * {@link OrderResult#REJECTED_PRICE}, as are prices off the ladder when no far level can take them.
 * Single-threaded: exactly one thread may use a book.
 */
public final class OrderBook implements Book {

    /** Marks an empty level, the end of a list, or "no best level". Equal to LongBitSet's "not found". */
    static final int EMPTY = -1;

    /** Level index meaning "not on the ladder, but valid for a far level". Never stored. */
    private static final int FAR = -2;

    final long basePrice;
    final long tickSize;
    final int levels;
    final long priceTick;
    final int farCapacity;

    // Level state, indexed [side][level]. Indexes below `levels` are the ladder; from `levels` up,
    // far levels by slot. A level is empty exactly when its head is EMPTY.
    final int[][] levelHead = new int[2][];      // slot of the oldest order
    final int[][] levelTail = new int[2][];      // slot of the newest order
    final long[][] levelQty = new long[2][];     // total remaining quantity
    final int[][] levelCount = new int[2][];     // number of orders
    final LongBitSet[] occupied = new LongBitSet[2];     // ladder levels only
    final int[] nonEmptyLevels = new int[2];             // ladder and far levels

    // Far levels: each side's live far slots sorted by ascending price, their prices, and free slots.
    final long[][] farPrice = new long[2][];
    final int[][] farSorted = new int[2][];
    final int[] farCount = new int[2];
    private final int[][] farFree = new int[2][];
    private final int[] farFreeCount = new int[2];

    int bestBidIdx = EMPTY;     // best ladder level; a far level may still be better
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
        this(basePrice, tickSize, levels, poolCapacity, listener, touchSearch, tickSize, 0);
    }

    /**
     * A book whose ladder is a window, with far levels for everything else.
     *
     * @param priceTick   smallest valid price increment; divides tickSize. Prices must be positive multiples of it.
     * @param farCapacity most far price levels one side can hold at once; the book fails loudly beyond it
     */
    public OrderBook(long basePrice, long tickSize, int levels, int poolCapacity, TradeListener listener,
                     TouchSearch touchSearch, long priceTick, int farCapacity) {
        if (tickSize <= 0) throw new IllegalArgumentException("tickSize must be positive");
        if (basePrice <= 0 || basePrice % tickSize != 0) {
            throw new IllegalArgumentException("basePrice must be a positive multiple of tickSize");
        }
        if (levels <= 0) throw new IllegalArgumentException("levels must be positive");
        if (poolCapacity <= 0) throw new IllegalArgumentException("poolCapacity must be positive");
        if (priceTick <= 0 || tickSize % priceTick != 0) {
            throw new IllegalArgumentException("priceTick must be positive and divide tickSize");
        }
        if (farCapacity < 0 || farCapacity > Integer.MAX_VALUE - levels) {
            throw new IllegalArgumentException("farCapacity out of range: " + farCapacity);
        }
        try {
            Math.addExact(basePrice, Math.multiplyExact(tickSize, (long) levels));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ladder prices overflow a long", e);
        }

        this.basePrice = basePrice;
        this.tickSize = tickSize;
        this.levels = levels;
        this.priceTick = priceTick;
        this.farCapacity = farCapacity;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.linearScan = Objects.requireNonNull(touchSearch, "touchSearch") == TouchSearch.LINEAR_SCAN;

        int total = levels + farCapacity;
        for (int s = Side.BUY; s <= Side.SELL; s++) {
            levelHead[s] = filledWithEmpty(total);
            levelTail[s] = filledWithEmpty(total);
            levelQty[s] = new long[total];
            levelCount[s] = new int[total];
            occupied[s] = new LongBitSet(levels);
            farPrice[s] = new long[farCapacity];
            farSorted[s] = new int[farCapacity];
            farFree[s] = new int[farCapacity];
            for (int i = 0; i < farCapacity; i++) farFree[s][i] = farCapacity - 1 - i;     // hand out slot 0 first
            farFreeCount[s] = farCapacity;
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

        int remaining = match(id, side, idx, price, qty);
        if (remaining > 0) rest(id, side, idx, remaining);
        return OrderResult.ACCEPTED;
    }

    @Override
    public int addMarketOrder(long id, byte side, int qty) {
        if (!Side.isValid(side)) return OrderResult.REJECTED_SIDE;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        if (idToSlot.get(id) != LongIntMap.NOT_FOUND) return OrderResult.REJECTED_DUP_ID;

        // A limit at the far end of the ladder, and beyond it for far levels, crosses every level.
        boolean buying = side == Side.BUY;
        match(id, side, buying ? levels - 1 : 0, buying ? Long.MAX_VALUE : Long.MIN_VALUE, qty);
        return OrderResult.ACCEPTED;
    }

    /** Crosses against the opposite side. Returns the unfilled quantity. */
    private int match(long aggressorId, byte side, int limitIdx, long limitPrice, int qty) {
        boolean buying = side == Side.BUY;
        byte restingSide = Side.opposite(side);
        int[] head = levelHead[restingSide];
        long[] lvQty = levelQty[restingSide];

        while (qty > 0) {
            int best = buying ? bestAskIdx : bestBidIdx;
            if (farCount[restingSide] != 0) {
                // Far levels in play: compare prices, since far indexes aren't in price order.
                best = bestLevel(restingSide, best);
                if (best == EMPTY) break;
                long bestPrice = priceAt(restingSide, best);
                if (buying ? bestPrice > limitPrice : bestPrice < limitPrice) break;
            } else if (best == EMPTY || (buying ? best > limitIdx : best < limitIdx)) {
                // A buyer crosses when the best ask is at or below their limit; a seller the reverse.
                break;
            }

            long price = priceAt(restingSide, best);
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
        int idx = restingIndexOf(price);
        int check = validateNew(id, side, idx, qty);
        if (check != OrderResult.ACCEPTED) return check;

        if (idx == FAR) idx = farLevel(side, price);
        rest(id, side, idx, qty);
        return OrderResult.ACCEPTED;
    }

    @Override
    public boolean execute(long id, int qty) {
        int slot = idToSlot.get(id);
        if (slot == LongIntMap.NOT_FOUND || qty <= 0 || qty > pool.qty[slot]) return false;

        byte side = pool.side[slot];
        int idx = pool.levelIdx[slot];
        long price = priceAt(side, idx);        // before removal can free a far level
        pool.qty[slot] -= qty;
        levelQty[side][idx] -= qty;
        if (pool.qty[slot] == 0) removeOrder(slot);

        listener.onTrade(TradeListener.UNKNOWN_ID, id, price, qty, Side.opposite(side));
        return true;
    }

    @Override
    public int replace(long oldId, long newId, long price, int qty) {
        int slot = idToSlot.get(oldId);
        if (slot == LongIntMap.NOT_FOUND) return OrderResult.REJECTED_UNKNOWN_ID;
        if (qty <= 0) return OrderResult.REJECTED_QTY;
        int idx = restingIndexOf(price);
        if (idx == EMPTY) return OrderResult.REJECTED_PRICE;
        if (newId != oldId && idToSlot.get(newId) != LongIntMap.NOT_FOUND) return OrderResult.REJECTED_DUP_ID;

        byte side = pool.side[slot];
        removeOrder(slot);                          // may free the far level the new price needs; farLevel recreates it
        if (idx == FAR) idx = farLevel(side, price);
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
            if (idx < levels) occupied[side].set(idx);
            nonEmptyLevels[side]++;
        } else {
            pool.next[t] = slot;
        }
        tail[idx] = slot;

        levelQty[side][idx] += qty;
        levelCount[side][idx]++;
        idToSlot.put(id, slot);

        if (idx >= levels) return;                  // far levels are ordered by farSorted, not the touch index
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
            nonEmptyLevels[side]--;
            if (idx >= levels) {
                freeFarLevel(side, idx - levels);
                return;
            }
            occupied[side].clear(idx);
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

    /** Ladder index for a resting order, FAR if it belongs on a far level, or EMPTY if the price is invalid. */
    private int restingIndexOf(long price) {
        int idx = indexOf(price);
        if (idx != EMPTY || farCapacity == 0 || price <= 0 || price % priceTick != 0) return idx;
        return FAR;
    }

    private long toPrice(int idx) {
        return basePrice + idx * tickSize;
    }

    /** Price of a ladder or far level. */
    private long priceAt(byte side, int idx) {
        return idx < levels ? toPrice(idx) : farPrice[side][idx - levels];
    }

    /** The better of a side's best ladder level and its best far level, as a level index, or EMPTY. */
    private int bestLevel(byte side, int ladderBest) {
        int count = farCount[side];
        if (count == 0) return ladderBest;
        if (side == Side.BUY) {
            int far = farSorted[side][count - 1];
            return ladderBest == EMPTY || farPrice[side][far] > toPrice(ladderBest) ? levels + far : ladderBest;
        }
        int far = farSorted[side][0];
        return ladderBest == EMPTY || farPrice[side][far] < toPrice(ladderBest) ? levels + far : ladderBest;
    }

    /** Position of {@code price} in the side's sorted far levels, or -(insertion point) - 1, as Arrays.binarySearch. */
    private int farSearch(byte side, long price) {
        int[] sorted = farSorted[side];
        long[] prices = farPrice[side];
        int lo = 0;
        int hi = farCount[side] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long p = prices[sorted[mid]];
            if (p < price) lo = mid + 1;
            else if (p > price) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    /** Level index of the far level at {@code price}, creating it if needed. */
    private int farLevel(byte side, long price) {
        int pos = farSearch(side, price);
        if (pos >= 0) return levels + farSorted[side][pos];
        if (farFreeCount[side] == 0) {
            throw new IllegalStateException("far levels exhausted at " + farCapacity + " on side " + Side.name(side));
        }
        int far = farFree[side][--farFreeCount[side]];
        int insert = -pos - 1;
        int[] sorted = farSorted[side];
        System.arraycopy(sorted, insert, sorted, insert + 1, farCount[side] - insert);
        sorted[insert] = far;
        farCount[side]++;
        farPrice[side][far] = price;
        return levels + far;
    }

    private void freeFarLevel(byte side, int far) {
        int pos = farSearch(side, farPrice[side][far]);
        int[] sorted = farSorted[side];
        System.arraycopy(sorted, pos + 1, sorted, pos, farCount[side] - pos - 1);
        farCount[side]--;
        farFree[side][farFreeCount[side]++] = far;
    }

    /** Level index holding {@code price} on a side, ladder or far, or EMPTY. */
    private int levelOf(byte side, long price) {
        int idx = indexOf(price);
        if (idx != EMPTY || farCount[side] == 0) return idx;
        int pos = farSearch(side, price);
        return pos >= 0 ? levels + farSorted[side][pos] : EMPTY;
    }

    private static byte checkSide(byte side) {
        if (!Side.isValid(side)) throw new IllegalArgumentException("invalid side " + side);
        return side;
    }

    // ---------------------------------------------------------------- queries

    @Override
    public long bestBid() {
        long ladder = bestBidIdx == EMPTY ? NO_BID : toPrice(bestBidIdx);
        int count = farCount[Side.BUY];
        return count == 0 ? ladder : Math.max(ladder, farPrice[Side.BUY][farSorted[Side.BUY][count - 1]]);
    }

    @Override
    public long bestAsk() {
        long ladder = bestAskIdx == EMPTY ? NO_ASK : toPrice(bestAskIdx);
        return farCount[Side.SELL] == 0 ? ladder : Math.min(ladder, farPrice[Side.SELL][farSorted[Side.SELL][0]]);
    }

    @Override
    public boolean isCrossed() {
        long bid = bestBid();
        long ask = bestAsk();
        return bid != NO_BID && ask != NO_ASK && bid >= ask;
    }

    @Override
    public long bidQtyAt(long price) {
        int idx = levelOf(Side.BUY, price);
        return idx == EMPTY ? 0 : levelQty[Side.BUY][idx];
    }

    @Override
    public long askQtyAt(long price) {
        int idx = levelOf(Side.SELL, price);
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
        int[] sorted = farSorted[side];
        int farEnd = farCount[side];
        int f = bids ? farEnd - 1 : 0;              // next far level, best first
        int i = 0;
        while (i < n) {
            boolean haveFar = bids ? f >= 0 : f < farEnd;
            if (idx == EMPTY && !haveFar) break;
            int level;
            if (haveFar && (idx == EMPTY
                    || (bids ? farPrice[side][sorted[f]] > toPrice(idx) : farPrice[side][sorted[f]] < toPrice(idx)))) {
                level = levels + sorted[f];
                f += bids ? -1 : 1;
            } else {
                level = idx;
                idx = bids ? occupied[side].prevSetBit(idx - 1) : occupied[side].nextSetBit(idx + 1);
            }
            prices[i] = priceAt(side, level);
            qtys[i] = levelQty[side][level];
            if (counts != null) counts[i] = levelCount[side][level];
            i++;
        }
        return i;
    }

    @Override
    public long[] queueAt(byte side, long price) {
        checkSide(side);
        int idx = levelOf(side, price);
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

    /**
     * The arrival sequence number the next resting order will get. Every order already in the
     * book has a lower number, so comparing against this tells "ahead of" from "behind" a point in time.
     */
    public long nextArrivalSeq() {
        return nextArrivalSeq;
    }

    /** Price of a resting order, or -1 if it isn't in the book. */
    public long priceOf(long id) {
        int slot = idToSlot.get(id);
        return slot == LongIntMap.NOT_FOUND ? -1 : priceAt(pool.side[slot], pool.levelIdx[slot]);
    }

    /** Side of a resting order, or -1 if it isn't in the book. */
    public byte sideOf(long id) {
        int slot = idToSlot.get(id);
        return slot == LongIntMap.NOT_FOUND ? -1 : pool.side[slot];
    }

    /** Lowest price on the ladder. Far levels may hold lower prices. */
    public long minPrice() {
        return basePrice;
    }

    /** Highest price on the ladder. Far levels may hold higher prices. */
    public long maxPrice() {
        return toPrice(levels - 1);
    }

    public long tickSize() {
        return tickSize;
    }

    public int poolCapacity() {
        return pool.capacity();
    }

    /** Most far price levels one side can hold; 0 if the book has none. */
    public int farCapacity() {
        return farCapacity;
    }

    /** Far price levels currently in use on one side. */
    public int farLevelCount(byte side) {
        return farCount[checkSide(side)];
    }
}
