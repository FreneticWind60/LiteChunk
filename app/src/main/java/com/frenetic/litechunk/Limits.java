package com.frenetic.litechunk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The size limits behind the two "this is big" prompts.
 * <p>
 * Four numbers: the archive and folder sizes that ask before an import, and the compressed/unpacked sizes that ask
 * before a non-zip save is unpacked into the cache. They are not arbitrary: on a phone with 3 GB free, a 1 GB limit
 * means "you have room for it"; on one with 200 MB free it means "you do not". So the recommended set is derived from
 * {@link Space#free(Context)} and capped at the values that were picked by hand originally
 * (512 MB / 1 GB / 256 MB / 1 GB) - the numbers the warnings were designed around.
 * <p>
 * Nothing here is a hard limit. Every prompt can be dismissed as "continue", because the device is the user's and a
 * world that takes ten minutes to copy is still their choice.
 */
final class Limits {

    static final long MB = 1024L * 1024L;

    /** The original hand-picked numbers, and the ceiling for the recommended set. */
    static final long DEFAULT_ARCHIVE = 512L * MB;
    static final long DEFAULT_FOLDER = 1024L * MB;
    static final long DEFAULT_EXTRACT_IN = 256L * MB;
    static final long DEFAULT_EXTRACT_OUT = 1024L * MB;

    // No recommendation goes below these: a nearly full phone would otherwise warn on every save.
    private static final long FLOOR_ARCHIVE = 64L * MB;
    private static final long FLOOR_FOLDER = 128L * MB;
    private static final long FLOOR_EXTRACT_IN = 32L * MB;
    private static final long FLOOR_EXTRACT_OUT = 128L * MB;

    private static final String FILE = "limits";

    boolean auto = true;
    long archive = DEFAULT_ARCHIVE;
    long folder = DEFAULT_FOLDER;
    long extractIn = DEFAULT_EXTRACT_IN;
    long extractOut = DEFAULT_EXTRACT_OUT;

    private Limits() {
    }

    /** @return the settings in force, with the recommended set already resolved when it is selected. */
    static Limits load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Limits limits = new Limits();
        limits.auto = preferences.getBoolean("auto", true);
        limits.archive = preferences.getLong("archive", DEFAULT_ARCHIVE);
        limits.folder = preferences.getLong("folder", DEFAULT_FOLDER);
        limits.extractIn = preferences.getLong("extractIn", DEFAULT_EXTRACT_IN);
        limits.extractOut = preferences.getLong("extractOut", DEFAULT_EXTRACT_OUT);
        if (limits.auto) {
            long[] recommended = recommended(context);
            limits.archive = recommended[0];
            limits.folder = recommended[1];
            limits.extractIn = recommended[2];
            limits.extractOut = recommended[3];
        }
        return limits;
    }

    void save(Context context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("auto", auto)
                .putLong("archive", archive)
                .putLong("folder", folder)
                .putLong("extractIn", extractIn)
                .putLong("extractOut", extractOut)
                .apply();
    }

    /** @return one line for the about page: which set is in use, with its numbers. */
    String summary() {
        return (auto ? Text.get(R.string.Limits_01) : Text.get(R.string.Limits_02))
                + " · " + mb(archive) + " / " + mb(folder) + " / " + mb(extractIn) + " / " + mb(extractOut);
    }

    /**
     * The recommended set for this device.
     * <p>
     * Each number is a fraction of the free space - an import copies, so a quarter of what is left; a folder is packed
     * rather than copied twice, so a third; unpacking keeps the archive and the tree, so an eighth in and a half out.
     * Capped at the hand-picked defaults and floored so a full device still gets a usable warning.
     *
     * @param context a context.
     * @return {archive, folder, extractIn, extractOut} in bytes.
     */
    static long[] recommended(Context context) {
        long free = Space.free(context);
        if (free <= 0L) {
            return new long[]{DEFAULT_ARCHIVE, DEFAULT_FOLDER, DEFAULT_EXTRACT_IN, DEFAULT_EXTRACT_OUT};
        }
        return new long[]{
                scale(free / 4L, DEFAULT_ARCHIVE, FLOOR_ARCHIVE),
                scale(free / 3L, DEFAULT_FOLDER, FLOOR_FOLDER),
                scale(free / 8L, DEFAULT_EXTRACT_IN, FLOOR_EXTRACT_IN),
                scale(free / 2L, DEFAULT_EXTRACT_OUT, FLOOR_EXTRACT_OUT)
        };
    }

    private static long scale(long wanted, long ceiling, long floor) {
        return Math.max(floor, Math.min(ceiling, wanted));
    }

    /** @return megabytes as shown in the settings sheet, e.g. {@code 512}. */
    static long mb(long bytes) {
        return Math.max(1L, Math.round((double) bytes / MB));
    }
}
