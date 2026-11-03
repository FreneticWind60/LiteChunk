package com.frenetic.litechunk;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * The app language: a saved override, or "follow the system" when nothing is saved.
 * <p>
 * Applied in two places because the app runs in two processes. {@link AppCompatDelegate#setApplicationLocales}
 * recreates activities, which only matters in the UI process; {@link Text#install(Context)} covers the conversion
 * process, which has no activity and would otherwise stay on the system language.
 */
final class Language {

    private static final String PREFS = "litechunk";
    private static final String KEY = "language";

    /** Empty means "follow the system". */
    static final String FOLLOW = "";

    /** Selectable values, in the order the dialog shows them. */
    static final String[] TAGS = {FOLLOW, "zh-CN", "en", "ja"};

    /** Names of the options as they appear in the dialog, each written in its own language. */
    private static final int[] LABEL_IDS = {
            R.string.language_follow, R.string.language_zh, R.string.language_en, R.string.language_ja};

    private Language() {
    }

    static String tag(Context context) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, FOLLOW);
        return saved == null ? FOLLOW : saved;
    }

    static int index(Context context) {
        String saved = tag(context);
        for (int i = 0; i < TAGS.length; i++) {
            if (TAGS[i].equals(saved)) {
                return i;
            }
        }
        return 0;
    }

    /** Resolved on call, not at class load: the resources are only available after {@link Text#install}. */
    static String label(int index) {
        return Text.get(LABEL_IDS[Math.max(0, Math.min(LABEL_IDS.length - 1, index))]);
    }

    /** Persist a choice from {@link #TAGS} and apply it to both processes' lookups. */
    static void save(Context context, int index) {
        String value = TAGS[Math.max(0, Math.min(TAGS.length - 1, index))];
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, value)
                .apply();
        apply(context);
    }

    /**
     * Push the saved choice into AppCompat (recreates the UI) and into {@link Text} (this process's string lookup).
     * Called at startup as well, so an upgrade that changed the tag list cannot leave a stale value behind.
     */
    static void apply(Context context) {
        String tag = tag(context);
        AppCompatDelegate.setApplicationLocales(tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
        Text.install(context);
    }
}
