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
 * cache directory and is deleted as soon as an item finishes; the per-entry copy made for detection is reused by the
 * conversion and dropped then, so a world is unpacked once instead of twice.
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

    /**
     * Scratch directory owned by one queue entry, named after it so a later stage can find it again.
     * <p>
     * Detection and conversion both need the source unpacked as real files. Detection used to delete its copy when it
     * finished, which meant a world was unzipped twice: once to read its version, once to convert it. Keeping the
     * copy keyed by entry id makes the second one free.
     */
    static File stageFor(Context context, String itemId) {
        String safe = itemId == null ? "unknown" : itemId.replaceAll("[^A-Za-z0-9-]", "");
        return new File(flashParent(context), "stage-" + safe);
    }

    /**
     * Marker written once a stage is fully unpacked.
     * <p>
     * Without it a conversion that starts while detection is still copying would read a half written world: the
     * queue row appears the moment the archive is picked, so "add then immediately convert" is a real sequence.
     */
    private static final String STAGE_READY = ".ready";

    static void markStageReady(File stage) {
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(stage, STAGE_READY).createNewFile();
        } catch (Exception ignored) {
            // A stage without the marker is simply not reused; the conversion unpacks again.
        }
    }

    static boolean isStageReady(File stage) {
        return new File(stage, STAGE_READY).isFile();
    }

    /**
     * Drop stage directories that no longer belong to a queued entry.
     * <p>
     * Called from the conversion worker, never from the UI: that worker is the only thing that reads a stage, so
     * deleting other stages there cannot pull the ground out from under a running job. Doing it when an item is
     * added would race a conversion that started a moment earlier.
     *
     * @param keepItemIds ids of the entries in the batch, whose stages must survive.
     */
    static void pruneStagesExcept(Context context, java.util.Set<String> keepItemIds) {
        File parent = flashParent(context);
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.getName().startsWith("stage-")) {
                continue;
            }
            boolean keep = false;
            for (String id : keepItemIds) {
                if (child.getName().equals(stageFor(context, id).getName())) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                WorldFiles.delete(child);
            }
        }
    }

    static void deleteStage(Context context, String itemId) {
        WorldFiles.delete(stageFor(context, itemId));
    }

    /**
     * Clear leftovers from a run that was killed mid conversion.
     * <p>
     * Only the anonymous {@code job-*} directories are removed. A {@code stage-<itemId>} belongs to a queue entry that
     * survives in {@code input.json}, and it is validated by its own {@code .ready} marker — wiping it here would pull
     * the copy out from under a detection thread that the same startup just spawned.
     */
    static void clearFlash(Context context) {
        File parent = flashParent(context);
        File[] children = parent.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.getName().startsWith("job-")) {
                WorldFiles.delete(child);
            }
        }
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
