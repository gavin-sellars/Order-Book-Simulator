package obs.core;

/**
 * The one price convention used everywhere: a long with 4 implied decimal places, the same as
 * Nasdaq ITCH. $150.01 is 1_500_100. Never use double for prices.
 *
 * parse and format allocate, so they belong at the edges of the system (demos, tests, CLIs),
 * never in the book.
 */
public final class Prices {

    public static final long SCALE = 10_000L;

    /** One US cent: the tick size for stocks priced at $1.00 and above. */
    public static final long CENT = 100L;

    private Prices() {}

    /** Parses "150.01", "150" or "-0.5" into implied-decimal form. At most 4 decimal places. */
    public static long parse(String text) {
        String t = text.trim();
        boolean negative = t.startsWith("-");
        if (negative) t = t.substring(1);

        int dot = t.indexOf('.');
        String whole = dot < 0 ? t : t.substring(0, dot);
        String frac = dot < 0 ? "" : t.substring(dot + 1);
        if (whole.isEmpty() || frac.length() > 4 || !isDigits(whole) || !isDigits(frac)) {
            throw new IllegalArgumentException("bad price: \"" + text + "\"");
        }

        long fracUnits = frac.isEmpty() ? 0 : Long.parseLong((frac + "000").substring(0, 4));
        long value = Math.addExact(Math.multiplyExact(Long.parseLong(whole), SCALE), fracUnits);
        return negative ? -value : value;
    }

    /** Formats 1_500_100 as "150.01" and 1_500_125 as "150.0125": at least 2 decimals, at most 4. */
    public static String format(long price) {
        if (price == Long.MIN_VALUE) throw new IllegalArgumentException("cannot format Long.MIN_VALUE");

        long abs = Math.abs(price);
        String frac = String.format("%04d", abs % SCALE);
        int end = 4;
        while (end > 2 && frac.charAt(end - 1) == '0') end--;

        StringBuilder sb = new StringBuilder();
        if (price < 0) sb.append('-');
        return sb.append(abs / SCALE).append('.').append(frac, 0, end).toString();
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
