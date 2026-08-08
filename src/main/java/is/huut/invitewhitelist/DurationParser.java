package is.huut.invitewhitelist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses short duration strings like "7d", "12h", "30m", "45s", or a
 * combination like "1d12h". "permanent"/"never"/"0" mean "does not expire".
 */
public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)([dhms])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {
    }

    /**
     * @return milliseconds until expiry, or -1 if the invite should never expire.
     * @throws IllegalArgumentException if the string can't be parsed.
     */
    public static long parseToMillis(String input) {
        String normalized = input.trim().toLowerCase();
        if (normalized.equals("permanent") || normalized.equals("never") || normalized.equals("0")) {
            return -1;
        }

        Matcher matcher = PART.matcher(normalized);
        long totalMillis = 0;
        boolean matchedAny = false;
        int consumed = 0;

        while (matcher.find()) {
            matchedAny = true;
            consumed += matcher.group().length();
            long value = Long.parseLong(matcher.group(1));
            char unit = matcher.group(2).charAt(0);
            totalMillis += switch (unit) {
                case 'd' -> value * 24L * 60 * 60 * 1000;
                case 'h' -> value * 60L * 60 * 1000;
                case 'm' -> value * 60L * 1000;
                case 's' -> value * 1000L;
                default -> 0L;
            };
        }

        if (!matchedAny || consumed != normalized.length()) {
            throw new IllegalArgumentException(
                    "Invalid duration '" + input + "' - expected something like 7d, 12h, 30m, 1d12h, or 'permanent'");
        }

        return totalMillis;
    }
}
