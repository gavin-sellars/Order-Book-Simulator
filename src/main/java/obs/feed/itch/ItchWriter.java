package obs.feed.itch;

import obs.core.Side;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static obs.feed.itch.ItchLayout.*;

/**
 * Writes Nasdaq TotalView-ITCH 5.0 messages for one stock, framed as in Nasdaq's binary files:
 * each message is preceded by its length as a 2-byte big-endian integer.
 *
 * Messages go into a reusable direct buffer that is flushed to the channel when full, so writing
 * a message allocates nothing. Timestamps are nanoseconds since midnight and must never go
 * backwards. The stock directory entry is marked as test data (authenticity 'T').
 */
public final class ItchWriter implements AutoCloseable {

    private static final int BUFFER_BYTES = 1 << 20;

    private final WritableByteChannel out;
    private final ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_BYTES);    // big-endian, like ITCH
    private final int stockLocate;
    private final byte[] stock;
    private int trackingNumber;
    private long lastTimestamp;
    private long messageCount;

    /**
     * @param stockLocate the locate code this file uses for the stock, 1 to 65535 (0 means market-wide)
     * @param stock       ticker, 1 to 8 ASCII characters
     */
    public ItchWriter(WritableByteChannel out, int stockLocate, String stock) {
        if (stockLocate < 1 || stockLocate > 0xFFFF) throw new IllegalArgumentException("stockLocate must be 1 to 65535");
        this.out = out;
        this.stockLocate = stockLocate;
        this.stock = alpha(stock, 8);
    }

    public static ItchWriter create(Path file, int stockLocate, String stock) throws IOException {
        FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return new ItchWriter(channel, stockLocate, stock);
    }

    // ---------------------------------------------------------------- messages

    /** 'S', market-wide (stock locate 0). */
    public void systemEvent(long timestamp, byte eventCode) {
        int start = begin(SYSTEM_EVENT, SYSTEM_EVENT_LENGTH, 0, timestamp);
        buf.put(eventCode);
        end(start, SYSTEM_EVENT_LENGTH);
    }

    /** 'R', announcing this writer's stock and locate code. Write it before any order messages. */
    public void stockDirectory(long timestamp, int roundLotSize) {
        int start = begin(STOCK_DIRECTORY, STOCK_DIRECTORY_LENGTH, stockLocate, timestamp);
        buf.put(stock);
        buf.put((byte) 'Q');            // market category: Nasdaq Global Select Market
        buf.put((byte) 'N');            // financial status: normal
        buf.putInt(roundLotSize);
        buf.put((byte) 'N');            // round lots only: no
        buf.put((byte) 'C');            // issue classification: common stock
        buf.put((byte) 'Z').put((byte) ' ');   // issue sub-type: not applicable
        buf.put((byte) 'T');            // authenticity: test, because this data is synthetic
        buf.put((byte) 'N');            // short sale threshold: not restricted
        buf.put((byte) ' ');            // IPO flag: not available
        buf.put((byte) '1');            // LULD reference price tier 1
        buf.put((byte) 'N');            // not an exchange-traded product
        buf.putInt(0);                  // ETP leverage factor
        buf.put((byte) 'N');            // not an inverse ETP
        end(start, STOCK_DIRECTORY_LENGTH);
    }

    /** 'A': a new resting order without attribution. */
    public void addOrder(long timestamp, long orderRef, byte side, int shares, long price) {
        checkAdd(side, shares, price);
        int start = begin(ADD_ORDER, ADD_ORDER_LENGTH, stockLocate, timestamp);
        putAddBody(orderRef, side, shares, price);
        end(start, ADD_ORDER_LENGTH);
    }

    /** 'F': a new resting order attributed to a market participant. */
    public void addOrderWithMpid(long timestamp, long orderRef, byte side, int shares, long price, byte[] mpid) {
        if (mpid.length != 4) throw new IllegalArgumentException("MPID must be 4 bytes; use ItchLayout.alpha(text, 4)");
        checkAdd(side, shares, price);
        int start = begin(ADD_ORDER_MPID, ADD_ORDER_MPID_LENGTH, stockLocate, timestamp);
        putAddBody(orderRef, side, shares, price);
        buf.put(mpid);
        end(start, ADD_ORDER_MPID_LENGTH);
    }

    /** 'E': a resting order traded at its own price. */
    public void orderExecuted(long timestamp, long orderRef, int shares, long matchNumber) {
        checkShares(shares);
        int start = begin(ORDER_EXECUTED, ORDER_EXECUTED_LENGTH, stockLocate, timestamp);
        buf.putLong(orderRef).putInt(shares).putLong(matchNumber);
        end(start, ORDER_EXECUTED_LENGTH);
    }

    /** 'X': part of a resting order was cancelled. */
    public void orderCancel(long timestamp, long orderRef, int cancelledShares) {
        checkShares(cancelledShares);
        int start = begin(ORDER_CANCEL, ORDER_CANCEL_LENGTH, stockLocate, timestamp);
        buf.putLong(orderRef).putInt(cancelledShares);
        end(start, ORDER_CANCEL_LENGTH);
    }

    /** 'D': a resting order was removed. */
    public void orderDelete(long timestamp, long orderRef) {
        int start = begin(ORDER_DELETE, ORDER_DELETE_LENGTH, stockLocate, timestamp);
        buf.putLong(orderRef);
        end(start, ORDER_DELETE_LENGTH);
    }

    /** 'U': a resting order was replaced by a new order reference, at the back of the queue. */
    public void orderReplace(long timestamp, long originalRef, long newRef, int shares, long price) {
        checkShares(shares);
        checkPrice(price);
        int start = begin(ORDER_REPLACE, ORDER_REPLACE_LENGTH, stockLocate, timestamp);
        buf.putLong(originalRef).putLong(newRef).putInt(shares).putInt((int) price);
        end(start, ORDER_REPLACE_LENGTH);
    }

    public long messageCount() {
        return messageCount;
    }

    public void flush() {
        buf.flip();
        try {
            while (buf.hasRemaining()) out.write(buf);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        buf.clear();
    }

    /** Flushes and closes the channel. */
    @Override
    public void close() throws IOException {
        flush();
        out.close();
    }

    // ---------------------------------------------------------------- internals
    // Every check runs before begin(), so a rejected message never leaves partial bytes in the buffer.

    private static void checkAdd(byte side, int shares, long price) {
        if (!Side.isValid(side)) throw new IllegalArgumentException("invalid side " + side);
        checkShares(shares);
        checkPrice(price);
    }

    private void putAddBody(long orderRef, byte side, int shares, long price) {
        buf.putLong(orderRef);
        buf.put(side == Side.BUY ? BUY : SELL);
        buf.putInt(shares);
        buf.put(stock);
        buf.putInt((int) price);
    }

    /** Writes the length prefix and header. Returns the position of the type byte. */
    private int begin(byte type, int length, int locate, long timestamp) {
        if (timestamp < 0 || timestamp > MAX_TIMESTAMP) throw new IllegalArgumentException("timestamp out of range: " + timestamp);
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("timestamp " + timestamp + " is before the previous message's " + lastTimestamp);
        }
        if (buf.remaining() < 2 + length) flush();

        trackingNumber = (trackingNumber + 1) & 0xFFFF;
        buf.putShort((short) length);
        int start = buf.position();
        buf.put(type);
        buf.putShort((short) locate);
        buf.putShort((short) trackingNumber);
        buf.putShort((short) (timestamp >>> 32));
        buf.putInt((int) timestamp);
        lastTimestamp = timestamp;
        return start;
    }

    private void end(int start, int length) {
        if (buf.position() - start != length) {
            throw new IllegalStateException("wrote " + (buf.position() - start) + " bytes for a " + length + "-byte message");
        }
        messageCount++;
    }

    private static void checkShares(int shares) {
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive: " + shares);
    }

    private static void checkPrice(long price) {
        if (price < 0 || price > MAX_PRICE) throw new IllegalArgumentException("price out of ITCH range: " + price);
    }
}
