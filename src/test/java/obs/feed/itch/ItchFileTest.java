package obs.feed.itch;

import obs.core.Prices;
import obs.core.Side;
import obs.feed.StockProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItchFileTest {

    /**
     * Two stocks interleaved message by message in one stream, as in a real day: AAA on locate 1
     * and BBB on locate 2, sharing the system events.
     */
    private static byte[] twoStockDay() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel channel = Channels.newChannel(bytes);
        ItchWriter a = new ItchWriter(channel, 1, "AAA");
        ItchWriter b = new ItchWriter(channel, 2, "BBB");
        a.systemEvent(1, ItchLayout.START_OF_MESSAGES);
        a.flush();
        a.stockDirectory(2, 100);
        a.flush();
        b.stockDirectory(2, 100);
        b.flush();
        a.addOrder(3, 10, Side.BUY, 100, Prices.parse("10.00"));
        a.flush();
        b.addOrder(4, 11, Side.SELL, 300, Prices.parse("20.0050"));   // sub-penny
        b.flush();
        a.addOrderWithMpid(5, 12, Side.SELL, 200, Prices.parse("10.05"), ItchLayout.alpha("SIMX", 4));
        a.flush();
        b.orderExecuted(6, 11, 300, 1);
        b.flush();
        a.orderExecuted(7, 12, 50, 2);
        a.flush();
        a.orderReplace(8, 10, 13, 70, Prices.parse("9.90"));
        a.flush();
        a.orderCancel(9, 13, 20);
        a.flush();
        a.systemEvent(10, ItchLayout.START_OF_MARKET_HOURS);
        a.flush();
        a.orderDelete(11, 12);
        a.flush();
        a.systemEvent(12, ItchLayout.END_OF_MESSAGES);
        a.close();
        b.close();
        return bytes.toByteArray();
    }

    private static List<String> readWithItchReader(byte[] bytes, String ticker) {
        ItchReaderTest.Recorder recorder = new ItchReaderTest.Recorder();
        new ItchReader(ByteBuffer.wrap(bytes)).replay(ticker, recorder);
        return recorder.events;
    }

    @Test
    void walksEveryStockAndDecodesLikeTheReader() throws IOException {
        byte[] day = twoStockDay();
        List<String> types = new ArrayList<>();
        ItchReaderTest.Recorder aaa = new ItchReaderTest.Recorder();
        long count;
        try (ItchFile itch = ItchFile.of(day)) {
            assertEquals(day.length, itch.size());
            count = itch.forEach((m, type, length, locate) -> {
                types.add((char) type + "" + locate);
                if (type == ItchLayout.SYSTEM_EVENT || (locate == 1 && type != ItchLayout.STOCK_DIRECTORY)) {
                    itch.deliver(m, aaa);
                }
            });
        }
        assertEquals(List.of("S0", "R1", "R2", "A1", "A2", "F1", "E2", "E1", "U1", "X1", "S0", "D1", "S0"), types);
        assertEquals(13, count);
        assertEquals(readWithItchReader(day, "AAA"), aaa.events);
    }

    @Test
    void rejectsATruncatedDay() throws IOException {
        byte[] day = twoStockDay();
        try (ItchFile itch = ItchFile.of(Arrays.copyOf(day, day.length - 3))) {
            assertThrows(IllegalStateException.class, () -> itch.forEach((m, type, length, locate) -> { }));
        }
    }

    @Test
    void extractsEachStockIntoAFileTheReaderSeesIdentically(@TempDir Path dir) throws IOException {
        byte[] day = twoStockDay();
        List<ItchExtractor.Extract> extracts;
        try (ItchFile itch = ItchFile.of(day)) {
            extracts = ItchExtractor.extract(itch, List.of("AAA", "BBB", "ZZZ"), dir);
        }

        assertEquals(List.of("AAA", "BBB"), extracts.stream().map(ItchExtractor.Extract::ticker).toList());
        assertFalse(Files.exists(dir.resolve("ZZZ.itch")), "a ticker missing from the directory gets no file");
        for (ItchExtractor.Extract e : extracts) {
            byte[] extracted = Files.readAllBytes(e.file());
            assertEquals(e.bytes(), extracted.length);
            assertEquals(readWithItchReader(day, e.ticker()), readWithItchReader(extracted, e.ticker()));
        }
        // AAA: 3 system events, its directory entry and 6 order messages. BBB: 3, 1 and 2.
        assertEquals(10, extracts.get(0).messages());
        assertEquals(6, extracts.get(1).messages());
        assertTrue(readWithItchReader(Files.readAllBytes(dir.resolve("AAA.itch")), "BBB").stream()
                .allMatch(event -> event.startsWith("system")), "the AAA file holds nothing of BBB's");
    }

    @Test
    void profileSizesTheBookFromTheData() throws IOException {
        byte[] day = twoStockDay();

        StockProfile aaa = StockProfile.measure(new ItchReader(ByteBuffer.wrap(day)), "AAA");
        assertEquals(Prices.parse("9.90"), aaa.minPrice());
        assertEquals(Prices.parse("10.05"), aaa.maxPrice());
        assertEquals(Prices.parse("10.05"), aaa.minTraded());
        assertEquals(Prices.parse("10.05"), aaa.maxTraded());
        assertEquals(StockProfile.CENT, aaa.ladderTick());
        assertEquals(Prices.parse("8.04"), aaa.basePrice(), "20% below the lowest trade");
        assertEquals(Prices.parse("12.57"), aaa.maxLadderPrice(), "25% above the highest trade, rounded up to a cent");
        assertEquals(454, aaa.ladderLevels());
        assertEquals(2, aaa.maxLiveOrders());
        assertEquals(8, aaa.poolCapacity());
        assertEquals(0, aaa.maxFarLevels());
        assertEquals(6, aaa.orderMessages());
        assertEquals(0, aaa.farOrderMessages());

        StockProfile bbb = StockProfile.measure(new ItchReader(ByteBuffer.wrap(day)), "BBB");
        assertEquals(StockProfile.CENT, bbb.ladderTick(), "a stock over $1 keeps a cent ladder");
        assertEquals(Prices.parse("16.00"), bbb.basePrice());
        assertEquals(1, bbb.maxFarLevels(), "the sub-penny order sits on a far level");
        assertEquals(2, bbb.farOrderMessages());
        var bbbBook = bbb.newBook(obs.core.TradeListener.NONE);
        var bbbBuilder = new obs.feed.BookBuilder(bbbBook);
        new ItchReader(ByteBuffer.wrap(day)).replay("BBB", bbbBuilder);
        assertEquals(0, bbbBuilder.rejectedMessages());
        assertEquals(0, bbbBook.orderCount());

        var book = aaa.newBook(obs.core.TradeListener.NONE);
        var builder = new obs.feed.BookBuilder(book);
        new ItchReader(ByteBuffer.wrap(day)).replay("AAA", builder);
        assertEquals(0, builder.rejectedMessages());
        assertEquals(1, book.orderCount());
        assertEquals(Prices.parse("9.90"), book.bestBid());
        assertEquals(50, book.restingQty(13));
    }
}
