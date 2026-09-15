package obs.feed.itch;

import obs.core.Side;
import obs.feed.MessageHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.IntConsumer;

import static obs.feed.itch.ItchLayout.*;

/**
 * Reads a length-prefixed ITCH 5.0 stream and passes one stock's messages to a
 * {@link MessageHandler}.
 *
 * Fields are read by absolute offset straight out of the buffer: no message objects and no
 * allocation per message. A file is memory-mapped, so it is never copied onto the heap. Files
 * over 2 GB (a full real trading day) would need a {@code MemorySegment} version of this reader.
 *
 * The stock is found by ticker through its 'R' stock directory message, as with real files, where
 * locate codes change from day to day. System events are always delivered; messages for other
 * stocks, message types this reader doesn't know, and order messages before the directory entry
 * are skipped. A truncated stream, or a known message type with the wrong length, throws
 * {@link IllegalStateException}.
 *
 * Reading happens in two steps that can run on different threads: {@link #scan} walks the framing
 * and picks out the messages to deliver, and {@link #deliver} decodes one message. The buffer is
 * only ever read, so both threads can use one reader. {@link #replay} does both on one thread.
 */
public final class ItchReader {

    private final ByteBuffer buf;

    /** Reads from the buffer's position to its limit. The buffer itself is not modified. */
    public ItchReader(ByteBuffer buffer) {
        this.buf = buffer.slice().order(ByteOrder.BIG_ENDIAN);
    }

    public static ItchReader open(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() > Integer.MAX_VALUE) {
                throw new IOException(file + " is over 2 GB; this reader maps the file as a single ByteBuffer");
            }
            return new ItchReader(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
    }

    /** Delivers every system event and every message for {@code ticker}. Returns how many messages were delivered. */
    public long replay(String ticker, MessageHandler handler) {
        return scan(ticker, offset -> deliver(offset, handler));
    }

    /**
     * Walks the stream and passes the offset of every message {@link #replay} would deliver to
     * {@code sink}, in order, without decoding the messages. Returns how many offsets were passed.
     */
    public long scan(String ticker, IntConsumer sink) {
        byte[] wanted = alpha(ticker, 8);
        int locate = -1;
        long found = 0;
        int limit = buf.limit();
        int p = 0;

        while (p < limit) {
            if (limit - p < 2) throw new IllegalStateException("truncated length prefix at byte " + p);
            int length = Short.toUnsignedInt(buf.getShort(p));
            int m = p + 2;
            if (length == 0 || limit - m < length) {
                throw new IllegalStateException("truncated message at byte " + p + ": needs " + length + " bytes");
            }

            byte type = buf.get(m);
            int expected = lengthOf(type);
            if (expected >= 0 && expected != length) {
                throw new IllegalStateException("'" + (char) type + "' message at byte " + p + " has length "
                        + length + ", expected " + expected);
            }

            if (type == SYSTEM_EVENT) {
                sink.accept(m);
                found++;
            } else if (type == STOCK_DIRECTORY) {
                if (locate < 0 && fieldEquals(m + DIRECTORY_STOCK, wanted)) {
                    locate = Short.toUnsignedInt(buf.getShort(m + STOCK_LOCATE));
                }
            } else if (expected >= 0 && locate >= 0 && Short.toUnsignedInt(buf.getShort(m + STOCK_LOCATE)) == locate) {
                sink.accept(m);
                found++;
            }
            p = m + length;
        }
        return found;
    }

    /** Decodes the message at {@code offset} and passes it to {@code handler}. The offset must come from {@link #scan} on this reader. */
    public void deliver(int offset, MessageHandler handler) {
        long ts = timestamp(offset);
        int m = offset;
        switch (buf.get(m)) {
            case SYSTEM_EVENT -> handler.onSystemEvent(ts, buf.get(m + EVENT_CODE));
            case ADD_ORDER, ADD_ORDER_MPID -> handler.onAdd(ts, buf.getLong(m + ORDER_REF),
                    side(m + ADD_SIDE), shares(m + ADD_SHARES), price(m + ADD_PRICE));
            case ORDER_EXECUTED -> handler.onExecute(ts, buf.getLong(m + ORDER_REF),
                    shares(m + EXECUTED_SHARES), buf.getLong(m + EXECUTED_MATCH_NUMBER), -1);
            case ORDER_EXECUTED_WITH_PRICE -> handler.onExecute(ts, buf.getLong(m + ORDER_REF),
                    shares(m + EXECUTED_SHARES), buf.getLong(m + EXECUTED_MATCH_NUMBER), price(m + EXECUTED_PRICE));
            case ORDER_CANCEL -> handler.onCancel(ts, buf.getLong(m + ORDER_REF), shares(m + CANCELLED_SHARES));
            case ORDER_DELETE -> handler.onDelete(ts, buf.getLong(m + ORDER_REF));
            case ORDER_REPLACE -> handler.onReplace(ts, buf.getLong(m + REPLACE_ORIGINAL_REF),
                    buf.getLong(m + REPLACE_NEW_REF), shares(m + REPLACE_SHARES), price(m + REPLACE_PRICE));
            case TRADE -> handler.onHiddenTrade(ts, side(m + TRADE_SIDE), shares(m + TRADE_SHARES),
                    price(m + TRADE_PRICE), buf.getLong(m + TRADE_MATCH_NUMBER));
            default -> throw new IllegalArgumentException("no deliverable message at offset " + offset);
        }
    }

    /** Six bytes, big-endian, assembled without allocating. */
    private long timestamp(int m) {
        return ((long) Short.toUnsignedInt(buf.getShort(m + TIMESTAMP)) << 32)
                | Integer.toUnsignedLong(buf.getInt(m + TIMESTAMP + 2));
    }

    private byte side(int offset) {
        byte b = buf.get(offset);
        if (b == BUY) return Side.BUY;
        if (b == SELL) return Side.SELL;
        throw new IllegalStateException("bad side indicator " + b + " at byte " + offset);
    }

    private int shares(int offset) {
        int shares = buf.getInt(offset);
        if (shares < 0) throw new IllegalStateException("share count above 2^31 at byte " + offset);
        return shares;
    }

    private long price(int offset) {
        return Integer.toUnsignedLong(buf.getInt(offset));
    }

    private boolean fieldEquals(int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (buf.get(offset + i) != expected[i]) return false;
        }
        return true;
    }
}
