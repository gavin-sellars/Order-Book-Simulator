package obs.workload;

import com.sun.management.ThreadMXBean;
import obs.core.Book;
import obs.core.OrderBook;
import obs.ref.RefOrderBook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The zero-allocation claim as an ordinary test: the JVM counts every byte each thread allocates,
 * so replaying the tape must not move this thread's counter at all. JMH's -prof gc and the Epsilon
 * run make the same point for the benchmarks; this one runs on every build.
 */
class ZeroAllocationTest {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final MessageTape TAPE = MessageTape.generate(Workload.SEED, 100_000);

    @BeforeAll
    static void allocationCountingIsAvailable() {
        assumeTrue(THREADS.isThreadAllocatedMemorySupported() && THREADS.isThreadAllocatedMemoryEnabled());
        THREADS.getCurrentThreadAllocatedBytes();       // any one-off setup cost happens here, not in a measurement
    }

    @Test
    void fastBookAllocatesNothingWhileReplaying() {
        long[] tradedQty = new long[1];
        OrderBook book = Workload.newFastBook((aggressorId, restingId, price, qty, side) -> tradedQty[0] += qty);
        Workload.prefill(book);
        replay(book, 20);                               // let the JIT compile and settle first

        long allocated = allocatedDuring(book, 10);

        assertEquals(0, allocated, "bytes allocated replaying " + 10L * TAPE.length() + " messages");
        assertTrue(tradedQty[0] > 0, "the replay should have traded");
    }

    /** Guards against a broken measurement that always reads zero. */
    @Test
    void referenceBookAllocatesSoTheMeasurementWorks() {
        RefOrderBook book = Workload.newRefBook((aggressorId, restingId, price, qty, side) -> { });
        Workload.prefill(book);
        replay(book, 2);

        assertTrue(allocatedDuring(book, 1) > 1_000_000, "the TreeMap book should allocate heavily");
    }

    private static long allocatedDuring(Book book, int cycles) {
        long before = THREADS.getCurrentThreadAllocatedBytes();
        replay(book, cycles);
        return THREADS.getCurrentThreadAllocatedBytes() - before;
    }

    private static void replay(Book book, int cycles) {
        for (int c = 0; c < cycles; c++) {
            for (int i = 0; i < TAPE.length(); i++) TAPE.apply(book, i);
        }
    }
}
