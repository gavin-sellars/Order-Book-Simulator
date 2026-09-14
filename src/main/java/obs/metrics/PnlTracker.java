package obs.metrics;

/**
 * Cash, position and mark-to-market profit for one instrument, in the {@link obs.core.Prices}
 * convention (1/10,000 of a dollar per share), so there is no floating-point rounding.
 *
 * Exchange fees: a passive (resting) fill earns a rebate per share and an aggressive fill pays a
 * fee per share, as on most US equity venues. Fee amounts are also in price units per share
 * (for example, $0.0020 is 20).
 */
public final class PnlTracker {

    private final long makerRebatePerShare;
    private final long takerFeePerShare;
    private long cash;
    private long position;
    private long volume;
    private long netFees;           // fees paid minus rebates earned
    private long fills;

    public PnlTracker(long makerRebatePerShare, long takerFeePerShare) {
        if (makerRebatePerShare < 0 || takerFeePerShare < 0) throw new IllegalArgumentException("fees must be non-negative");
        this.makerRebatePerShare = makerRebatePerShare;
        this.takerFeePerShare = takerFeePerShare;
    }

    public void onFill(byte side, long price, int qty, boolean passive) {
        if (qty <= 0) throw new IllegalArgumentException("fill quantity must be positive");
        long notional = Math.multiplyExact(price, (long) qty);
        if (side == obs.core.Side.BUY) {
            cash -= notional;
            position += qty;
        } else {
            cash += notional;
            position -= qty;
        }
        long fee = passive ? -makerRebatePerShare * qty : takerFeePerShare * qty;
        cash -= fee;
        netFees += fee;
        volume += qty;
        fills++;
    }

    /** Shares held: positive long, negative short. */
    public long position() {
        return position;
    }

    public long cash() {
        return cash;
    }

    /** Cash plus the position valued at {@code price}: the P&L if the position were closed there for free. */
    public long markToMarket(long price) {
        return cash + position * price;
    }

    /** Fees paid minus rebates earned; negative means the rebates were larger. */
    public long netFees() {
        return netFees;
    }

    public long volume() {
        return volume;
    }

    public long fills() {
        return fills;
    }
}
