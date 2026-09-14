package obs.core;

/**
 * Preallocated storage for resting orders, laid out as a struct of arrays. An "order" is an int
 * slot index into these arrays; there are no Order objects and no allocation after construction.
 *
 * Free slots are threaded onto a free list through {@link #next}. A slot is either live (next is
 * its link within a price level) or free (next is the free-list link), never both.
 */
final class OrderPool {

    final long[] id;
    final int[] qty;           // remaining quantity
    final int[] levelIdx;      // index into the price ladder
    final byte[] side;
    final long[] seq;          // arrival sequence number: lower means earlier time priority
    final int[] next;          // towards the back of the level's queue, or the free list
    final int[] prev;          // towards the front of the level's queue

    private final int capacity;
    private int freeHead;
    private int liveCount;

    OrderPool(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive: " + capacity);
        this.capacity = capacity;
        id = new long[capacity];
        qty = new int[capacity];
        levelIdx = new int[capacity];
        side = new byte[capacity];
        seq = new long[capacity];
        next = new int[capacity];
        prev = new int[capacity];

        for (int i = 0; i < capacity - 1; i++) next[i] = i + 1;
        next[capacity - 1] = -1;
        freeHead = 0;
    }

    /**
     * Takes a free slot. Throws when the pool is exhausted: silently growing would mean a
     * mid-run array copy, a latency spike that would corrupt every measurement. Size it generously.
     */
    int allocate() {
        int slot = freeHead;
        if (slot == -1) throw new IllegalStateException("order pool exhausted at " + capacity + " orders");
        freeHead = next[slot];
        next[slot] = -1;
        prev[slot] = -1;
        liveCount++;
        return slot;
    }

    void release(int slot) {
        next[slot] = freeHead;
        prev[slot] = -1;
        freeHead = slot;
        liveCount--;
    }

    int liveCount() {
        return liveCount;
    }

    int capacity() {
        return capacity;
    }
}
