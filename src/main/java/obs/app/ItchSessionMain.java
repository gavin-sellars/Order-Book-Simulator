package obs.app;

import obs.core.BookValidator;
import obs.core.OrderBook;
import obs.core.Prices;
import obs.feed.BookBuilder;
import obs.feed.FlowConfig;
import obs.feed.SyntheticItchGenerator;
import obs.feed.itch.ItchReader;
import obs.feed.itch.ItchWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates a synthetic ITCH session to a file, replays the file into the fast book, and checks
 * that the replayed book ends exactly where the generator's reference book ended.
 * Run with {@code gradlew itchSession -PitchArgs="[seed] [minutes] [file]"}.
 *
 * Defaults: seed 20260914, a full 390-minute session (09:30 to 16:00), build/session.itch.
 */
public final class ItchSessionMain {

    public static void main(String[] args) throws IOException {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20260914L;
        long minutes = args.length > 1 ? Long.parseLong(args[1]) : 390;
        Path file = Path.of(args.length > 2 ? args[2] : "build/session.itch");

        FlowConfig config = FlowConfig.defaults(seed).withDuration(minutes * 60 * FlowConfig.NANOS_PER_SECOND);
        Files.createDirectories(file.toAbsolutePath().getParent());

        long start = System.nanoTime();
        SyntheticItchGenerator.Summary summary;
        try (ItchWriter writer = ItchWriter.create(file, config.stockLocate(), config.ticker())) {
            summary = SyntheticItchGenerator.generate(config, writer, SyntheticItchGenerator.Observer.NONE);
        }
        double generateSeconds = (System.nanoTime() - start) / 1e9;
        long bytes = Files.size(file);

        System.out.printf("Generated %s: seed %d, %d minutes, %,d bytes%n", file, seed, minutes, bytes);
        System.out.printf("  %,d events -> %,d messages (%,d add, %,d execute, %,d cancel, %,d delete, %,d replace, %d session)%n",
                summary.events(), summary.totalMessages(), summary.addMessages(), summary.executionMessages(),
                summary.cancelMessages(), summary.deleteMessages(), summary.replaceMessages(), summary.sessionMessages());
        System.out.printf("  %,d shares traded, cancel-to-trade %.1f : 1, closing touch %s / %s with %,d resting orders%n",
                summary.sharesTraded(), summary.cancelToTradeRatio(), Prices.format(summary.bestBid()),
                Prices.format(summary.bestAsk()), summary.restingOrders());
        System.out.printf("  generation (reference book + ITCH writing): %.2f s, %,.0f messages/sec%n",
                generateSeconds, summary.totalMessages() / generateSeconds);

        ItchReader reader = ItchReader.open(file);
        OrderBook book = new OrderBook(config.minPrice(), config.tickSize(), config.ladderLevels(), 1 << 20,
                (aggressorId, restingId, price, qty, side) -> { });
        BookBuilder builder = new BookBuilder(book);

        start = System.nanoTime();
        long delivered = reader.replay(config.ticker(), builder);
        double replaySeconds = (System.nanoTime() - start) / 1e9;

        System.out.printf("Replayed into the fast book: %,d messages in %.3f s, %,.0f messages/sec (%.1f ns/message)%n",
                delivered, replaySeconds, delivered / replaySeconds, replaySeconds * 1e9 / delivered);

        BookValidator.validate(book, false);
        boolean matches = builder.rejectedMessages() == 0
                && book.bestBid() == summary.bestBid()
                && book.bestAsk() == summary.bestAsk()
                && book.orderCount() == summary.restingOrders()
                && builder.orderMessages() == summary.orderMessages();
        System.out.printf("  %,d rejected messages; closing touch %s / %s with %,d resting orders; structure valid%n",
                builder.rejectedMessages(), Prices.format(book.bestBid()), Prices.format(book.bestAsk()), book.orderCount());
        System.out.println(matches ? "MATCH: replayed book ends exactly where the generator's book ended"
                                   : "MISMATCH: replayed book differs from the generator's book");
        if (!matches) System.exit(1);
    }
}
