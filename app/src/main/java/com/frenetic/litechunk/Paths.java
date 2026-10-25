package com.frenetic.litechunk;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Where LiteChunk keeps its data.
 * <p>
 * Everything the user can see lives under {@code /sdcard/Android/data/com.frenetic.litechunk/}: {@code output/} holds
 * the finished archives. Files inside the app's own directory need no storage permission at all, which is why this
 * works on Android 8.1 without asking for anything.
 * <p>
 * Scratch space is the exception. Conversion needs a real directory tree (LevelDB memory maps its files, so a
 * streaming implementation is not possible) and doing that on external storage means going through the FUSE layer,
 * which is measurably slower for the small random writes LevelDB does. Flush space therefore stays in the internal
 * cache directory and is deleted as soon as an item finishes.
 */
final class Paths {

    private static final String TAG = "Paths";

    private Paths() {
    }

    /** {@code /sdcard/Android/data/com.frenetic.litechunk}. */
    static File root(Context context) {
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File parent = external.getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        // No external storage at all (rare, but a shared-storage-less device would otherwise crash).
        return context.getFilesDir();
    }

    static File output(Context context) {
        return ensure(new File(root(context), "output"));
    }

    /** Serialised conversion queue, so a restart does not lose a list the user spent time assembling. */
    static File inputState(Context context) {
        return new File(root(context), "input.json");
    }

    /** Scratch root for one item; always inside the app's internal cache. */
    static File flashParent(Context context) {
        return new File(context.getCacheDir(), "flush");
    }

    static File newFlushDirectory(Context context) {
        File parent = flashParent(context);
        if (!parent.isDirectory() && !parent.mkdirs()) {
            return new File(context.getCacheDir(), "flush");
        }
        return new File(parent, "job-" + System.currentTimeMillis());
    }

    /** Clear leftovers from a previous run that was killed mid conversion. */
    static void clearFlash(Context context) {
        WorldFiles.delete(flashParent(context));
    }

    static File ensure(File directory) {
        if (directory.isDirectory()) {
            int state = probe(directory);
            if (state == PROBE_OK) {
                return directory;
            }
            if (state == PROBE_DENIED) {
                // A directory can survive an uninstall still owned by the previous install's uid; every write into
                // it then fails with EACCES even though it exists. Move it aside rather than deleting it (the
                // archives inside are still readable and might be the only copy the user has) and make a new one.
                // On this storage layer the rename often fails too, in which case the write error surfaces later
                // with an explanation from Job.describe instead of a raw EACCES.
                File aside = new File(directory.getParentFile(),
                    directory.getName() + ".old-" + System.currentTimeMillis());
                if (directory.renameTo(aside)) {
                    Log.w(TAG, "moved unwritable " + directory + " to " + aside.getName());
                    //noinspection ResultOfMethodCallIgnored
                    directory.mkdirs();
                } else {
                    Log.w(TAG, "cannot write to " + directory + " and cannot move it aside");
                }
                return directory;
            }
            // Some other failure (a full disk, a yanked SD card): leave the directory alone. Renaming it would hide
            // output the user already has for a problem that a rename cannot fix.
            return directory;
        }
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        return directory;
    }

    private static final int PROBE_OK = 0;
    private static final int PROBE_DENIED = 1;
    private static final int PROBE_BROKEN = 2;

    /**
     * Whether files can actually be created here.
     * <p>
     * {@link File#canWrite()} is not a reliable answer on the emulated storage layer: it goes through
     * {@code access(W_OK)}, which differs from what the {@code open()} we are about to issue decides. Creating a
     * file takes the same path as the write, so it is the check that actually predicts the outcome.
     */
    private static int probe(File directory) {
        File probe = new File(directory, ".probe-" + System.nanoTime());
        try {
            if (probe.createNewFile()) {
                //noinspection ResultOfMethodCallIgnored
                probe.delete();
                return PROBE_OK;
            }
            return PROBE_BROKEN;
        } catch (SecurityException denied) {
            return PROBE_DENIED;
        } catch (java.io.IOException failure) {
            String message = failure.getMessage();
            return message != null && (message.contains("EACCES") || message.contains("ermission"))
                ? PROBE_DENIED
                : PROBE_BROKEN;
        }
    }
}
