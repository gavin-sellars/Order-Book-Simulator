package obs.mem;

import java.util.Arrays;

/**
 * Open-addressed long -> int map with linear probing. No boxing and no per-entry objects:
 * one array of keys, one of values, and collisions resolve to the neighbouring slot, which is
 * usually on the same cache line.
 *
 * Values must be non-negative; {@link #NOT_FOUND} marks an empty slot. The table never resizes
 * (a resize is a latency spike), so size it for the most entries it will ever hold.
 */
public final class LongIntMap {

    public static final int NOT_FOUND = -1;

    private static final int MAX_CAPACITY = 1 << 30;

    private final long[] keys;
    private final int[] values;
    private final int mask;
    private final int maxSize;
    private final boolean mixBits;
    private int size;

    /** How a key chooses its home slot. */
    public enum Hashing {
        /** Scramble the key's bits first, so any key pattern spreads evenly. The default. */
        MIX,
        /**
         * Use the key's low bits directly. Kept for benchmarks, not for use. Sequential keys land
         * in neighbouring slots, so lookups are cache-friendly, but a window of live sequential
         * keys forms one long run of occupied slots, and every removal's backward shift scans to
         * the end of that run: about 74 microseconds per remove with 100,000 live keys, against
         * about 40 nanoseconds with MIX (docs/BENCHMARKS.md).
         */
        IDENTITY
    }

    /** Capacity is the next power of two at least twice expectedEntries, so the load factor stays at or below 0.5. */
    public LongIntMap(int expectedEntries) {
        this(expectedEntries, Hashing.MIX);
    }

    public LongIntMap(int expectedEntries, Hashing hashing) {
        if (expectedEntries < 0 || expectedEntries > MAX_CAPACITY / 2) {
            throw new IllegalArgumentException("expectedEntries out of range: " + expectedEntries);
        }
        int capacity = ceilPowerOfTwo(Math.max(16, expectedEntries * 2));
        keys = new long[capacity];
        values = new int[capacity];
        Arrays.fill(values, NOT_FOUND);
        mask = capacity - 1;
        maxSize = capacity / 2;
        mixBits = hashing == Hashing.MIX;
    }

    private static int ceilPowerOfTwo(int n) {
        return 1 << (32 - Integer.numberOfLeadingZeros(n - 1));
    }

    /**
     * Home slot for a key. With {@link Hashing#MIX} the bits go through the 64-bit finalizer from
     * MurmurHash3 (fmix64) first; with {@link Hashing#IDENTITY} the key is used as is.
     *
     * {@code & mask} replaces {@code % capacity}: the same result for a power-of-two capacity,
     * but a single AND instead of a 20-40 cycle integer division.
     */
    private int slotOf(long key) {
        long h = key;
        if (mixBits) {
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= h >>> 33;
        }
        return (int) h & mask;
    }

    /** Maps key to value, replacing any existing mapping. Throws if the key is new and the map is full. */
    public void put(long key, int value) {
        if (value < 0) throw new IllegalArgumentException("values must be non-negative: " + value);

        int i = slotOf(key);
        while (values[i] != NOT_FOUND) {
            if (keys[i] == key) {
                values[i] = value;                  // overwrite
                return;
            }
            i = (i + 1) & mask;                     // linear probe
        }
        if (size >= maxSize) throw new IllegalStateException("map full at " + size + " entries");
        keys[i] = key;
        values[i] = value;
        size++;
    }

    /** Returns the value for key, or NOT_FOUND. */
    public int get(long key) {
        int i = slotOf(key);
        while (values[i] != NOT_FOUND) {
            if (keys[i] == key) return values[i];
            i = (i + 1) & mask;
        }
        return NOT_FOUND;
    }

    /** Removes key and returns its value, or NOT_FOUND if it wasn't present. */
    public int remove(long key) {
        int i = slotOf(key);
        while (values[i] != NOT_FOUND) {
            if (keys[i] == key) {
                int v = values[i];
                removeAt(i);
                return v;
            }
            i = (i + 1) & mask;
        }
        return NOT_FOUND;
    }

    /**
     * Backward-shift deletion. Blanking the slot would break probe chains: a later key that
     * probed past this slot would become unreachable. Instead, walk forward and pull back any
     * entry that is allowed to sit in the hole. The load factor guarantees an empty slot exists,
     * so the walk terminates.
     */
    private void removeAt(int hole) {
        int j = hole;
        while (true) {
            j = (j + 1) & mask;
            if (values[j] == NOT_FOUND) break;

            int ideal = slotOf(keys[j]);
            if (cyclicallyWithin(hole, ideal, j)) continue;     // moving it back would put it before its ideal slot

            keys[hole] = keys[j];
            values[hole] = values[j];
            hole = j;
        }
        values[hole] = NOT_FOUND;
        keys[hole] = 0L;
        size--;
    }

    /** True if k lies in the cyclic interval (i, j]. */
    private static boolean cyclicallyWithin(int i, int k, int j) {
        return (i <= j) ? (i < k && k <= j) : (i < k || k <= j);
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return mask + 1;
    }
}
