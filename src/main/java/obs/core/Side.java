package obs.core;

/** Order side as a primitive byte, so it can live in primitive arrays without boxing. */
public final class Side {

    public static final byte BUY = 0;
    public static final byte SELL = 1;

    private Side() {}

    public static boolean isValid(byte side) {
        return side == BUY || side == SELL;
    }

    /** Only meaningful for a valid side. */
    public static byte opposite(byte side) {
        return (byte) (1 - side);
    }

    public static String name(byte side) {
        return switch (side) {
            case BUY -> "BUY";
            case SELL -> "SELL";
            default -> "INVALID(" + side + ")";
        };
    }
}
