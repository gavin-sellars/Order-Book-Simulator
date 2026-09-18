package obs.app;

import obs.feed.itch.ItchExtractor;
import obs.feed.itch.ItchFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Cuts per-stock ITCH files out of a whole day, in one pass. Run with
 * {@code gradlew itchExtract -PextractArgs="<day file> <out dir> TICKER [TICKER...]"}.
 */
public final class ItchExtractMain {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("usage: ItchExtractMain <day file> <out dir> TICKER...");
        Path file = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        List<String> tickers = Arrays.asList(args).subList(2, args.length);

        long start = System.nanoTime();
        List<ItchExtractor.Extract> extracts;
        try (ItchFile itch = ItchFile.open(file)) {
            extracts = ItchExtractor.extract(itch, tickers, outDir);
        }
        System.out.printf("Read %s in %.1f s%n", file, (System.nanoTime() - start) / 1e9);
        for (ItchExtractor.Extract e : extracts) {
            System.out.printf("  %-8s %,13d messages %,15d bytes  %s%n", e.ticker(), e.messages(), e.bytes(), e.file());
        }
        if (extracts.size() < tickers.size()) {
            System.out.printf("Not in the stock directory: %s%n",
                    tickers.stream().filter(t -> extracts.stream().noneMatch(e -> e.ticker().equals(t))).toList());
        }
    }
}
