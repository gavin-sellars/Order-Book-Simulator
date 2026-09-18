package obs.feed.itch;

import obs.core.Prices;
import obs.core.Side;
import obs.feed.MessageHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.List;

import static obs.feed.itch.ItchFixtureTest.PX_150_01;
import static obs.feed.itch.ItchFixtureTest.REF;
import static obs.feed.itch.ItchFixtureTest.SYNTH;
import static obs.feed.itch.ItchFixtureTest.T;
import static obs.feed.itch.ItchFixtureTest.TS;
import static obs.feed.itch.ItchFixtureTest.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItchReaderTest {

    /** A stock directory entry for "SYNTH" with the given 2-byte locate code, written by hand. */
    private static String directory(String locate) {
        return "00 27 | 52 | " + locate + " | 00 01 | " + TS + " | " + SYNTH
                + " | 51 | 4E | 00 00 00 64 | 4E | 43 | 5A 20 | 54 | 4E | 20 | 31 | 4E | 00 00 00 00 | 4E | ";
    }

    private static List<String> read(byte[] bytes) {
        Recorder recorder = new Recorder();
        new ItchReader(ByteBuffer.wrap(bytes)).replay("SYNTH", recorder);
        return recorder.events;
    }

    @Test
    void decodesExecutedWithPriceAndHiddenTradeFromSpecBytes() {
        byte[] bytes = hex(directory("00 01")
                + "00 24 | 43 | 00 01 | 00 02 | " + TS + " | " + REF + " | 00 00 00 64 | 00 00 00 00 00 00 00 08 | 59 | " + PX_150_01 + " | "
                + "00 2C | 50 | 00 01 | 00 03 | " + TS + " | 00 00 00 00 00 00 00 00 | 42 | 00 00 00 64 | " + SYNTH + " | "
                + PX_150_01 + " | 00 00 00 00 00 00 00 09");

        assertEquals(List.of(
                "execute " + T + " 12345 100 match=8 px=1500100",
                "hidden " + T + " BUY 100 px=1500100 match=9"), read(bytes));
    }

    @Test
    void deliversOnlyTheRequestedStockPlusSystemEvents() {
        String addForLocate2 = "00 24 | 41 | 00 02 | 00 01 | " + TS + " | " + REF + " | 42 | 00 00 00 64 | "
                + "4F 54 48 45 52 20 20 20 | " + PX_150_01 + " | ";
        String addForLocate1 = "00 24 | 41 | 00 01 | 00 01 | " + TS + " | " + REF + " | 53 | 00 00 00 64 | "
                + SYNTH + " | " + PX_150_01 + " | ";

        byte[] bytes = hex(addForLocate1                                  // before the directory entry: skipped
                + "00 0C | 53 | 00 00 | 00 01 | " + TS + " | 51 | "      // system event: always delivered
                + directory("00 01")
                + addForLocate2                                          // another stock: skipped
                + "00 0C | 5A | 00 01 | 00 01 | " + TS + " | 00 | "      // unknown type 'Z': skipped
                + addForLocate1);

        assertEquals(List.of("system " + T + " Q", "add " + T + " 12345 SELL 100 px=1500100"), read(bytes));
    }

    @Test
    void rejectsTruncatedStreamsAndWrongLengths() {
        byte[] good = hex("00 13 | 44 | 00 01 | 00 01 | " + TS + " | " + REF);
        byte[] cut = java.util.Arrays.copyOf(good, good.length - 1);
        byte[] danglingPrefix = hex("00 13 | 44 | 00 01 | 00 01 | " + TS + " | " + REF + " | 00");
        byte[] deleteWithWrongLength = hex("00 14 | 44 | 00 01 | 00 01 | " + TS + " | " + REF + " | 00");

        assertThrows(IllegalStateException.class, () -> read(cut));
        assertThrows(IllegalStateException.class, () -> read(danglingPrefix));
        assertThrows(IllegalStateException.class, () -> read(deleteWithWrongLength));
    }

    @Test
    void readsBackEverythingTheWriterWrites() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ItchWriter w = new ItchWriter(Channels.newChannel(bytes), 7, "SYNTH")) {
            w.systemEvent(1, ItchLayout.START_OF_MESSAGES);
            w.stockDirectory(2, 100);
            w.addOrder(3, 1, Side.BUY, 100, Prices.parse("150.00"));
            w.addOrderWithMpid(4, 2, Side.SELL, 200, Prices.parse("150.01"), ItchLayout.alpha("SIMX", 4));
            w.orderExecuted(5, 2, 50, 1);
            w.orderCancel(6, 1, 30);
            w.orderReplace(7, 1, 3, 70, Prices.parse("149.99"));
            w.orderDelete(8, 3);
            w.systemEvent(ItchLayout.MAX_TIMESTAMP, ItchLayout.END_OF_MESSAGES);
        }

        assertEquals(List.of(
                "system 1 O",
                "add 3 1 BUY 100 px=1500000",
                "add 4 2 SELL 200 px=1500100",
                "execute 5 2 50 match=1 px=-1",
                "cancel 6 1 30",
                "replace 7 1 3 70 px=1499900",
                "delete 8 3",
                "system " + ItchLayout.MAX_TIMESTAMP + " C"), read(bytes.toByteArray()));
    }

    static final class Recorder implements MessageHandler {
        final List<String> events = new ArrayList<>();

        @Override
        public void onSystemEvent(long ts, byte code) {
            events.add("system " + ts + " " + (char) code);
        }

        @Override
        public void onAdd(long ts, long ref, byte side, int shares, long price) {
            events.add("add " + ts + " " + ref + " " + Side.name(side) + " " + shares + " px=" + price);
        }

        @Override
        public void onExecute(long ts, long ref, int shares, long match, long price) {
            events.add("execute " + ts + " " + ref + " " + shares + " match=" + match + " px=" + price);
        }

        @Override
        public void onCancel(long ts, long ref, int shares) {
            events.add("cancel " + ts + " " + ref + " " + shares);
        }

        @Override
        public void onDelete(long ts, long ref) {
            events.add("delete " + ts + " " + ref);
        }

        @Override
        public void onReplace(long ts, long oldRef, long newRef, int shares, long price) {
            events.add("replace " + ts + " " + oldRef + " " + newRef + " " + shares + " px=" + price);
        }

        @Override
        public void onHiddenTrade(long ts, byte side, int shares, long price, long match) {
            events.add("hidden " + ts + " " + Side.name(side) + " " + shares + " px=" + price + " match=" + match);
        }
    }
}
