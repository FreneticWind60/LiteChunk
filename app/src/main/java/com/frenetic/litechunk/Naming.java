package com.frenetic.litechunk;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How a finished archive is named.
 * <p>
 * The name is built from an ordered, partially enabled list of tokens instead of a free-form pattern. A pattern editor
 * looks friendlier until someone types a slash or leaves it blank, and then the file cannot be created at all;
 * with tokens the only decisions are order, inclusion and the separator, and every result is a legal file name.
 * <p>
 * Order and inclusion are stored separately so a token that is switched off keeps its place and comes back where the
 * user left it.
 */
final class Naming {

    static final String TOKEN_NAME = "name";
    static final String TOKEN_EDITION = "edition";
    static final String TOKEN_TIME = "time";

    /** Every token, in the order used before the user rearranges anything. */
    static final String[] TOKENS = {TOKEN_NAME, TOKEN_EDITION, TOKEN_TIME};

    static final String DEFAULT_ORDER = TOKEN_NAME + "," + TOKEN_EDITION + "," + TOKEN_TIME;
    static final String DEFAULT_ENABLED = DEFAULT_ORDER;
    static final String DEFAULT_SEPARATOR = "-";

    /** Separators offered by the sheet. The empty one joins the parts with nothing at all. */
    static final String[] SEPARATORS = {"-", "_", ""};

    private static final String PREFS = "litechunk";
    private static final String KEY_ORDER = "namingOrder";
    private static final String KEY_ENABLED = "namingEnabled";
    private static final String KEY_SEPARATOR = "namingSeparator";
    /** Compact and sortable, which is the point of putting a timestamp in a file name. */
    private static final String TIME_PATTERN = "yyyyMMdd-HHmm";

    private Naming() {
    }

    // ---------------------------------------------------------------------------------------------
    // Stored settings
    // ---------------------------------------------------------------------------------------------

    static String order(Context context) {
        return sanitiseOrder(read(context, KEY_ORDER, DEFAULT_ORDER));
    }

    static String enabled(Context context) {
        String saved = read(context, KEY_ENABLED, DEFAULT_ENABLED);
        List<String> known = new ArrayList<>();
        for (String token : split(saved)) {
            if (isToken(token) && !known.contains(token)) {
                known.add(token);
            }
        }
        return join(known);
    }

    static String separator(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SEPARATOR, DEFAULT_SEPARATOR);
        return saved == null ? DEFAULT_SEPARATOR : saved;
    }

    static void save(Context context, String order, String enabled, String separator) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ORDER, sanitiseOrder(order))
                .putString(KEY_ENABLED, enabled)
                .putString(KEY_SEPARATOR, separator == null ? DEFAULT_SEPARATOR : separator)
                .apply();
    }

    static void reset(Context context) {
        save(context, DEFAULT_ORDER, DEFAULT_ENABLED, DEFAULT_SEPARATOR);
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------------

    /**
     * Build the base name (no extension) for one converted archive.
     *
     * @param sourceName    the queue entry's name, which may still carry its archive extension.
     * @param bedrockTarget whether the archive is Bedrock, which decides the {@code BE} / {@code JE} tag.
     * @param timeMillis    when the archive was written.
     */
    static String render(String order, String enabled, String separator,
                         String sourceName, boolean bedrockTarget, long timeMillis) {
        Set<String> included = new LinkedHashSet<>(split(enabled));
        List<String> parts = new ArrayList<>();
        for (String token : split(sanitiseOrder(order))) {
            if (!included.contains(token)) {
                continue;
            }
            String value = valueOf(token, sourceName, bedrockTarget, timeMillis);
            if (!value.isEmpty()) {
                parts.add(value);
            }
        }
        String joined = String.join(separator == null ? DEFAULT_SEPARATOR : separator, parts);
        String safe = joined.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "world" : safe;
    }

    /** The same rules applied to a sample name, for the preview line and the sheet. */
    static String preview(Context context, String sourceName, boolean bedrockTarget) {
        return render(order(context), enabled(context), separator(context), sourceName, bedrockTarget,
                System.currentTimeMillis());
    }

    private static String valueOf(String token, String sourceName, boolean bedrockTarget, long timeMillis) {
        switch (token) {
            case TOKEN_NAME:
                return WorldFiles.baseName(sourceName);
            case TOKEN_EDITION:
                return bedrockTarget ? "BE" : "JE";
            case TOKEN_TIME:
                return new SimpleDateFormat(TIME_PATTERN, Locale.US).format(new Date(timeMillis));
            default:
                return "";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tokens
    // ---------------------------------------------------------------------------------------------

    static String label(String token) {
        switch (token) {
            case TOKEN_NAME:
                return "存档原名";
            case TOKEN_EDITION:
                return "版本标记（BE/JE）";
            case TOKEN_TIME:
                return "时间戳";
            default:
                return token;
        }
    }

    static String describeSeparator(String separator) {
        if (separator == null || separator.isEmpty()) {
            return "无分隔";
        }
        return "「" + separator + "」";
    }

    static boolean isToken(String token) {
        for (String known : TOKENS) {
            if (known.equals(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Force a stored order to be a permutation of every known token: settings written by an older build, or a value
     * that lost a token, would otherwise make a token disappear from the sheet with no way to bring it back.
     */
    private static String sanitiseOrder(String order) {
        List<String> result = new ArrayList<>();
        for (String token : split(order)) {
            if (isToken(token) && !result.contains(token)) {
                result.add(token);
            }
        }
        for (String token : TOKENS) {
            if (!result.contains(token)) {
                result.add(token);
            }
        }
        return join(result);
    }

    static List<String> split(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split(",")));
    }

    static String join(List<String> values) {
        return String.join(",", values);
    }

    private static String read(Context context, String key, String fallback) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, fallback);
        return saved == null ? fallback : saved;
    }
}
