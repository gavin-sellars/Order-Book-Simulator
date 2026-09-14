package obs.core;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PricesTest {

    @Test
    void parsesToFourImpliedDecimals() {
        assertEquals(1_500_100L, Prices.parse("150.01"));
        assertEquals(1_500_000L, Prices.parse("150"));
        assertEquals(1_500_000L, Prices.parse("150."));
        assertEquals(1_500_125L, Prices.parse("150.0125"));
        assertEquals(5_000L, Prices.parse("0.5"));
        assertEquals(1L, Prices.parse("0.0001"));
        assertEquals(-100L, Prices.parse("-0.01"));
    }

    @Test
    void formatsWithTwoToFourDecimals() {
        assertEquals("150.01", Prices.format(1_500_100L));
        assertEquals("150.00", Prices.format(1_500_000L));
        assertEquals("150.0125", Prices.format(1_500_125L));
        assertEquals("150.001", Prices.format(1_500_010L));
        assertEquals("0.00", Prices.format(0L));
        assertEquals("-0.01", Prices.format(-100L));
    }

    @Test
    void rejectsMalformedPrices() {
        for (String bad : new String[] {"", " ", "-", "abc", ".5", "1.23456", "1.2.3", "+1", "1e3", "--1"}) {
            assertThrows(IllegalArgumentException.class, () -> Prices.parse(bad), bad);
        }
    }

    @Property
    void formatThenParseRoundTrips(@ForAll @LongRange(min = -1_000_000_000_000_000L, max = 1_000_000_000_000_000L) long price) {
        assertEquals(price, Prices.parse(Prices.format(price)));
    }
}
