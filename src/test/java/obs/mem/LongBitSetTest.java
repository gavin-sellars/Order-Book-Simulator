package obs.mem;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongBitSetTest {

    @Test
    void emptySetFindsNothing() {
        LongBitSet bits = new LongBitSet(130);

        assertEquals(-1, bits.nextSetBit(0));
        assertEquals(-1, bits.prevSetBit(129));
    }

    @Test
    void searchesAcrossWordBoundaries() {
        LongBitSet bits = new LongBitSet(130);
        for (int i : new int[] {0, 63, 64, 129}) bits.set(i);

        assertEquals(0, bits.nextSetBit(-5));
        assertEquals(0, bits.nextSetBit(0));
        assertEquals(63, bits.nextSetBit(1));
        assertEquals(64, bits.nextSetBit(64));
        assertEquals(129, bits.nextSetBit(65));
        assertEquals(-1, bits.nextSetBit(130));

        assertEquals(129, bits.prevSetBit(1000));
        assertEquals(129, bits.prevSetBit(129));
        assertEquals(64, bits.prevSetBit(128));
        assertEquals(63, bits.prevSetBit(63));
        assertEquals(0, bits.prevSetBit(62));
        assertEquals(-1, bits.prevSetBit(-1));

        bits.clear(64);
        assertFalse(bits.get(64));
        assertTrue(bits.get(63));
        assertEquals(129, bits.nextSetBit(64));
        assertEquals(63, bits.prevSetBit(128));
    }

    @Test
    void zeroSizedSetIsAlwaysEmpty() {
        LongBitSet bits = new LongBitSet(0);

        assertEquals(-1, bits.nextSetBit(0));
        assertEquals(-1, bits.prevSetBit(0));
        assertEquals(-1, bits.prevSetBit(10));
    }

    @Test
    void rejectsIndexesOutsideTheSet() {
        LongBitSet bits = new LongBitSet(130);

        assertThrows(IndexOutOfBoundsException.class, () -> bits.set(130));
        assertThrows(IndexOutOfBoundsException.class, () -> bits.clear(-1));
        assertThrows(IllegalArgumentException.class, () -> new LongBitSet(-1));
    }

    /** Each value toggles one bit; the searches must then agree with java.util.BitSet from every position. */
    @Property(tries = 300)
    void agreesWithJavaUtilBitSet(@ForAll @IntRange(min = 1, max = 300) int size,
                                  @ForAll @Size(max = 200) List<@IntRange(min = 0, max = 299) Integer> toggles) {
        LongBitSet bits = new LongBitSet(size);
        BitSet model = new BitSet(size);

        for (int t : toggles) {
            int i = t % size;
            if (model.get(i)) {
                model.clear(i);
                bits.clear(i);
            } else {
                model.set(i);
                bits.set(i);
            }
        }

        for (int from = 0; from <= size + 2; from++) {
            assertEquals(model.nextSetBit(from), bits.nextSetBit(from), "next from " + from);
            assertEquals(model.previousSetBit(from), bits.prevSetBit(from), "prev from " + from);
        }
    }
}
