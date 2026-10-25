package com.frenetic.litechunk;

import java.util.Locale;

/**
 * Decides how much of the conversion pipeline fits in the heap this process was given.
 * <p>
 * Chunker's pipeline keeps every in-flight chunk column alive between reads and writes, so peak heap grows roughly
 * linearly with worker threads. Upstream defaults to 8 threads, which is sized for a desktop JVM; on Android the cap
 * is whatever {@code android:largeHeap} allows (512 MB on most devices, 256 MB without it) and 8 threads run out of
 * memory part way through a medium sized world.
 * <p>
 * The budget below is measured rather than guessed. A 278 MB Java world converted successfully in 169 s with 2
 * threads, 176 s with a 320 MB heap and 183 s with a 256 MB heap, so two threads fit in 256 MB as long as the
 * collector is allowed to run: the scary 469 MB peak reported at 512 MB is mostly unreclaimed garbage, not live
 * data. Going past two threads only hurts (8 threads measured slower than 2 on the same world), so threads are
 * capped rather than scaled with the heap. {@link #lean} stays as the floor for devices that really are small: it
 * drops the peak from 469 MB to 270 MB on that same world.
 */
final class MemoryPlan {

    /** Heap needed per conversion worker thread before it is worth a second one. */
    private static final long MB_PER_THREAD = 128L;
    /** More than two writers only adds GC pressure: 8 threads measured slower than 2 on the same world. */
    private static final int MAX_THREADS = 2;
    /** Below this much heap, even a single thread has no room for lighting and entity data. */
    private static final long LEAN_BELOW_MB = 256L;

    final int heapMb;
    final int threads;
    /** Skip lighting, entities, block entities, height maps and loot tables. */
    final boolean lean;

    private MemoryPlan(int heapMb, int threads, boolean lean) {
        this.heapMb = heapMb;
        this.threads = threads;
        this.lean = lean;
    }

    static MemoryPlan forCurrentProcess() {
        return of(heapMb());
    }

    static MemoryPlan of(int heapMb) {
        int threads = (int) (heapMb / MB_PER_THREAD);
        if (threads < 1) {
            threads = 1;
        }
        if (threads > MAX_THREADS) {
            threads = MAX_THREADS;
        }
        return new MemoryPlan(heapMb, threads, heapMb < LEAN_BELOW_MB);
    }

    /** Same budget but reduced to the floor: one thread and no optional stages. */
    MemoryPlan leanRetry() {
        return new MemoryPlan(heapMb, 1, true);
    }

    /** @return heap ceiling of this process in MB, i.e. what {@code largeHeap} grants. */
    static int heapMb() {
        return (int) (Runtime.getRuntime().maxMemory() / (1024L * 1024L));
    }

    /** Current heap in use, for logging. */
    static long usedMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
    }

    /**
     * Whether a failure looks like the heap ran out.
     * <p>
     * Chunker reports failures through {@code CompletionException}, and the message often carries the original
     * {@code OutOfMemoryError} while the cause chain no longer does, so both the type and the text have to be checked.
     * An allocation that fails inside a native buffer or a LevelDB mmap shows up as {@code OutOfMemoryError} too,
     * which is exactly what should trigger the retry.
     */
    static boolean isOutOfMemory(Throwable throwable) {
        int depth = 0;
        for (Throwable current = throwable; current != null && depth++ < 12; current = current.getCause()) {
            if (current instanceof OutOfMemoryError) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("outofmemory") || lower.contains("failed to allocate")
                        || lower.contains("heap space")) {
                    return true;
                }
            }
            if (current == current.getCause()) {
                break;
            }
        }
        return false;
    }

    String describe() {
        return "堆上限 " + heapMb + " MB / " + threads + " 线程" + (lean ? " / 精简模式" : "");
    }
}
