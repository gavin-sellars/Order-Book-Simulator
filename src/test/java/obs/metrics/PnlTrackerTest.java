package obs.metrics;

import obs.core.Prices;
import obs.core.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PnlTrackerTest {

    private static long px(String price) {
        return Prices.parse(price);
    }

    @Test
    void capturingTheSpreadPassivelyEarnsTheSpreadPlusRebates() {
        PnlTracker pnl = new PnlTracker(px("0.0020"), px("0.0030"));

        pnl.onFill(Side.BUY, px("150.00"), 100, true);
        pnl.onFill(Side.SELL, px("150.02"), 100, true);

        assertEquals(0, pnl.position());
        assertEquals(px("2.40"), pnl.cash(), "$2.00 of spread plus two $0.20 rebates");
        assertEquals(px("2.40"), pnl.markToMarket(px("999.00")), "a flat position doesn't depend on the mark");
        assertEquals(-px("0.40"), pnl.netFees());
        assertEquals(200, pnl.volume());
        assertEquals(2, pnl.fills());
    }

    @Test
    void anOpenPositionIsValuedAtTheMark() {
        PnlTracker pnl = new PnlTracker(px("0.0020"), px("0.0030"));

        pnl.onFill(Side.BUY, px("150.00"), 100, false);        // took liquidity: pays the fee

        assertEquals(100, pnl.position());
        assertEquals(-px("15000.30"), pnl.cash());
        assertEquals(px("0.70"), pnl.markToMarket(px("150.01")), "up $1.00 on the price, down $0.30 in fees");
        assertEquals(-px("1.30"), pnl.markToMarket(px("149.99")));
    }

    @Test
    void shortPositionsGainWhenThePriceFalls() {
        PnlTracker pnl = new PnlTracker(0, 0);

        pnl.onFill(Side.SELL, px("150.00"), 300, true);

        assertEquals(-300, pnl.position());
        assertEquals(px("3.00"), pnl.markToMarket(px("149.99")));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> new PnlTracker(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PnlTracker(0, 0).onFill(Side.BUY, 100, 0, true));
    }

    @Test
    void fillStatsCountOrdersAndShares() {
        FillStats stats = new FillStats();
        stats.onOrderSent(100);
        stats.onOrderSent(100);
        stats.onOrderSent(200);
        stats.onCancelSent();

        stats.onFill(1, 40, 60);
        stats.onFill(1, 60, 0);
        stats.onFill(3, 50, 150);

        assertEquals(3, stats.ordersSent());
        assertEquals(2, stats.ordersWithFills());
        assertEquals(1, stats.ordersFullyFilled());
        assertEquals(1, stats.cancelsSent());
        assertEquals(2.0 / 3, stats.fillRate(), 1e-12);
        assertEquals(150.0 / 400, stats.shareFillRate(), 1e-12);
    }
}
