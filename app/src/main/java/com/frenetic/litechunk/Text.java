package com.frenetic.litechunk;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * Resource lookup for code that has no {@link Context} to ask.
 * <p>
 * Most of the app can call {@code getString} because it lives in an activity or fragment, but three kinds of code
 * cannot: the file plumbing ({@link WorldFiles}, {@link Archive}), the archive sniffing ({@link Archives},
 * {@link Inbox}) and the error mapper ({@link Job#describe}). Those used to hold Chinese sentences as literals, which
 * is exactly what kept the app from being translatable.
 * <p>
 * The {@link Resources} used here are built from the saved language preference rather than from
 * {@code context.getResources()}: {@link ConversionService} runs in its own process, where AppCompat's per-app locale
 * is not applied to anything (there is no activity to recreate), so the service would otherwise report errors in the
 * system language while the UI is in the chosen one.
 */
final class Text {

    private static volatile Resources resources;

    private Text() {
    }

    /**
     * Build the lookup table for this process. Safe to call more than once; the last call wins.
     *
     * @param context any context of the process.
     */
    static void install(Context context) {
        Context application = context.getApplicationContext();
        Configuration configuration = new Configuration(application.getResources().getConfiguration());
        String tag = Language.tag(application);
        if (!tag.isEmpty()) {
            configuration.setLocale(Locale.forLanguageTag(tag));
        }
        resources = application.createConfigurationContext(configuration).getResources();
    }

    /** @return the string, or an empty string before {@link #install(Context)} has run. */
    static String get(int id) {
        Resources current = resources;
        return current == null ? "" : current.getString(id);
    }

    /** @return the formatted string, see {@link Resources#getString(int, Object...)}. */
    static String get(int id, Object... args) {
        Resources current = resources;
        return current == null ? "" : current.getString(id, args);
    }
}
