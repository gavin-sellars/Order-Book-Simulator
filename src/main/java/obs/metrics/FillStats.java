package obs.metrics;

import java.util.HashSet;
import java.util.Set;

/** How often a strategy's orders actually trade. */
public final class FillStats {

    private final Set<Long> ordersWithFills = new HashSet<>();
    private long ordersSent;
    private long ordersFullyFilled;
    private long cancelsSent;
    private long sharesSent;
    private long sharesFilled;

    public void onOrderSent(int qty) {
        ordersSent++;
        sharesSent += qty;
    }

    public void onCancelSent() {
        cancelsSent++;
    }

    public void onFill(long clientId, int qty, int remaining) {
        ordersWithFills.add(clientId);
        sharesFilled += qty;
        if (remaining == 0) ordersFullyFilled++;
    }

    public long ordersSent() {
        return ordersSent;
    }

    public long ordersWithFills() {
        return ordersWithFills.size();
    }

    public long ordersFullyFilled() {
        return ordersFullyFilled;
    }

    public long cancelsSent() {
        return cancelsSent;
    }

    public long sharesSent() {
        return sharesSent;
    }

    public long sharesFilled() {
        return sharesFilled;
    }

    /** Fraction of orders sent that traded at least once. */
    public double fillRate() {
        return ordersSent == 0 ? 0 : (double) ordersWithFills.size() / ordersSent;
    }

    /** Fraction of shares sent that traded. */
    public double shareFillRate() {
        return sharesSent == 0 ? 0 : (double) sharesFilled / sharesSent;
    }
}
