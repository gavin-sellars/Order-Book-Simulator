package obs.ref;

import obs.core.Book;
import obs.core.BookContractTest;
import obs.core.Prices;
import obs.core.TradeListener;

/** Runs the shared book scenarios against the reference book. */
class RefOrderBookTest extends BookContractTest {

    @Override
    protected Book newBook(TradeListener listener) {
        return new RefOrderBook(Prices.CENT, listener);
    }
}
