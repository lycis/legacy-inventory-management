package legacy.support;

import java.text.DecimalFormat;
import java.util.Locale;

/**
 * Third-party-feeling helper that produces odd currency strings and noisy status badges.
 */
public class LegacyValueFormatter {
    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(() -> {
        DecimalFormat format = (DecimalFormat) DecimalFormat.getCurrencyInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(4);
        return format;
    });

    public static String moneyBadge(double value) {
        DecimalFormat format = MONEY.get();
        String base = format.format(value);
        return base + " (approx)";
    }

    public static String shoutCommand(String lastCommand) {
        if (lastCommand == null || lastCommand.trim().isEmpty()) {
            return "<silence>";
        }
        return "{" + lastCommand.trim().toUpperCase(Locale.US) + "!}";
    }

    public static String jitterLabel(String text) {
        if (text == null) {
            text = "UNKNOWN";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            trimmed = "BLANK";
        }
        return "~" + trimmed.replace(' ', '_') + "~";
    }
}
