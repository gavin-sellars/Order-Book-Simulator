package obs.core;

/** Every OrderBookTest and shared book test again, with the level-by-level touch search. */
class OrderBookLinearScanTest extends OrderBookTest {

    @Override
    OrderBook.TouchSearch touchSearch() {
        return OrderBook.TouchSearch.LINEAR_SCAN;
    }
}
