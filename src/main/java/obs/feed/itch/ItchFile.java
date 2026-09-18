package obs.feed.itch;

import obs.core.Side;
import obs.feed.MessageHandler;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static obs.feed.itch.ItchLayout.*;

/**
 * A length-prefixed ITCH 5.0 file of any size, memory-mapped as one {@link MemorySegment}.
 *
 * {@link ItchReader} maps a file as a {@code ByteBuffer}, so it stops at 2 GB; a real Nasdaq day
 * is around 30 GB and holds every stock. This class walks such a file without copying it, for
 * surveying a day and for cutting out the messages of a few stocks ({@link ItchExtractor}). The
 * per-stock files it produces are small enough for {@link ItchReader}, which the replay benchmarks
 * keep using so their numbers stay comparable with the synthetic session's.
 *
 * Unlike {@link ItchReader}, this walks every stock and every message type. Fields are read by
 * absolute offset; nothing is allocated per message. Not thread-safe to close while in use.
 */
public final class ItchFile implements AutoCloseable {

    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final Arena arena;
    private final MemorySegment data;

    private ItchFile(Arena arena, MemorySegment data) {
        this.arena = arena;
        this.data = data;
    }

    public static ItchFile open(Path file) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return new ItchFile(arena, channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena));
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** Wraps bytes already in memory, such as a test fixture. */
    public static ItchFile of(byte[] bytes) {
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(Math.max(1, bytes.length)).asSlice(0, bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);
        return new ItchFile(arena, segment);
    }

    /** Reads bytes someone else owns, such as a chunk of a larger file. Closing the view doesn't free them. */
    public static ItchFile view(MemorySegment bytes) {
        return new ItchFile(null, bytes);
    }

    public long size() {
        return data.byteSize();
    }

    /** Receives each message in file order. {@code offset} is the type byte's position, as {@link #deliver} expects. */
    @FunctionalInterface
    public interface Visitor {
        void message(long offset, byte type, int length, int locate);
    }

    /**
     * Walks the framing of the whole file. A truncated stream, or a type this code knows arriving
     * with the wrong length, throws {@link IllegalStateException}. Returns the number of messages.
     */
    public long forEach(Visitor visitor) {
        long[] count = new long[1];
        walk(visitor, false, count);
        return count[0];
    }

    /**
     * Walks the complete messages at the start of a chunk and stops before one cut off by the end,
     * for reading a file a chunk at a time. Returns the number of bytes consumed; the rest belongs
     * at the start of the next chunk.
     */
    public long forEachComplete(Visitor visitor) {
        return walk(visitor, true, new long[1]);
    }

    private long walk(Visitor visitor, boolean stopAtCut, long[] count) {
        long limit = data.byteSize();
        long p = 0;
        while (p < limit) {
            if (limit - p < 2) {
                if (stopAtCut) return p;
                throw new IllegalStateException("truncated length prefix at byte " + p);
            }
            int length = Short.toUnsignedInt(data.get(SHORT, p));
            long m = p + 2;
            if (length < 1) throw new IllegalStateException("zero-length message at byte " + p);
            if (limit - m < length) {
                if (stopAtCut) return p;
                throw new IllegalStateException("truncated message at byte " + p + ": needs " + length + " bytes");
            }
            byte type = data.get(ValueLayout.JAVA_BYTE, m);
            int expected = lengthOf(type);
            if (expected >= 0 && expected != length) {
                throw new IllegalStateException("'" + (char) type + "' message at byte " + p + " has length "
                        + length + ", expected " + expected);
            }
            // Every ITCH 5.0 message has the 11-byte header, but a stray short one mustn't read past its end.
            int locate = length >= HEADER_LENGTH ? Short.toUnsignedInt(data.get(SHORT, m + STOCK_LOCATE)) : -1;
            visitor.message(m, type, length, locate);
            count[0]++;
            p = m + length;
        }
        return p;
    }

    /** Decodes an order or system message at {@code offset} and passes it to {@code handler}; other types are ignored. */
    public void deliver(long offset, MessageHandler handler) {
        long m = offset;
        long ts = timestamp(m);
        switch (data.get(ValueLayout.JAVA_BYTE, m)) {
            case SYSTEM_EVENT -> handler.onSystemEvent(ts, byteAt(m + EVENT_CODE));
            case ADD_ORDER, ADD_ORDER_MPID -> handler.onAdd(ts, longAt(m + ORDER_REF),
                    side(m + ADD_SIDE), shares(m + ADD_SHARES), priceAt(m + ADD_PRICE));
            case ORDER_EXECUTED -> handler.onExecute(ts, longAt(m + ORDER_REF),
                    shares(m + EXECUTED_SHARES), longAt(m + EXECUTED_MATCH_NUMBER), -1);
            case ORDER_EXECUTED_WITH_PRICE -> handler.onExecute(ts, longAt(m + ORDER_REF),
                    shares(m + EXECUTED_SHARES), longAt(m + EXECUTED_MATCH_NUMBER), priceAt(m + EXECUTED_PRICE));
            case ORDER_CANCEL -> handler.onCancel(ts, longAt(m + ORDER_REF), shares(m + CANCELLED_SHARES));
            case ORDER_DELETE -> handler.onDelete(ts, longAt(m + ORDER_REF));
            case ORDER_REPLACE -> handler.onReplace(ts, longAt(m + REPLACE_ORIGINAL_REF),
                    longAt(m + REPLACE_NEW_REF), shares(m + REPLACE_SHARES), priceAt(m + REPLACE_PRICE));
            case TRADE -> handler.onHiddenTrade(ts, side(m + TRADE_SIDE), shares(m + TRADE_SHARES),
                    priceAt(m + TRADE_PRICE), longAt(m + TRADE_MATCH_NUMBER));
            default -> { }
        }
    }

    public byte byteAt(long offset) {
        return data.get(ValueLayout.JAVA_BYTE, offset);
    }

    public int unsignedShortAt(long offset) {
        return Short.toUnsignedInt(data.get(SHORT, offset));
    }

    public int intAt(long offset) {
        return data.get(INT, offset);
    }

    public long longAt(long offset) {
        return data.get(LONG, offset);
    }

    /** An unsigned 4-byte price with 4 implied decimals. */
    public long priceAt(long offset) {
        return Integer.toUnsignedLong(data.get(INT, offset));
    }

    /** Six bytes, big-endian: nanoseconds since midnight. */
    public long timestamp(long m) {
        return ((long) unsignedShortAt(m + TIMESTAMP) << 32) | Integer.toUnsignedLong(intAt(m + TIMESTAMP + 2));
    }

    /** An alpha field with its trailing spaces removed, such as a ticker. Allocates; not for the hot path. */
    public String alphaAt(long offset, int width) {
        byte[] bytes = new byte[width];
        MemorySegment.copy(data, ValueLayout.JAVA_BYTE, offset, bytes, 0, width);
        int end = width;
        while (end > 0 && bytes[end - 1] == ' ') end--;
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** The whole message at {@code offset}, including its 2-byte length prefix, as a slice of the mapping. */
    public MemorySegment framed(long offset, int length) {
        return data.asSlice(offset - 2, length + 2L);
    }

    private byte side(long offset) {
        byte b = byteAt(offset);
        if (b == BUY) return Side.BUY;
        if (b == SELL) return Side.SELL;
        throw new IllegalStateException("bad side indicator " + b + " at byte " + offset);
    }

    private int shares(long offset) {
        int shares = intAt(offset);
        if (shares < 0) throw new IllegalStateException("share count above 2^31 at byte " + offset);
        return shares;
    }

    @Override
    public void close() {
        if (arena != null) arena.close();
    }
}
