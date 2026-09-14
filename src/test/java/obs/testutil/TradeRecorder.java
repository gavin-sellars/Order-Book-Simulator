package obs.testutil;

import obs.core.TradeListener;

import java.util.ArrayList;
import java.util.List;

/** Collects trades as records so tests can compare them with assertEquals. */
public final class TradeRecorder implements TradeListener {

    public record Trade(long aggressorId, long restingId, long price, int qty, byte aggressorSide) {}

    private final List<Trade> trades = new ArrayList<>();

    @Override
    public void onTrade(long aggressorId, long restingId, long price, int qty, byte aggressorSide) {
        trades.add(new Trade(aggressorId, restingId, price, qty, aggressorSide));
    }

    public boolean isEmpty() {
        return trades.isEmpty();
    }

    /** Returns the trades recorded since the last drain and forgets them. */
    public List<Trade> drain() {
        List<Trade> out = List.copyOf(trades);
        trades.clear();
        return out;
    }
}
