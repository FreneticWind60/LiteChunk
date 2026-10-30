package com.frenetic.litechunk.engine;

import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.FormatGroup;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * On-device diagnostic entry point.
 * <p>
 * Runs the exact same dex that ships in {@code assets/chunker.jar}, but through {@code dalvikvm}
 * instead of {@code DexClassLoader}. That makes it possible to prove the engine works on ART without
 * installing an APK, which matters on devices that gate installation behind a manual confirmation.
 * <p>
 * Usage: {@code dalvikvm -cp chunker.jar:api.dex com.frenetic.litechunk.engine.EngineCli <world.zip> <workDir> <formatId> [resourceDir]}
 * <p>
 * It exercises the parts that differ from a desktop JVM: dex verification, static initialisers,
 * LevelDB's mmap lifecycle, lz4's safe instantiation, and the Gson type wiring.
 */
public final class EngineCli {

    private EngineCli() {
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("usage: EngineCli <world.zip> <workDir> <formatId> [resourceDir]");
            System.out.println("RESULT: FAILED (bad args)");
            return;
        }
        try {
            run(args);
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.out);
            System.out.println("RESULT: FAILED " + throwable.getClass().getName() + ": " + throwable.getMessage());
        }
    }

    private static void run(String[] args) throws Exception {
        File zip = new File(args[0]);
        File work = new File(args[1]);
        String formatId = args[2];

        File output = new File(work, "output");
        deleteTree(output);

        File input;
        if (zip.isDirectory()) {
            input = zip;
            log("input is already a directory: " + input.getAbsolutePath());
        } else {
            input = new File(work, "input");
            deleteTree(input);
            log("unzip " + zip.getAbsolutePath() + " (" + zip.length() + " bytes)");
            long unzipStarted = System.currentTimeMillis();
            unzip(zip, input);
            log("unzipped in " + (System.currentTimeMillis() - unzipStarted) + "ms");
        }

        File worldRoot = findLevelDat(input);
        if (worldRoot == null) {
            System.out.println("RESULT: FAILED no level.dat in archive");
            return;
        }
        log("world root = " + worldRoot.getAbsolutePath());

        EngineImpl engine = new EngineImpl();
        if (args.length > 3) {
            engine.setResourceDirectory(new File(args[3]));
        }
        int concurrency = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        if (concurrency > 0) {
            engine.setConcurrency(concurrency);
        }
        boolean lean = args.length > 5 && Boolean.parseBoolean(args[5]);
        engine.setLeanMode(lean);
        log("heap limit = " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB, concurrency = "
                + (concurrency > 0 ? String.valueOf(concurrency) : "default") + ", lean = " + lean);

        log("engine version = " + engine.engineVersion());
        int versions = 0;
        for (FormatGroup group : engine.formats()) {
            versions += group.versions.length;
        }
        log("formats = " + engine.formats().size() + " groups / " + versions + " versions");

        Detection detection = engine.detect(worldRoot);
        log("detect = " + (detection == null
                ? "null"
                : detection.formatId + " [" + detection.formatLabel + "] " + detection.version));

        final int[] callbacks = {0};
        final double[] peak = {0};
        HeapSampler sampler = new HeapSampler();
        sampler.start();

        long started = System.currentTimeMillis();
        long cpuStarted = processCpuNanos();
        try {
            engine.convert(worldRoot, output, formatId, (fraction, stage) -> {
                callbacks[0]++;
                if (!Double.isNaN(fraction) && fraction > peak[0]) {
                    peak[0] = fraction;
                }
                if (callbacks[0] % 25 == 0) {
                    log("  progress " + String.format("%.1f", peak[0] * 100) + "% " + stage
                            + " (heap " + sampler.currentMb() + " MB)");
                }
            });
        } finally {
            sampler.stopSampling();
        }
        long elapsed = System.currentTimeMillis() - started;

        boolean hasLevelDat = findLevelDat(output) != null;
        boolean hasDb = new File(output, "db").isDirectory();
        boolean hasRegion = findDirectory(output, "region") != null;

        log("converted in " + elapsed + "ms with " + callbacks[0] + " callbacks, peak="
                + String.format("%.3f", peak[0]));
        log("cpu = " + describeCpu(processCpuNanos() - cpuStarted, elapsed));
        log("peak heap = " + sampler.peakMb() + " MB of "
                + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        log("output: level.dat=" + hasLevelDat + " db=" + hasDb + " region=" + hasRegion);

        if (hasLevelDat && (hasDb || hasRegion)) {
            System.out.println("RESULT: OK " + elapsed + "ms " + detection + " -> " + formatId);
        } else {
            System.out.println("RESULT: FAILED output structure incomplete");
        }
    }

    private static void log(String message) {
        System.out.println("[EngineCli] " + message);
    }

    /**
     * Read the total CPU time this process has burned so far.
     * <p>
     * Wall clock on a multi-threaded conversion is mostly waiting, so it hides whether a change actually removed
     * work. CPU time does not: it sums every thread, which is exactly the quantity that per-block optimisations
     * move. Reflective because {@code com.sun.management.OperatingSystemMXBean} does not exist on ART and this class
     * is part of the shipped dex.
     *
     * @return nanoseconds of CPU time, or -1 when the platform does not expose it (Android).
     */
    private static long processCpuNanos() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            // The runtime object is com.sun.management.internal.OperatingSystemImpl, which lives in a package the
            // module system does not open; invoking the exported interface method works.
            Class<?> sunBean = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!sunBean.isInstance(bean)) return -1L;
            Object value = sunBean.getMethod("getProcessCpuTime").invoke(bean);
            return value instanceof Long ? (Long) value : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /**
     * Format a CPU/wall pair as seconds plus the average number of cores it kept busy.
     *
     * @param cpuNanos  the CPU time used, or -1 if unavailable.
     * @param wallMillis the wall clock time used.
     * @return the formatted description.
     */
    private static String describeCpu(long cpuNanos, long wallMillis) {
        if (cpuNanos < 0) return "unavailable";
        double cpuSeconds = cpuNanos / 1_000_000_000.0;
        double wallSeconds = wallMillis / 1000.0;
        double cores = wallSeconds > 0 ? cpuSeconds / wallSeconds : 0;
        return String.format("%.1f s over %.1f s wall (%.2f cores avg)", cpuSeconds, wallSeconds, cores);
    }

    /**
     * Samples heap usage in the background so the peak can be reported after a run.
     * <p>
     * A failure only tells you the process died; knowing that it climbed to 480 of 512 MB before dying says the
     * pipeline actually needed more, while a spike right at the start points at something else.
     */
    private static final class HeapSampler extends Thread {
        private volatile boolean running = true;
        private volatile long peak;
        private volatile long current;

        HeapSampler() {
            setName("heap-sampler");
            setDaemon(true);
        }

        @Override
        public void run() {
            Runtime runtime = Runtime.getRuntime();
            while (running) {
                long used = runtime.totalMemory() - runtime.freeMemory();
                current = used;
                if (used > peak) {
                    peak = used;
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        long peakMb() {
            return peak / (1024L * 1024L);
        }

        long currentMb() {
            return current / (1024L * 1024L);
        }

        void stopSampling() {
            running = false;
            interrupt();
        }
    }

    private static void unzip(File zipFile, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("cannot create " + destination);
        }
        String base = destination.getCanonicalPath();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream raw = new java.io.FileInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(base)) {
                    throw new IOException("zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static File findLevelDat(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        if (new File(directory, "level.dat").isFile()) {
            return directory;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File found = findLevelDat(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static File findDirectory(File directory, String name) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (name.equals(child.getName())) {
                    return child;
                }
                File nested = findDirectory(child, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
