package obs.sim;

import java.util.PriorityQueue;

/**
 * Discrete-event simulation clock (Part 6 of the guide). Time jumps straight to the next event,
 * so quiet periods cost nothing. Events at the same time fire in the order they were scheduled,
 * so a run with the same inputs always fires the same events in the same order.
 *
 * Times are nanoseconds, on whatever timeline the caller uses (ITCH: since midnight).
 * Not thread-safe; a simulation runs on one thread.
 */
public final class SimClock {

    @FunctionalInterface
    public interface Action {
        void fire(long now);
    }

    private record Scheduled(long time, long sequence, Action action) implements Comparable<Scheduled> {
        @Override
        public int compareTo(Scheduled other) {
            int byTime = Long.compare(time, other.time);
            return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
        }
    }

    private final PriorityQueue<Scheduled> queue = new PriorityQueue<>();
    private long now;
    private long nextSequence;

    public SimClock(long startTime) {
        this.now = startTime;
    }

    public long now() {
        return now;
    }

    /** Number of events waiting to fire. */
    public int pending() {
        return queue.size();
    }

    public void schedule(long time, Action action) {
        if (time < now) throw new IllegalArgumentException("cannot schedule at " + time + ", the clock is already at " + now);
        queue.add(new Scheduled(time, nextSequence++, action));
    }

    public void scheduleAfter(long delay, Action action) {
        if (delay < 0) throw new IllegalArgumentException("negative delay " + delay);
        schedule(now + delay, action);
    }

    /**
     * Fires every event scheduled strictly before {@code time}, including events those events
     * schedule, then moves the clock to {@code time}. Events at exactly {@code time} stay pending,
     * so work the caller does at {@code time} happens before them.
     */
    public void advanceTo(long time) {
        if (time < now) throw new IllegalArgumentException("cannot move the clock back from " + now + " to " + time);
        while (!queue.isEmpty() && queue.peek().time < time) fireNext();
        now = time;
    }

    /** Fires every event at or before {@code time}, including events those events schedule, then moves the clock to {@code time}. */
    public void runThrough(long time) {
        if (time < now) throw new IllegalArgumentException("cannot move the clock back from " + now + " to " + time);
        while (!queue.isEmpty() && queue.peek().time <= time) fireNext();
        now = time;
    }

    private void fireNext() {
        Scheduled next = queue.poll();
        now = next.time;
        next.action.fire(now);
    }
}
