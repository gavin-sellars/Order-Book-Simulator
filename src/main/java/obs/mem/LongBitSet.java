package obs.mem;

import java.util.Objects;

/**
 * Fixed-size bitset with fast searches in both directions.
 *
 * The order book keeps one bit per price level, set when the level is non-empty. When the best
 * level empties, finding the next one is a scan of 64 levels per word using
 * {@code numberOfTrailingZeros}/{@code numberOfLeadingZeros}, rather than one level at a time.
 * That bounds the worst case when the book has wide gaps.
 */
public final class LongBitSet {

    private final long[] words;
    private final int size;

    public LongBitSet(int size) {
        if (size < 0) throw new IllegalArgumentException("size must be non-negative: " + size);
        this.size = size;
        this.words = new long[(size + 63) >>> 6];
    }

    public int size() {
        return size;
    }

    // Java masks the shift distance of a long to its low 6 bits, so 1L << i is 1L << (i % 64).

    public void set(int i) {
        Objects.checkIndex(i, size);
        words[i >>> 6] |= 1L << i;
    }

    public void clear(int i) {
        Objects.checkIndex(i, size);
        words[i >>> 6] &= ~(1L << i);
    }

    public boolean get(int i) {
        Objects.checkIndex(i, size);
        return (words[i >>> 6] & (1L << i)) != 0;
    }

    /** Lowest set index at or above {@code from}, or -1. Any {@code from} is allowed. */
    public int nextSetBit(int from) {
        if (from < 0) from = 0;
        if (from >= size) return -1;

        int w = from >>> 6;
        long word = words[w] & (-1L << from);           // ignore bits below from
        while (true) {
            if (word != 0) return (w << 6) + Long.numberOfTrailingZeros(word);
            if (++w == words.length) return -1;
            word = words[w];
        }
    }

    /** Highest set index at or below {@code from}, or -1. Any {@code from} is allowed. */
    public int prevSetBit(int from) {
        if (from < 0 || size == 0) return -1;
        if (from >= size) from = size - 1;

        int w = from >>> 6;
        long word = words[w] & (-1L >>> (63 - (from & 63)));   // ignore bits above from
        while (true) {
            if (word != 0) return (w << 6) + 63 - Long.numberOfLeadingZeros(word);
            if (w-- == 0) return -1;
            word = words[w];
        }
    }
}
