package obs.mem;

import java.util.function.LongConsumer;

/**
 * Lock-free ring buffer of longs for exactly one producer thread and one consumer thread
 * (Part 4 of the guide).
 *
 * <p>Both sequences only ever increase: {@code writeSeq} counts values ever written and
 * {@code readSeq} values ever read, and a value's slot is its sequence masked to the capacity.
 * Full is {@code write - read == capacity} and empty is {@code write == read}, so the two can never
 * be confused.
 *
 * <p>Why no lock is needed: the producer stores a value <em>before</em> the volatile write that
 * advances {@code writeSeq}. When the consumer's volatile read sees the new sequence, the Java
 * Memory Model guarantees it also sees the value stored before it.
 *
 * <p>Layout against false sharing. Each thread keeps a cached copy of the other's sequence, so it
 * usually doesn't touch the other's cache lines at all. Fields are grouped by the thread that
 * writes them, and each group sits between 56-byte pads:
 * <pre>
 *   header | pad | writeSeq, cachedReadSeq (producer) | pad | readSeq, cachedWriteSeq (consumer) | pad | buffer, mask
 * </pre>
 * HotSpot lays out a superclass's fields before its subclass's, which is what the class chain
 * below relies on; {@code SpscLongRingBufferTest} checks the real offsets with JOL. The guide's
 * version kept both cached sequences as neighbouring plain fields, so each thread's hottest write
 * shared a cache line with the other's. The backing array is padded at both ends as well, so the
 * array header and whatever object is allocated next never share a line with the first or last slot.
 */
public final class SpscLongRingBuffer extends ConsumerPad {

    private static final int ARRAY_PAD = 16;           // 16 longs = 128 bytes on each side of the slots

    private final long[] buffer;
    private final int mask;

    public SpscLongRingBuffer(int capacity) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a positive power of two: " + capacity);
        }
        if (capacity > Integer.MAX_VALUE - 8 - 2 * ARRAY_PAD) throw new IllegalArgumentException("capacity too large");
        this.buffer = new long[capacity + 2 * ARRAY_PAD];
        this.mask = capacity - 1;
    }

    public int capacity() {
        return mask + 1;
    }

    // ---------------------------------------------------------------- producer thread only

    /** Adds a value. Returns false, changing nothing, if the buffer is full. */
    public boolean offer(long value) {
        long w = writeSeq;
        if (w - cachedReadSeq > mask) {                 // looks full: only now read the consumer's sequence
            cachedReadSeq = readSeq;
            if (w - cachedReadSeq > mask) return false;
        }
        buffer[ARRAY_PAD + (int) (w & mask)] = value;
        writeSeq = w + 1;                               // the volatile write publishes the value
        return true;
    }

    // ---------------------------------------------------------------- consumer thread only

    /** Removes and returns the oldest value, or {@code emptyValue} if there is none. Pick an emptyValue that is never offered. */
    public long poll(long emptyValue) {
        long r = readSeq;
        if (r >= cachedWriteSeq) {
            cachedWriteSeq = writeSeq;
            if (r >= cachedWriteSeq) return emptyValue;
        }
        long value = buffer[ARRAY_PAD + (int) (r & mask)];
        readSeq = r + 1;
        return value;
    }

    /**
     * Passes up to {@code limit} of the oldest values to {@code consumer}, oldest first, and returns
     * how many. The consumer's sequence is published once for the whole batch rather than once per
     * value. If the consumer throws, none of the batch counts as read.
     */
    public int drain(LongConsumer consumer, int limit) {
        long r = readSeq;
        long w = cachedWriteSeq;
        if (r >= w) {
            w = writeSeq;
            cachedWriteSeq = w;
            if (r >= w) return 0;
        }
        int n = (int) Math.min(limit, w - r);
        for (int i = 0; i < n; i++) consumer.accept(buffer[ARRAY_PAD + (int) ((r + i) & mask)]);
        readSeq = r + n;
        return n;
    }

    // ---------------------------------------------------------------- either thread

    /** A snapshot; by the time the caller acts on it, the other thread may have changed it. */
    public int size() {
        long r = readSeq;
        long w = writeSeq;
        return (int) Math.max(0, w - r);
    }

    public boolean isEmpty() {
        return size() == 0;
    }
}

// Padding classes. Package-private; they exist only to fix the field layout described above.

abstract class LeadingPad {
    long p00, p01, p02, p03, p04, p05, p06;
}

/** Written only by the producer. */
abstract class ProducerFields extends LeadingPad {
    volatile long writeSeq;
    long cachedReadSeq;
}

abstract class MiddlePad extends ProducerFields {
    long p10, p11, p12, p13, p14, p15, p16;
}

/** Written only by the consumer. */
abstract class ConsumerFields extends MiddlePad {
    volatile long readSeq;
    long cachedWriteSeq;
}

abstract class ConsumerPad extends ConsumerFields {
    long p20, p21, p22, p23, p24, p25, p26;
}
