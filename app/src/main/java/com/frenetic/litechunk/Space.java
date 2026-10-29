package com.frenetic.litechunk;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.util.List;

/**
 * Free space checks for the two places where the app writes something as big as a world.
 * <p>
 * These are guesses, not measurements: a compressed Java world unpacks to several times its size, and the converted
 * output is usually smaller but not always. The estimate therefore errs on the high side and the UI only ever asks
 * for confirmation — blocking on an estimate would refuse conversions that would have fit, which is worse than a
 * warning nobody reads.
 */
final class Space {

    /**
     * Bytes assumed per byte of source.
     * <p>
     * Conversion holds the unpacked world (roughly 2–3× the compressed size) plus the converted output. Two is the
     * number that catches "the phone is nearly full" without crying wolf on a normal device.
     */
    private static final long FACTOR = 2L;

    private Space() {
    }

    /**
     * Free bytes that both the output folder and the cache can use.
     * <p>
     * On every Android release since 4.4 emulated storage lives on the same filesystem as {@code /data}, so these are
     * the same number and taking the minimum costs nothing. When they are genuinely different volumes (a real SD
     * card as the external dir) the smaller one is the one that runs out first.
     */
    static long free(Context context) {
        long output = available(Paths.output(context));
        long cache = available(context.getCacheDir());
        return output <= 0L ? cache : (cache <= 0L ? output : Math.min(output, cache));
    }

    /** @return estimated bytes for a batch, or {@code -1} when nothing in it has a known size. */
    static long estimate(List<QueueItem> items) {
        long total = 0L;
        boolean known = false;
        for (QueueItem item : items) {
            if (item.size > 0L) {
                total += item.size;
                known = true;
            }
        }
        return known ? total * FACTOR : -1L;
    }

    private static long available(File directory) {
        try {
            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        } catch (Exception unusable) {
            // A StatFs that cannot answer is not a reason to refuse work.
            return -1L;
        }
    }
}
