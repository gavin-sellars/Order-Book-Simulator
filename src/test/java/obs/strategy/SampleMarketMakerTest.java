package obs.strategy;

import obs.core.Book;
import obs.core.Prices;
import obs.core.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the market maker directly with a fake context that records what it sends. */
class SampleMarketMakerTest {

    private final List<String> sent = new ArrayList<>();
    private final SampleMarketMaker maker = new SampleMarketMaker(100, 300);
    private long nextId = 1;

    private static long px(String price) {
        return Prices.parse(price);
    }

    @BeforeEach
    void init() {
        maker.init(new StrategyContext() {
            @Override
            public long now() {
                return 0;
            }

            @Override
            public long tickSize() {
                return Prices.CENT;
            }

            @Override
            public long sendLimit(byte side, long price, int qty) {
                long id = nextId++;
                sent.add("#" + id + " " + Side.name(side) + " " + qty + " @ " + Prices.format(price));
                return id;
            }

            @Override
            public void cancel(long clientId) {
                sent.add("cancel #" + clientId);
            }
        });
    }

    private void book(String bid, String ask) {
        maker.onBookUpdate(0, px(bid), px(ask), 100, 100);
    }

    @Test
    void joinsTheTouchWhenTheSpreadIsNarrow() {
        book("150.00", "150.01");

        assertEquals(List.of("#1 BUY 100 @ 150.00", "#2 SELL 100 @ 150.01"), sent);
    }

    @Test
    void quotesOneTickInsideAWideSpread() {
        book("150.00", "150.03");

        assertEquals(List.of("#1 BUY 100 @ 150.01", "#2 SELL 100 @ 150.02"), sent);
    }

    @Test
    void doesNothingWithoutATwoSidedBook() {
        maker.onBookUpdate(0, Book.NO_BID, px("150.01"), 0, 100);
        maker.onBookUpdate(0, px("150.00"), Book.NO_ASK, 100, 0);

        assertTrue(sent.isEmpty());
    }

    @Test
    void cancelsWhenTheTouchMovesAndRequotesOnlyAfterTheAcknowledgement() {
        book("150.00", "150.01");
        sent.clear();

        book("150.01", "150.02");
        assertEquals(List.of("cancel #1", "cancel #2"), sent);

        book("150.02", "150.03");                          // still waiting: no new orders, no repeat cancels
        assertEquals(List.of("cancel #1", "cancel #2"), sent);

        maker.onOwnCancelAck(0, 1, 100);
        maker.onOwnCancelAck(0, 2, 100);
        assertEquals(List.of("cancel #1", "cancel #2", "#3 BUY 100 @ 150.02", "#4 SELL 100 @ 150.03"), sent);
    }

    @Test
    void anOrderThatFillsWhileItsCancelIsInFlightDoesNotFreezeThatSide() {
        book("150.00", "150.01");
        book("150.01", "150.02");                          // cancels #1 and #2
        sent.clear();

        maker.onOwnFill(0, 1, Side.BUY, px("150.00"), 100, 0);     // #1 filled before the cancel reached it
        maker.onOwnCancelAck(0, 1, 0);                             // the late acknowledgement: nothing cancelled

        assertEquals(List.of("#3 BUY 100 @ 150.01"), sent, "the bid side requotes straight after the fill");

        book("150.02", "150.03");
        assertTrue(sent.contains("cancel #3"), "and keeps following the market: " + sent);
    }

    @Test
    void stopsAddingToAPositionAtTheLimit() {
        book("150.00", "150.01");
        maker.onOwnFill(0, 1, Side.BUY, px("150.00"), 100, 0);     // position 100: requotes the bid
        maker.onOwnFill(0, 3, Side.BUY, px("150.00"), 100, 0);     // 200
        maker.onOwnFill(0, 4, Side.BUY, px("150.00"), 100, 0);     // 300: at the limit
        sent.clear();

        book("150.00", "150.01");

        assertEquals(300, maker.position());
        assertTrue(sent.stream().noneMatch(s -> s.contains("BUY")), "no new bids at the limit: " + sent);

        maker.onOwnFill(0, 2, Side.SELL, px("150.01"), 100, 0);    // position 200: bids allowed again
        assertTrue(sent.stream().anyMatch(s -> s.contains("BUY")), "bids resume below the limit: " + sent);
    }

    @Test
    void cancelsEverythingAtTheCloseAndStopsQuoting() {
        book("150.00", "150.01");
        sent.clear();

        maker.onSessionEnd(0);
        book("150.05", "150.06");

        assertEquals(List.of("cancel #1", "cancel #2"), sent);
    }
}
