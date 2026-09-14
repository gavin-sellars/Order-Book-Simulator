package obs.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderPoolTest {

    @Test
    void handsOutEveryDistinctSlotThenFailsLoudly() {
        OrderPool pool = new OrderPool(5);
        Set<Integer> slots = new HashSet<>();

        for (int i = 0; i < 5; i++) {
            int slot = pool.allocate();
            assertTrue(slot >= 0 && slot < 5);
            assertTrue(slots.add(slot), "slot handed out twice: " + slot);
        }

        assertEquals(5, pool.liveCount());
        assertThrows(IllegalStateException.class, pool::allocate);
    }

    @Test
    void releasedSlotIsReusedWithCleanLinks() {
        OrderPool pool = new OrderPool(3);
        int a = pool.allocate();
        int b = pool.allocate();
        pool.next[a] = b;
        pool.prev[b] = a;

        pool.release(a);
        assertEquals(1, pool.liveCount());

        int again = pool.allocate();
        assertEquals(a, again, "most recently freed slot comes back first");
        assertEquals(-1, pool.next[again]);
        assertEquals(-1, pool.prev[again]);
        assertEquals(2, pool.liveCount());
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new OrderPool(0));
    }
}
