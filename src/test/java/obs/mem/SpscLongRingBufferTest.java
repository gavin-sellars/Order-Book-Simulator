package obs.mem;

import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpscLongRingBufferTest {

    private static final long EMPTY = Long.MIN_VALUE;

    @Test
    void rejectsCapacitiesThatAreNotPowersOfTwo() {
        for (int bad : new int[] {0, -8, 3, 100}) {
            assertThrows(IllegalArgumentException.class, () -> new SpscLongRingBuffer(bad), "capacity " + bad);
        }
        assertEquals(64, new SpscLongRingBuffer(64).capacity());
    }

    @Test
    void holdsExactlyItsCapacityInFirstInFirstOutOrder() {
        SpscLongRingBuffer ring = new SpscLongRingBuffer(8);

        assertEquals(EMPTY, ring.poll(EMPTY));
        for (long v = 0; v < 8; v++) assertTrue(ring.offer(v));
        assertFalse(ring.offer(8), "full");
        assertEquals(8, ring.size());

        for (long v = 0; v < 8; v++) assertEquals(v, ring.poll(EMPTY));
        assertEquals(EMPTY, ring.poll(EMPTY));
        assertTrue(ring.isEmpty());
    }

    @Test
    void wrapsAroundTheBackingArrayIndefinitely() {
        SpscLongRingBuffer ring = new SpscLongRingBuffer(4);
        long next = 0;
        long expected = 0;

        for (int round = 0; round < 10_000; round++) {
            int burst = 1 + round % 4;
            for (int i = 0; i < burst; i++) assertTrue(ring.offer(next++));
            for (int i = 0; i < burst; i++) assertEquals(expected++, ring.poll(EMPTY));
        }
        assertTrue(ring.isEmpty());
    }

    @Test
    void drainRespectsItsLimitAndDeliversOldestFirst() {
        SpscLongRingBuffer ring = new SpscLongRingBuffer(16);
        for (long v = 100; v < 110; v++) ring.offer(v);
        List<Long> seen = new ArrayList<>();

        assertEquals(4, ring.drain(seen::add, 4));
        assertEquals(List.of(100L, 101L, 102L, 103L), seen);
        assertEquals(6, ring.drain(seen::add, 100));
        assertEquals(0, ring.drain(seen::add, 100));
        assertEquals(10, seen.size());
        assertEquals(109L, seen.get(9));
    }

    @Test
    void aConsumerThatThrowsLeavesTheBatchUnread() {
        SpscLongRingBuffer ring = new SpscLongRingBuffer(8);
        ring.offer(1);
        ring.offer(2);

        assertThrows(IllegalStateException.class, () -> ring.drain(v -> { throw new IllegalStateException(); }, 8));

        assertEquals(1, ring.poll(EMPTY));
        assertEquals(2, ring.poll(EMPTY));
    }

    @Test
    void twoThreadsWithPollDeliverEveryValueInOrder() throws InterruptedException {
        stress(false);
    }

    @Test
    void twoThreadsWithDrainDeliverEveryValueInOrder() throws InterruptedException {
        stress(true);
    }

    /** A small ring, so both the full and the empty paths are hit constantly. */
    private static void stress(boolean useDrain) throws InterruptedException {
        final long count = 20_000_000;
        SpscLongRingBuffer ring = new SpscLongRingBuffer(1 << 10);
        AtomicLong received = new AtomicLong();
        AtomicReference<String> error = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            long[] expected = {0};
            while (expected[0] < count && error.get() == null) {
                if (useDrain) {
                    int n = ring.drain(v -> {
                        if (v != expected[0] && error.get() == null) error.set("expected " + expected[0] + " but got " + v);
                        expected[0]++;
                    }, 256);
                    if (n == 0) Thread.onSpinWait();
                } else {
                    long v = ring.poll(EMPTY);
                    if (v == EMPTY) {
                        Thread.onSpinWait();
                    } else {
                        if (v != expected[0]) error.set("expected " + expected[0] + " but got " + v);
                        expected[0]++;
                    }
                }
            }
            received.set(expected[0]);
        }, "ring-consumer");
        consumer.start();

        for (long v = 0; v < count; v++) {
            while (!ring.offer(v)) Thread.onSpinWait();
        }
        consumer.join(60_000);

        assertFalse(consumer.isAlive(), "consumer did not finish");
        assertNull(error.get());
        assertEquals(count, received.get());
        assertTrue(ring.isEmpty());
    }

    /** Fields written by different threads must be at least a cache line apart, and away from the object's edges. */
    @Test
    void producerAndConsumerFieldsNeverShareACacheLine() {
        ClassLayout layout = ClassLayout.parseClass(SpscLongRingBuffer.class);
        long writeSeq = offset(layout, "writeSeq");
        long cachedReadSeq = offset(layout, "cachedReadSeq");
        long readSeq = offset(layout, "readSeq");
        long cachedWriteSeq = offset(layout, "cachedWriteSeq");

        long producerStart = Math.min(writeSeq, cachedReadSeq);
        long producerEnd = Math.max(writeSeq, cachedReadSeq) + 8;
        long consumerStart = Math.min(readSeq, cachedWriteSeq);
        long consumerEnd = Math.max(readSeq, cachedWriteSeq) + 8;
        String printed = layout.toPrintable();

        assertTrue(producerStart >= 64, "producer fields too close to the object header:\n" + printed);
        assertTrue(consumerStart - producerEnd >= 56 || producerStart - consumerEnd >= 56,
                "producer and consumer fields within a cache line:\n" + printed);
        assertTrue(offset(layout, "buffer") - consumerEnd >= 56, "consumer fields too close to the shared fields:\n" + printed);
        assertTrue(layout.instanceSize() - consumerEnd >= 56, "consumer fields too close to the next object:\n" + printed);
    }

    private static long offset(ClassLayout layout, String field) {
        return layout.fields().stream()
                .filter(f -> f.name().equals(field))
                .mapToLong(FieldLayout::offset)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + field));
    }
}
