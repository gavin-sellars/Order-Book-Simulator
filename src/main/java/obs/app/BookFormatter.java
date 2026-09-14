package obs.app;

import obs.core.Book;
import obs.core.Prices;
import obs.core.Side;

/** Draws the top of a book as the ladder picture used in Part 0 of the guide. Edge-of-system code. */
final class BookFormatter {

    private BookFormatter() {}

    static String render(Book book, int levels) {
        long[] prices = new long[levels];
        long[] qtys = new long[levels];
        int[] counts = new int[levels];
        StringBuilder sb = new StringBuilder();

        sb.append("        ASKS (sellers)\n");
        int asks = book.depth(Side.SELL, levels, prices, qtys, counts);
        for (int i = asks - 1; i >= 0; i--) {       // worst ask at the top, best ask next to the spread
            line(sb, prices[i], qtys[i], counts[i], i == 0 ? "  <-- best ask" : "");
        }

        long bid = book.bestBid();
        long ask = book.bestAsk();
        String spread = (bid == Book.NO_BID || ask == Book.NO_ASK)
                ? "n/a" : "$" + Prices.format(ask - bid);
        sb.append("  ---------------------------------  spread ").append(spread).append('\n');

        int bids = book.depth(Side.BUY, levels, prices, qtys, counts);
        for (int i = 0; i < bids; i++) {
            line(sb, prices[i], qtys[i], counts[i], i == 0 ? "  <-- best bid" : "");
        }
        sb.append("        BIDS (buyers)\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, long price, long qty, int count, String note) {
        sb.append(String.format("  $%-8s %6d shares (%d order%s)%s\n",
                Prices.format(price), qty, count, count == 1 ? "" : "s", note));
    }
}
