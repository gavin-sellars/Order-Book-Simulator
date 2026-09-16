package obs.ref;

import obs.core.Book;
import obs.core.BookContractTest;
import obs.core.Prices;
import obs.core.TradeListener;

/** Runs the shared book scenarios against the O(1)-cancel variant of the reference book. */
class RefLinkedOrderBookTest extends BookContractTest {

    @Override
    protected Book newBook(TradeListener listener) {
        return new RefLinkedOrderBook(Prices.CENT, listener);
    }
}
