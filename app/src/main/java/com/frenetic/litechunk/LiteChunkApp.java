package com.frenetic.litechunk;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Applies the saved theme before any activity is created.
 * <p>
 * Doing it here rather than in {@code onCreate} avoids the visible flash of the wrong palette that happens when the
 * mode is applied after the activity has already inflated its views.
 */
public class LiteChunkApp extends Application {

    private static final String PREFS = "litechunk";
    private static final String KEY_NIGHT_MODE = "nightMode";

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(readNightMode(this));
        Store.get(this).start();
        Paths.output(this);
        Paths.clearFlash(this);
    }

    static int readNightMode(android.content.Context context) {
        int saved = context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        return normalise(saved);
    }

    static void writeNightMode(android.content.Context context, int mode) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_NIGHT_MODE, normalise(mode))
                .apply();
    }

    /** Next value in the follow system -> light -> dark -> follow system cycle. */
    static int nextNightMode(int current) {
        switch (normalise(current)) {
            case AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case AppCompatDelegate.MODE_NIGHT_NO:
                return AppCompatDelegate.MODE_NIGHT_YES;
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    static String describeNightMode(int mode) {
        switch (normalise(mode)) {
            case AppCompatDelegate.MODE_NIGHT_NO:
                return "浅色模式";
            case AppCompatDelegate.MODE_NIGHT_YES:
                return "深色模式";
            default:
                return "跟随系统";
        }
    }

    private static int normalise(int mode) {
        if (mode == AppCompatDelegate.MODE_NIGHT_NO || mode == AppCompatDelegate.MODE_NIGHT_YES) {
            return mode;
        }
        return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
}
