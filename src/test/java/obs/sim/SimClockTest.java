package obs.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimClockTest {

    private final SimClock clock = new SimClock(1_000);
    private final List<String> fired = new ArrayList<>();

    private SimClock.Action record(String name) {
        return now -> fired.add(name + "@" + now);
    }

    @Test
    void firesEventsInTimeOrderRegardlessOfSchedulingOrder() {
        clock.schedule(3_000, record("c"));
        clock.schedule(1_500, record("a"));
        clock.schedule(2_000, record("b"));

        clock.runThrough(10_000);

        assertEquals(List.of("a@1500", "b@2000", "c@3000"), fired);
        assertEquals(10_000, clock.now());
    }

    @Test
    void eventsAtTheSameTimeFireInTheOrderTheyWereScheduled() {
        for (String name : new String[] {"first", "second", "third", "fourth"}) clock.schedule(2_000, record(name));

        clock.runThrough(2_000);

        assertEquals(List.of("first@2000", "second@2000", "third@2000", "fourth@2000"), fired);
    }

    @Test
    void advanceToLeavesEventsAtExactlyThatTimePending() {
        clock.schedule(1_999, record("before"));
        clock.schedule(2_000, record("at"));

        clock.advanceTo(2_000);
        assertEquals(List.of("before@1999"), fired);
        assertEquals(2_000, clock.now());
        assertEquals(1, clock.pending());

        clock.advanceTo(2_001);
        assertEquals(List.of("before@1999", "at@2000"), fired);
    }

    @Test
    void eventsCanScheduleMoreEventsThatFireInTheSameRun() {
        clock.schedule(2_000, now -> {
            fired.add("parent@" + now);
            clock.scheduleAfter(0, record("child"));
            clock.scheduleAfter(500, record("later"));
        });

        clock.runThrough(2_500);

        assertEquals(List.of("parent@2000", "child@2000", "later@2500"), fired);
    }

    @Test
    void timeNeverGoesBackwards() {
        clock.advanceTo(5_000);

        assertThrows(IllegalArgumentException.class, () -> clock.schedule(4_999, record("past")));
        assertThrows(IllegalArgumentException.class, () -> clock.scheduleAfter(-1, record("past")));
        assertThrows(IllegalArgumentException.class, () -> clock.advanceTo(4_000));
        assertThrows(IllegalArgumentException.class, () -> clock.runThrough(4_000));
    }
}
