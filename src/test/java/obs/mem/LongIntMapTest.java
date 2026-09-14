package obs.mem;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static obs.mem.LongIntMap.NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongIntMapTest {

    @Test
    void emptyMapFindsNothing() {
        LongIntMap map = new LongIntMap(16);

        assertEquals(NOT_FOUND, map.get(0));
        assertEquals(NOT_FOUND, map.remove(0));
        assertEquals(0, map.size());
    }

    @Test
    void putGetOverwriteRemove() {
        LongIntMap map = new LongIntMap(16);

        map.put(42, 7);
        assertEquals(7, map.get(42));
        map.put(42, 8);
        assertEquals(8, map.get(42));
        assertEquals(1, map.size());

        assertEquals(8, map.remove(42));
        assertEquals(NOT_FOUND, map.get(42));
        assertEquals(0, map.size());
    }

    @Test
    void handlesExtremeKeys() {
        LongIntMap map = new LongIntMap(16);
        long[] keys = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE};

        for (int i = 0; i < keys.length; i++) map.put(keys[i], i);
        for (int i = 0; i < keys.length; i++) assertEquals(i, map.get(keys[i]));
    }

    @Test
    void capacityIsAPowerOfTwoWithRoomForTwiceTheExpectedEntries() {
        assertEquals(16, new LongIntMap(0).capacity());
        assertEquals(16, new LongIntMap(8).capacity());
        assertEquals(32, new LongIntMap(9).capacity());
        assertEquals(2048, new LongIntMap(1000).capacity());
        assertEquals(1 << 21, new LongIntMap(1 << 20).capacity());
    }

    @Test
    void refusesNewKeysWhenFullButStillOverwrites() {
        LongIntMap map = new LongIntMap(8);         // capacity 16, holds 8
        for (int k = 0; k < 8; k++) map.put(k, k);

        assertThrows(IllegalStateException.class, () -> map.put(8, 8));
        map.put(3, 99);
        assertEquals(99, map.get(3));

        map.remove(3);
        map.put(8, 8);
        assertEquals(8, map.get(8));
    }

    @Test
    void rejectsNegativeValuesAndBadSizes() {
        LongIntMap map = new LongIntMap(16);
        assertThrows(IllegalArgumentException.class, () -> map.put(1, NOT_FOUND));
        assertThrows(IllegalArgumentException.class, () -> new LongIntMap(-1));
        assertThrows(IllegalArgumentException.class, () -> new LongIntMap(Integer.MAX_VALUE));
    }

    @Test
    void sequentialKeysSurviveHeavyChurn() {
        int window = 1000;
        for (LongIntMap.Hashing hashing : LongIntMap.Hashing.values()) {
            LongIntMap map = new LongIntMap(window, hashing);

            for (int k = 0; k < 1_000_000; k++) {
                map.put(k, k);
                if (k >= window) assertEquals(k - window, map.remove(k - window), hashing + " key " + k);
            }

            assertEquals(window, map.size());
            for (int k = 1_000_000 - window; k < 1_000_000; k++) assertEquals(k, map.get(k));
        }
    }

    // ---------------------------------------------------------------- model-based property

    record MapOp(int kind, long key, int value) {}      // kind: 0 put, 1 get, 2 remove

    /**
     * A small, nearly-full table with keys from a narrow range, so collisions, long probe chains,
     * wrap-around at the end of the table and backward-shift deletion all happen constantly.
     */
    @Property(tries = 500)
    void behavesLikeHashMap(@ForAll("mapOps") List<MapOp> ops, @ForAll LongIntMap.Hashing hashing) {
        LongIntMap map = new LongIntMap(32, hashing);   // capacity 64, holds 32
        Map<Long, Integer> model = new HashMap<>();

        for (MapOp op : ops) {
            switch (op.kind()) {
                case 0 -> {
                    if (model.size() == 32 && !model.containsKey(op.key())) {
                        assertThrows(IllegalStateException.class, () -> map.put(op.key(), op.value()));
                    } else {
                        map.put(op.key(), op.value());
                        model.put(op.key(), op.value());
                    }
                }
                case 1 -> assertEquals(model.getOrDefault(op.key(), NOT_FOUND), map.get(op.key()), op.toString());
                default -> {
                    Integer expected = model.remove(op.key());
                    assertEquals(expected == null ? NOT_FOUND : expected, map.remove(op.key()), op.toString());
                }
            }
            assertEquals(model.size(), map.size());
        }

        for (long k = -60; k <= 60; k++) assertEquals(model.getOrDefault(k, NOT_FOUND), map.get(k), "key " + k);
        for (Map.Entry<Long, Integer> e : model.entrySet()) assertEquals(e.getValue(), map.get(e.getKey()));
    }

    @Provide
    Arbitrary<List<MapOp>> mapOps() {
        Arbitrary<Long> key = Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(-50, 50)),
                Tuple.of(1, Arbitraries.of(Long.MIN_VALUE, Long.MAX_VALUE)));
        Arbitrary<Integer> kind = Arbitraries.frequencyOf(
                Tuple.of(5, Arbitraries.just(0)),
                Tuple.of(3, Arbitraries.just(1)),
                Tuple.of(3, Arbitraries.just(2)));
        return Combinators.combine(kind, key, Arbitraries.integers().between(0, 1_000_000))
                .as(MapOp::new)
                .list().ofMaxSize(400);
    }
}
