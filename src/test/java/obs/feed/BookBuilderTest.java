package obs.feed;

import obs.core.OrderBook;
import obs.core.Prices;
import obs.core.Side;
import obs.core.TradeListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookBuilderTest {

    private final OrderBook book = new OrderBook(Prices.parse("100.00"), Prices.CENT, 1000, 64, TradeListener.NONE);
    private final BookBuilder builder = new BookBuilder(book);

    @Test
    void messagesForUnknownOrdersAreCountedSeparatelyFromOtherRejections() {
        long px = Prices.parse("101.00");
        builder.onAdd(0, 1, Side.BUY, 100, px);
        builder.onAdd(0, 1, Side.BUY, 100, px);          // duplicate reference: rejected, but the order exists
        builder.onExecute(0, 1, 500, 1, -1);             // more than is resting: rejected, but the order exists
        builder.onDelete(0, 99);                         // unknown
        builder.onCancel(0, 98, 10);                     // unknown
        builder.onExecute(0, 97, 10, 2, -1);             // unknown
        builder.onReplace(0, 96, 2, 100, px);            // unknown
        builder.onDelete(0, 1);

        assertEquals(8, builder.orderMessages());
        assertEquals(6, builder.rejectedMessages());
        assertEquals(4, builder.unknownRefMessages());
        assertEquals(0, book.orderCount());
    }
}
