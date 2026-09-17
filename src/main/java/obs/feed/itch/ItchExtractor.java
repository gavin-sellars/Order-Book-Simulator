package obs.feed.itch;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static obs.feed.itch.ItchLayout.*;

/**
 * Cuts one file per stock out of a whole ITCH day, in a single pass.
 *
 * Each output holds, in the original order and byte for byte: every system event, the stock's
 * directory entry, and its order and trade messages (the types {@link ItchReader} delivers). It is
 * a valid ITCH file that {@link ItchReader} reads exactly as it would the full day for that ticker,
 * because the reader finds the stock by ticker through the directory entry and skips everything
 * else anyway. A feed handler does the same filtering by locate code before the book sees anything.
 *
 * Outputs over 2 GB are refused, since {@link ItchReader} can't map them.
 */
public final class ItchExtractor {

    private ItchExtractor() {}

    public record Extract(String ticker, Path file, long messages, long bytes) {}

    /** Writes {@code <outDir>/<TICKER>.itch} for each ticker. Tickers missing from the directory produce no file. */
    public static List<Extract> extract(ItchFile itch, List<String> tickers, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Map<String, Output> byTicker = new HashMap<>();
        for (String t : tickers) byTicker.put(t, null);
        Output[] byLocate = new Output[1 << 16];
        List<Output> all = new ArrayList<>();
        List<MemorySegment> systemEvents = new ArrayList<>();       // a handful per day

        try {
            itch.forEach((m, type, length, locate) -> {
                try {
                    if (type == SYSTEM_EVENT) {
                        systemEvents.add(itch.framed(m, length));
                        for (Output o : all) o.write(itch.framed(m, length));
                    } else if (type == STOCK_DIRECTORY) {
                        String ticker = itch.alphaAt(m + DIRECTORY_STOCK, 8);
                        if (byTicker.containsKey(ticker) && byTicker.get(ticker) == null) {
                            Output o = new Output(ticker, outDir.resolve(ticker + ".itch"));
                            byTicker.put(ticker, o);
                            byLocate[locate] = o;
                            all.add(o);
                            for (MemorySegment event : systemEvents) o.write(event);   // those before the directory entry
                            o.write(itch.framed(m, length));
                        }
                    } else if (lengthOf(type) >= 0 && byLocate[locate] != null) {
                        byLocate[locate].write(itch.framed(m, length));
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        } finally {
            for (Output o : all) o.close();
        }

        List<Extract> result = new ArrayList<>();
        for (Output o : all) result.add(new Extract(o.ticker, o.file, o.messages, o.bytes));
        return result;
    }

    private static final class Output implements AutoCloseable {
        final String ticker;
        final Path file;
        final FileChannel channel;
        final ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20);
        final MemorySegment bufferSegment = MemorySegment.ofBuffer(buffer);
        long messages;
        long bytes;

        Output(String ticker, Path file) throws IOException {
            this.ticker = ticker;
            this.file = file;
            this.channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }

        void write(MemorySegment framed) throws IOException {
            int n = (int) framed.byteSize();
            if (bytes + n > Integer.MAX_VALUE) {
                throw new IOException(file + " would exceed 2 GB, which ItchReader can't map");
            }
            if (buffer.remaining() < n) flush();
            MemorySegment.copy(framed, 0, bufferSegment, buffer.position(), n);
            buffer.position(buffer.position() + n);
            messages++;
            bytes += n;
        }

        void flush() throws IOException {
            buffer.flip();
            while (buffer.hasRemaining()) channel.write(buffer);
            buffer.clear();
        }

        @Override
        public void close() throws IOException {
            try {
                flush();
            } finally {
                channel.close();
            }
        }
    }
}
