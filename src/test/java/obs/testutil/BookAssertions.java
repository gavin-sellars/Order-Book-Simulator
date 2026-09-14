package obs.testutil;

import obs.core.Book;
import obs.core.Side;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class BookAssertions {

    private BookAssertions() {}

    /** Asserts both books have the same touch, counts and full depth on both sides, level by level. */
    public static void assertSameBook(Book expected, Book actual, int maxLevels, String context) {
        assertEquals(expected.bestBid(), actual.bestBid(), context + ": best bid");
        assertEquals(expected.bestAsk(), actual.bestAsk(), context + ": best ask");
        assertEquals(expected.isCrossed(), actual.isCrossed(), context + ": crossed");
        assertEquals(expected.orderCount(), actual.orderCount(), context + ": order count");

        for (byte side = Side.BUY; side <= Side.SELL; side++) {
            String label = context + ": " + Side.name(side) + " ";
            assertEquals(expected.levelCount(side), actual.levelCount(side), label + "level count");

            long[] ePrices = new long[maxLevels], eQtys = new long[maxLevels];
            long[] aPrices = new long[maxLevels], aQtys = new long[maxLevels];
            int[] eCounts = new int[maxLevels], aCounts = new int[maxLevels];
            int en = expected.depth(side, maxLevels, ePrices, eQtys, eCounts);
            int an = actual.depth(side, maxLevels, aPrices, aQtys, aCounts);

            assertEquals(en, an, label + "depth levels");
            assertArrayEquals(Arrays.copyOf(ePrices, en), Arrays.copyOf(aPrices, an), label + "prices");
            assertArrayEquals(Arrays.copyOf(eQtys, en), Arrays.copyOf(aQtys, an), label + "quantities");
            assertArrayEquals(Arrays.copyOf(eCounts, en), Arrays.copyOf(aCounts, an), label + "order counts");
        }
    }
}
