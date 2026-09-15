package obs.feed;

import obs.feed.itch.ItchReader;
import obs.mem.SpscLongRingBuffer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

/**
 * Replays ITCH on two threads (Part 4 of the guide): the calling thread walks the file's framing
 * and publishes each message's offset into a {@link SpscLongRingBuffer}; a second thread takes the
 * offsets, decodes the messages and applies them to the handler. The handler, and so the book, is
 * only ever touched by that one thread: the single-writer principle.
 *
 * The file is shared read-only, so only an 8-byte offset crosses between the threads.
 */
public final class PipelinedReplay {

    /** What a thread does when it finds the ring empty (consumer) or full (producer). */
    public enum IdleStrategy {
        /** Busy-spin: lowest latency, burns a core at 100%. */
        SPIN {
            @Override
            void idle() {
                Thread.onSpinWait();
            }
        },
        /** Gives up the time slice; the OS may run something else. */
        YIELD {
            @Override
            void idle() {
                Thread.yield();
            }
        },
        /** Sleeps briefly; cheapest on CPU, slowest to wake up. */
        PARK {
            @Override
            void idle() {
                LockSupport.parkNanos(1);
            }
        };

        abstract void idle();
    }

    private static final int BATCH = 1024;

    private PipelinedReplay() {}

    /**
     * Replays every system event and every message for {@code ticker} into {@code handler} on a new
     * thread, and returns when all of them have been applied. Returns how many messages were delivered.
     * An exception thrown by the handler stops the replay and is rethrown here.
     */
    public static long run(ItchReader reader, String ticker, MessageHandler handler, int ringCapacity, IdleStrategy idle)
            throws InterruptedException {
        SpscLongRingBuffer ring = new SpscLongRingBuffer(ringCapacity);
        AtomicBoolean producerDone = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            LongConsumer apply = offset -> reader.deliver((int) offset, handler);
            try {
                while (true) {
                    boolean finished = producerDone.get();         // read before draining, so nothing published after it is missed
                    if (ring.drain(apply, BATCH) == 0) {
                        if (finished) return;
                        idle.idle();
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "obs-book");
        consumer.start();

        long delivered;
        try {
            delivered = reader.scan(ticker, offset -> {
                while (!ring.offer(offset)) {
                    if (failure.get() != null) throw new IllegalStateException("book thread failed", failure.get());
                    idle.idle();
                }
            });
        } finally {
            producerDone.set(true);
            consumer.join();
        }

        Throwable t = failure.get();
        if (t instanceof RuntimeException e) throw e;
        if (t instanceof Error e) throw e;
        if (t != null) throw new IllegalStateException(t);
        return delivered;
    }
}
