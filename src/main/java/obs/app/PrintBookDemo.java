package obs.app;

import obs.core.Prices;
import obs.core.Side;
import obs.ref.RefOrderBook;

/** Builds the example book from Part 0 of the guide and walks through its matching example. */
public final class PrintBookDemo {

    public static void main(String[] args) {
        RefOrderBook book = new RefOrderBook(Prices.CENT,
                (aggressorId, restingId, price, qty, aggressorSide) -> System.out.printf(
                        "  TRADE %4d @ $%s   aggressor #%d (%s) vs resting #%d%n",
                        qty, Prices.format(price), aggressorId, Side.name(aggressorSide), restingId));

        book.addLimitOrder(1, Side.SELL, Prices.parse("150.03"), 900);
        book.addLimitOrder(2, Side.SELL, Prices.parse("150.02"), 400);
        book.addLimitOrder(3, Side.SELL, Prices.parse("150.01"), 200);
        book.addLimitOrder(4, Side.BUY, Prices.parse("150.00"), 500);
        book.addLimitOrder(5, Side.BUY, Prices.parse("149.99"), 1200);
        book.addLimitOrder(6, Side.BUY, Prices.parse("149.98"), 700);
        System.out.println("Starting book:");
        System.out.println(BookFormatter.render(book, 5));

        System.out.println("Incoming #7: BUY 500, limit $150.03");
        book.addLimitOrder(7, Side.BUY, Prices.parse("150.03"), 500);
        System.out.println();
        System.out.println(BookFormatter.render(book, 5));

        System.out.println("Incoming #8: BUY 5000, limit $150.03 (sweeps the asks, remainder rests)");
        book.addLimitOrder(8, Side.BUY, Prices.parse("150.03"), 5000);
        System.out.println();
        System.out.println(BookFormatter.render(book, 5));
    }
}
