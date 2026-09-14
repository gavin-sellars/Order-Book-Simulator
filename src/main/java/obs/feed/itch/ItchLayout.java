package obs.feed.itch;

import java.nio.charset.StandardCharsets;

/**
 * Nasdaq TotalView-ITCH 5.0 message types, lengths and field offsets. The writer and reader both
 * use these constants; the fixture tests check them against bytes written out by hand from the
 * specification, so a shared mistake here can't pass unnoticed.
 *
 * Offsets are from the start of the message (the type byte), not from the 2-byte length prefix.
 * All integers are big-endian. Prices are unsigned 4-byte integers with 4 implied decimal places,
 * the same convention as {@link obs.core.Prices}.
 */
public final class ItchLayout {

    private ItchLayout() {}

    // ---------------------------------------------------------------- header, common to every message

    public static final int TYPE = 0;
    public static final int STOCK_LOCATE = 1;         // 2 bytes; 0 for market-wide messages
    public static final int TRACKING_NUMBER = 3;      // 2 bytes
    public static final int TIMESTAMP = 5;            // 6 bytes, nanoseconds since midnight
    public static final int HEADER_LENGTH = 11;

    public static final long MAX_TIMESTAMP = (1L << 48) - 1;
    public static final long MAX_PRICE = 0xFFFF_FFFFL;

    // ---------------------------------------------------------------- message types and lengths

    public static final byte SYSTEM_EVENT = 'S';
    public static final byte STOCK_DIRECTORY = 'R';
    public static final byte ADD_ORDER = 'A';
    public static final byte ADD_ORDER_MPID = 'F';
    public static final byte ORDER_EXECUTED = 'E';
    public static final byte ORDER_EXECUTED_WITH_PRICE = 'C';
    public static final byte ORDER_CANCEL = 'X';
    public static final byte ORDER_DELETE = 'D';
    public static final byte ORDER_REPLACE = 'U';
    public static final byte TRADE = 'P';

    public static final int SYSTEM_EVENT_LENGTH = 12;
    public static final int STOCK_DIRECTORY_LENGTH = 39;
    public static final int ADD_ORDER_LENGTH = 36;
    public static final int ADD_ORDER_MPID_LENGTH = 40;
    public static final int ORDER_EXECUTED_LENGTH = 31;
    public static final int ORDER_EXECUTED_WITH_PRICE_LENGTH = 36;
    public static final int ORDER_CANCEL_LENGTH = 23;
    public static final int ORDER_DELETE_LENGTH = 19;
    public static final int ORDER_REPLACE_LENGTH = 35;
    public static final int TRADE_LENGTH = 44;

    /** Length of a message type this code understands, or -1 for types it skips. */
    public static int lengthOf(byte type) {
        return switch (type) {
            case SYSTEM_EVENT -> SYSTEM_EVENT_LENGTH;
            case STOCK_DIRECTORY -> STOCK_DIRECTORY_LENGTH;
            case ADD_ORDER -> ADD_ORDER_LENGTH;
            case ADD_ORDER_MPID -> ADD_ORDER_MPID_LENGTH;
            case ORDER_EXECUTED -> ORDER_EXECUTED_LENGTH;
            case ORDER_EXECUTED_WITH_PRICE -> ORDER_EXECUTED_WITH_PRICE_LENGTH;
            case ORDER_CANCEL -> ORDER_CANCEL_LENGTH;
            case ORDER_DELETE -> ORDER_DELETE_LENGTH;
            case ORDER_REPLACE -> ORDER_REPLACE_LENGTH;
            case TRADE -> TRADE_LENGTH;
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- 'S' system event

    public static final int EVENT_CODE = 11;

    public static final byte START_OF_MESSAGES = 'O';
    public static final byte START_OF_SYSTEM_HOURS = 'S';
    public static final byte START_OF_MARKET_HOURS = 'Q';
    public static final byte END_OF_MARKET_HOURS = 'M';
    public static final byte END_OF_SYSTEM_HOURS = 'E';
    public static final byte END_OF_MESSAGES = 'C';

    // ---------------------------------------------------------------- 'R' stock directory

    public static final int DIRECTORY_STOCK = 11;                 // 8 bytes, space padded
    public static final int DIRECTORY_MARKET_CATEGORY = 19;
    public static final int DIRECTORY_FINANCIAL_STATUS = 20;
    public static final int DIRECTORY_ROUND_LOT_SIZE = 21;        // 4 bytes
    public static final int DIRECTORY_ROUND_LOTS_ONLY = 25;
    public static final int DIRECTORY_ISSUE_CLASSIFICATION = 26;
    public static final int DIRECTORY_ISSUE_SUBTYPE = 27;         // 2 bytes
    public static final int DIRECTORY_AUTHENTICITY = 29;
    public static final int DIRECTORY_SHORT_SALE_THRESHOLD = 30;
    public static final int DIRECTORY_IPO_FLAG = 31;
    public static final int DIRECTORY_LULD_TIER = 32;
    public static final int DIRECTORY_ETP_FLAG = 33;
    public static final int DIRECTORY_ETP_LEVERAGE = 34;          // 4 bytes
    public static final int DIRECTORY_INVERSE = 38;

    // ---------------------------------------------------------------- order messages

    public static final int ORDER_REF = 11;                       // 8 bytes, in A F E C X D P

    public static final int ADD_SIDE = 19;                        // 'B' or 'S'
    public static final int ADD_SHARES = 20;                      // 4 bytes
    public static final int ADD_STOCK = 24;                       // 8 bytes
    public static final int ADD_PRICE = 32;                       // 4 bytes
    public static final int ADD_ATTRIBUTION = 36;                 // 4 bytes, 'F' only

    public static final int EXECUTED_SHARES = 19;                 // 4 bytes, E and C
    public static final int EXECUTED_MATCH_NUMBER = 23;           // 8 bytes, E and C
    public static final int EXECUTED_PRINTABLE = 31;              // C only
    public static final int EXECUTED_PRICE = 32;                  // 4 bytes, C only

    public static final int CANCELLED_SHARES = 19;                // 4 bytes

    public static final int REPLACE_ORIGINAL_REF = 11;            // 8 bytes
    public static final int REPLACE_NEW_REF = 19;                 // 8 bytes
    public static final int REPLACE_SHARES = 27;                  // 4 bytes
    public static final int REPLACE_PRICE = 31;                   // 4 bytes

    public static final int TRADE_SIDE = 19;
    public static final int TRADE_SHARES = 20;                    // 4 bytes
    public static final int TRADE_STOCK = 24;                     // 8 bytes
    public static final int TRADE_PRICE = 32;                     // 4 bytes
    public static final int TRADE_MATCH_NUMBER = 36;              // 8 bytes

    public static final byte BUY = 'B';
    public static final byte SELL = 'S';

    /** An ITCH alpha field: 1 to 8 printable ASCII characters, left-justified and padded with spaces. */
    public static byte[] alpha(String text, int width) {
        byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
        if (ascii.length == 0 || ascii.length > width) {
            throw new IllegalArgumentException("\"" + text + "\" must be 1 to " + width + " characters");
        }
        byte[] field = new byte[width];
        for (int i = 0; i < width; i++) {
            byte b = i < ascii.length ? ascii[i] : (byte) ' ';
            if (b < 0x20 || b > 0x7E || (i < ascii.length && text.charAt(i) > 0x7E)) {
                throw new IllegalArgumentException("\"" + text + "\" must be printable ASCII");
            }
            field[i] = b;
        }
        return field;
    }
}
