package obs.feed;

import obs.core.Book;
import obs.core.OrderResult;

/**
 * Applies a feed's order messages to a {@link Book} in book-builder mode: the exchange has
 * already matched everything, so adds rest as given and executions come from the feed.
 * Trades reach the book's own {@link obs.core.TradeListener}.
 *
 * A message the book can't apply (an unknown order reference, an execution larger than the
 * order) is counted rather than thrown, so a replay of imperfect data can finish and report.
 * Allocates nothing per message.
 */
public final class BookBuilder implements MessageHandler {

    private final Book book;
    private long orderMessages;
    private long rejectedMessages;

    public BookBuilder(Book book) {
        this.book = book;
    }

    @Override
    public void onAdd(long timestamp, long orderRef, byte side, int shares, long price) {
        applied(book.addRestingOrder(orderRef, side, price, shares) == OrderResult.ACCEPTED);
    }

    @Override
    public void onExecute(long timestamp, long orderRef, int shares, long matchNumber, long price) {
        applied(book.execute(orderRef, shares));
    }

    @Override
    public void onCancel(long timestamp, long orderRef, int shares) {
        applied(book.reduce(orderRef, shares));
    }

    @Override
    public void onDelete(long timestamp, long orderRef) {
        applied(book.cancel(orderRef));
    }

    @Override
    public void onReplace(long timestamp, long oldRef, long newRef, int shares, long price) {
        applied(book.replace(oldRef, newRef, price, shares) == OrderResult.ACCEPTED);
    }

    /** Order messages seen: adds, executions, cancels, deletes and replaces. */
    public long orderMessages() {
        return orderMessages;
    }

    /** Order messages the book could not apply. Zero for a consistent feed. */
    public long rejectedMessages() {
        return rejectedMessages;
    }

    private void applied(boolean ok) {
        orderMessages++;
        if (!ok) rejectedMessages++;
    }
}
