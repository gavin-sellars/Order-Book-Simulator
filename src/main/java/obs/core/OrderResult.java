package obs.core;

/** Result codes returned by order entry methods. Plain ints, so rejecting an order never allocates. */
public final class OrderResult {

    public static final int ACCEPTED = 0;
    public static final int REJECTED_SIDE = 1;
    public static final int REJECTED_QTY = 2;
    public static final int REJECTED_PRICE = 3;
    public static final int REJECTED_DUP_ID = 4;
    public static final int REJECTED_UNKNOWN_ID = 5;
    /** Used by the simulator for orders that reach the exchange outside market hours. */
    public static final int REJECTED_MARKET_CLOSED = 6;

    private OrderResult() {}

    public static String name(int result) {
        return switch (result) {
            case ACCEPTED -> "ACCEPTED";
            case REJECTED_SIDE -> "REJECTED_SIDE";
            case REJECTED_QTY -> "REJECTED_QTY";
            case REJECTED_PRICE -> "REJECTED_PRICE";
            case REJECTED_DUP_ID -> "REJECTED_DUP_ID";
            case REJECTED_UNKNOWN_ID -> "REJECTED_UNKNOWN_ID";
            case REJECTED_MARKET_CLOSED -> "REJECTED_MARKET_CLOSED";
            default -> "UNKNOWN(" + result + ")";
        };
    }
}
