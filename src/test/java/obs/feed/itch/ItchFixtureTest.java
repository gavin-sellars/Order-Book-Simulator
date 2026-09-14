package obs.feed.itch;

import obs.core.Prices;
import obs.core.Side;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Byte-exact fixtures for every message the writer produces, written out by hand from the
 * TotalView-ITCH 5.0 specification rather than from ItchLayout, so a wrong offset or length in
 * the shared constants fails here even though the writer and reader would agree with each other.
 *
 * Every fixture: 2-byte length prefix, then type, stock locate 1 (0 for system events), tracking
 * number 1, timestamp 0x010203040506.
 */
class ItchFixtureTest {

    static final String TS = "01 02 03 04 05 06";
    static final String REF = "00 00 00 00 00 00 30 39";           // 12345
    static final String SYNTH = "53 59 4E 54 48 20 20 20";         // "SYNTH   "
    static final String PX_150_01 = "00 16 E3 C4";                 // 1,500,100
    static final long T = 0x010203040506L;

    /** Parses hex byte pairs, ignoring spaces and '|' separators. */
    static byte[] hex(String text) {
        String digits = text.replaceAll("[\\s|]", "");
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    static byte[] written(Consumer<ItchWriter> writes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ItchWriter writer = new ItchWriter(Channels.newChannel(bytes), 1, "SYNTH")) {
            writes.accept(writer);
        }
        return bytes.toByteArray();
    }

    @Test
    void systemEvent() throws IOException {
        assertArrayEquals(hex("00 0C | 53 | 00 00 | 00 01 | " + TS + " | 51"),
                written(w -> w.systemEvent(T, ItchLayout.START_OF_MARKET_HOURS)));
    }

    @Test
    void stockDirectory() throws IOException {
        assertArrayEquals(hex("00 27 | 52 | 00 01 | 00 01 | " + TS + " | " + SYNTH
                        + " | 51 | 4E | 00 00 00 64 | 4E | 43 | 5A 20 | 54 | 4E | 20 | 31 | 4E | 00 00 00 00 | 4E"),
                written(w -> w.stockDirectory(T, 100)));
    }

    @Test
    void addOrder() throws IOException {
        assertArrayEquals(hex("00 24 | 41 | 00 01 | 00 01 | " + TS + " | " + REF + " | 42 | 00 00 00 64 | "
                        + SYNTH + " | " + PX_150_01),
                written(w -> w.addOrder(T, 12345, Side.BUY, 100, Prices.parse("150.01"))));
    }

    @Test
    void addOrderWithMpid() throws IOException {
        assertArrayEquals(hex("00 28 | 46 | 00 01 | 00 01 | " + TS + " | " + REF + " | 53 | 00 00 01 F4 | "
                        + SYNTH + " | " + PX_150_01 + " | 53 49 4D 58"),
                written(w -> w.addOrderWithMpid(T, 12345, Side.SELL, 500, Prices.parse("150.01"),
                        ItchLayout.alpha("SIMX", 4))));
    }

    @Test
    void orderExecuted() throws IOException {
        assertArrayEquals(hex("00 1F | 45 | 00 01 | 00 01 | " + TS + " | " + REF + " | 00 00 00 64 | 00 00 00 00 00 00 00 07"),
                written(w -> w.orderExecuted(T, 12345, 100, 7)));
    }

    @Test
    void orderCancel() throws IOException {
        assertArrayEquals(hex("00 17 | 58 | 00 01 | 00 01 | " + TS + " | " + REF + " | 00 00 00 32"),
                written(w -> w.orderCancel(T, 12345, 50)));
    }

    @Test
    void orderDelete() throws IOException {
        assertArrayEquals(hex("00 13 | 44 | 00 01 | 00 01 | " + TS + " | " + REF),
                written(w -> w.orderDelete(T, 12345)));
    }

    @Test
    void orderReplace() throws IOException {
        assertArrayEquals(hex("00 23 | 55 | 00 01 | 00 01 | " + TS + " | " + REF + " | 00 00 00 00 00 00 30 3A | 00 00 00 C8 | 00 16 E3 60"),
                written(w -> w.orderReplace(T, 12345, 12346, 200, Prices.parse("150.00"))));
    }

    @Test
    void trackingNumberCountsMessagesAndEachMessageHasItsOwnPrefix() throws IOException {
        byte[] bytes = written(w -> {
            w.orderDelete(T, 1);
            w.orderDelete(T, 2);
        });

        assertEquals(2 * 21, bytes.length);
        assertEquals(0x00, bytes[21 + 5]);
        assertEquals(0x02, bytes[21 + 6], "second message has tracking number 2");
    }

    @Test
    void rejectsValuesItchCannotRepresent() {
        assertThrows(IllegalArgumentException.class, () -> written(w -> w.addOrder(T, 1, (byte) 9, 100, 100)));
        assertThrows(IllegalArgumentException.class, () -> written(w -> w.addOrder(T, 1, Side.BUY, 0, 100)));
        assertThrows(IllegalArgumentException.class, () -> written(w -> w.addOrder(T, 1, Side.BUY, 100, ItchLayout.MAX_PRICE + 1)));
        assertThrows(IllegalArgumentException.class, () -> written(w -> w.orderDelete(ItchLayout.MAX_TIMESTAMP + 1, 1)));
        assertThrows(IllegalStateException.class, () -> written(w -> {
            w.orderDelete(T, 1);
            w.orderDelete(T - 1, 2);
        }));
        assertThrows(IllegalArgumentException.class, () -> new ItchWriter(Channels.newChannel(new ByteArrayOutputStream()), 1, "TOOLONGNAME"));
        assertThrows(IllegalArgumentException.class, () -> new ItchWriter(Channels.newChannel(new ByteArrayOutputStream()), 0, "SYNTH"));
    }
}
