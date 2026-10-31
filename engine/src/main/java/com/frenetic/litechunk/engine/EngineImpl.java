package com.frenetic.litechunk.engine;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.FormatGroup;
import com.frenetic.litechunk.api.FormatVersion;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.ProgressListener;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.LevelReaderWriter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.scheduling.task.TrackedTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The trimmed Chunker entry point used by the Android app.
 * <p>
 * This class is loaded through a {@code DexClassLoader}; it implements an interface owned by the app so the app never
 * has to reflect on individual Chunker types.
 */
public final class EngineImpl implements ChunkerEngine {

    /**
     * Must match the Chunker tag the engine sources were copied from.
     */
    public static final String ENGINE_VERSION = "1.21.0";

    private static final String STAGE_CONVERT = "转换地形与方块";
    private static final String STAGE_FINISH = "整理数据";
    private static final String STAGE_COMPACT = "整理数据库（较慢，请保持前台）";

    /** Worker threads for the conversion pipeline, upstream default is 8. */
    private int concurrency = 8;
    /** Smallest LevelDB write buffer worth using; below this the memtable flushes dominate the run. */
    private static final int WRITE_BUFFER_MIN_MB = 16;
    /** Largest useful write buffer: past this the wall clock stops improving and only the heap grows. */
    private static final int WRITE_BUFFER_MAX_MB = 64;
    /** Heap granted per MB of write buffer, from the sweep in {@link #applyMemoryBudget}. */
    private static final int MB_PER_WRITE_BUFFER_MB = 10;
    /** Skip the memory hungry stages (lighting / entities / block entities / height maps / loot tables). */
    private boolean lean;

    @Override
    public void setConcurrency(int threads) {
        concurrency = Math.max(1, Math.min(8, threads));
    }

    @Override
    public void setLeanMode(boolean lean) {
        this.lean = lean;
    }

    @Override
    public String engineVersion() {
        return ENGINE_VERSION;
    }

    @Override
    public List<FormatGroup> formats() {
        List<FormatGroup> groups = new ArrayList<>();
        for (EncodingType type : EncodingType.getWriteableTypes()) {
            if (type.isInternal()) {
                continue; // preview / settings pseudo formats
            }
            List<Version> versions = new ArrayList<>(type.getSupportedVersions());
            if (versions.isEmpty()) {
                continue;
            }
            Collections.sort(versions, Collections.reverseOrder());

            FormatVersion[] output = new FormatVersion[versions.size()];
            for (int i = 0; i < versions.size(); i++) {
                Version version = versions.get(i);
                String edition = type.getName().toUpperCase(Locale.ROOT);
                output[i] = new FormatVersion(edition + "_" + version, version.toString());
            }
            groups.add(new FormatGroup(type.getName().toUpperCase(Locale.ROOT), type.getLabel(), output));
        }

        // Java first, then Bedrock, so the UI order stays stable.
        Collections.sort(groups, Comparator.comparingInt(group -> "JAVA".equals(group.id) ? 0 : 1));
        return groups;
    }

    @Override
    public Detection detect(File worldDirectory) {
        if (worldDirectory == null || !worldDirectory.isDirectory()) {
            return null;
        }

        WorldConverter converter = new WorldConverter(UUID.randomUUID());
        LevelReaderWriter reader = null;
        try {
            Optional<? extends LevelReader> found = EncodingType.findReader(worldDirectory, converter);
            if (found.isEmpty()) {
                // Logging matters here: the UI can only say "unknown version", and that message is identical
                // whether the world is unsupported or a reader crashed half way through opening it.
                System.err.println("[LiteChunk] detect: no reader accepted " + worldDirectory);
                return null;
            }
            reader = found.get();
            EncodingType type = reader.getEncodingType();
            return new Detection(
                    type.getName().toUpperCase(Locale.ROOT),
                    type.getLabel(),
                    reader.getVersion().toString()
            );
        } catch (Throwable throwable) {
            // Detection is best effort, the UI falls back to manual selection, but the cause still needs to
            // reach logcat: a silently swallowed exception is undebuggable on a device.
            throwable.printStackTrace();
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.free();
                } catch (Exception ignored) {
                    // Closing a half opened LevelDB is not fatal for detection.
                }
            }
        }
    }

    @Override
    public void convert(File inputDirectory, File outputDirectory, String outputFormatId, ProgressListener listener) throws Exception {
        if (inputDirectory == null || !inputDirectory.isDirectory()) {
            throw new IllegalArgumentException("存档目录不存在：" + inputDirectory);
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("输出目录为空");
        }
        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建输出目录：" + outputDirectory);
        }

        WorldConverter converter = new WorldConverter(UUID.randomUUID());
        converter.setWorkerThreads(concurrency);
        applyProfile(converter);

        // Must come after setWorkerThreads: that call derives the three budgets from the thread count and would
        // overwrite anything set before it.
        applyMemoryBudget(converter);
        applyOverrides(converter);

        // Bedrock output ends with a full LevelDB compaction, which for a large world can run for over a minute
        // without any task progress at all. Reporting it as its own stage is the difference between "still working"
        // and "looks hung, let me kill it".
        final boolean[] compacting = {false};
        converter.setCompactionSignal(started -> compacting[0] = started);

        LevelReader reader = EncodingType.findReader(inputDirectory, converter)
                .orElseThrow(() -> new IllegalArgumentException("无法识别存档格式，请确认选择的是世界根目录"));
        LevelWriter writer = resolveWriter(outputFormatId, converter, outputDirectory)
                .orElseThrow(() -> new IllegalArgumentException("不支持的目标格式：" + outputFormatId));

        TrackedTask<Void> task = converter.convert(reader, writer);
        CompletableFuture<Void> future = task.future();

        while (!future.isDone()) {
            if (listener != null) {
                if (compacting[0]) {
                    listener.onProgress(0.97D, STAGE_COMPACT);
                } else {
                    // The task tree's weighting stalls once reading is batched, so prefer the region counter and keep
                    // the remaining tenth for level data + compaction.
                    double regionProgress = converter.getRegionProgress();
                    listener.onProgress(regionProgress >= 0 ? regionProgress * 0.9D : task.getProgress(),
                            STAGE_CONVERT);
                }
            }
            try {
                future.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Polling interval elapsed, report progress again.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException failure) {
                throw unwrap(failure);
            }
        }

        // Surface a failure that completed between two polls.
        try {
            future.get();
        } catch (ExecutionException failure) {
            throw unwrap(failure);
        }

        if (listener != null) {
            listener.onProgress(1D, STAGE_FINISH);
        }
    }

    @Override
    public LevelInfo inspectLevel(File worldDirectory) throws Exception {
        return LevelEditor.open(worldDirectory).inspect();
    }

    @Override
    public void applyLevelChanges(File worldDirectory, Map<String, String> changes) throws Exception {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        LevelEditor.open(worldDirectory).apply(changes);
    }

    @Override
    public void setResourceDirectory(File directory) {
        Res.setBaseDirectory(directory);
    }

    /**
     * Apply the memory profile to a converter.
     * <p>
     * Lean mode drops the stages whose memory cost is per block / per column rather than per chunk: lighting and
     * height maps need a full copy of every column, and pre-transforms keep a ring of neighbour columns alive. Those
     * are invisible in the finished world.
     * <p>
     * Entities and block entities are deliberately <em>not</em> dropped, even when memory is tight. They are what the
     * player notices first (an empty world with intact terrain still looks broken), and they cost almost nothing: a
     * mob is a few hundred bytes of NBT and a container is a list of item stacks, against the ~2 KB per column that
     * lighting alone needs. Dropping them to save memory traded a visible regression for an invisible gain.
     */
    private void applyProfile(WorldConverter converter) {
        // Chunker only implements entity handlers for paintings and item frames, so every other entity is
        // "unknown" and would be dropped during reading. Keeping them costs almost nothing (a mob is a few
        // hundred bytes of NBT) and is the difference between a converted world that arrives empty and one that
        // arrives alive, so this is on for every profile.
        converter.setPreserveUnknownEntities(true);

        // The Bedrock writer ends with a full database compaction, which rewrites every SST in the output. It
        // changes nothing about what Minecraft reads back, only the on-disk layout, and the folder is packed into
        // a zip immediately afterwards. On a 250MB world that step alone ran for tens of seconds.
        converter.setLevelDBCompaction(false);

        if (!lean) {
            return;
        }
        converter.setProcessLighting(false);
        converter.setProcessHeightMap(false);
        converter.setProcessColumnPreTransform(false);
        converter.setProcessLootTables(false);
    }

    /**
     * Choose the LevelDB write buffer from the heap this process was given.
     * <p>
     * {@code setWorkerThreads} derives it as {@code threads * 16 MB} clamped to 16-64, which means a phone running the
     * two threads the memory plan allows always gets 32 MB - the worst value on the curve. Measured on a 257 MB
     * world, two threads, {@code -Xmx512m}:
     * <pre>
     *   buffer   wall clock      CPU      peak heap
     *   16 MB    56.5 s         112 s     355 MB
     *   32 MB    38.3 / 38.7 s  88 / 89   437 / 426 MB   <- what threads*16 gives
     *   40 MB    41.3 / 41.5 s  95 / 93   471 / 430 MB   <- cliff: one more flush
     *   44 MB    25.5 / 25.4 s  78 / 77   455 / 450 MB
     *   48 MB    24.3 / 24.1 s  77 / 75   464 / 476 MB
     *   64 MB    24.6 / 24.6 s  77 / 78   505 / 479 MB
     *   256 MB   26.8 s          81 s     973 MB
     * </pre>
     * The curve is a staircase, not a slope: LevelDB flushes the memtable to an SST when the buffer fills, and every
     * flush stalls the single writer thread that all workers queue behind, so what matters is how many flushes the
     * output needs (40 -> 41 s, 44 -> 25 s on the same world). Past 48 MB the staircase is flat while the heap cost
     * keeps rising. The budget is therefore {@code heap / 10} clamped to 16-64: 51 MB on a 512 MB heap (what
     * {@code largeHeap} grants on most phones) and 64 MB on a desktop JVM, where this changes nothing.
     *
     * @param converter the converter to apply the budget to.
     */
    private void applyMemoryBudget(WorldConverter converter) {
        // The lean profile is the retry after an out-of-memory failure. It must not hand back a bigger write buffer
        // than setWorkerThreads derived, or the retry fails in exactly the same place.
        if (lean) return;

        int heapMb = (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024));
        int writeBufferMb = Math.max(WRITE_BUFFER_MIN_MB, Math.min(WRITE_BUFFER_MAX_MB, heapMb / MB_PER_WRITE_BUFFER_MB));
        converter.setLevelDBWriteBufferSize(writeBufferMb * 1024 * 1024);
    }

    /**
     * Apply diagnostic overrides for the pipeline budgets, read from system properties.
     * <p>
     * {@code WorldConverter.setWorkerThreads} derives {@code inFlightRegions}, {@code inFlightColumns} and the
     * LevelDB write buffer from the thread count, which makes it impossible to find out whether that coupling is
     * right: sweeping threads always moves three other dials with it. These properties allow one dial at a time.
     * <p>
     * Only active when a property is set, so the shipped path is unchanged: {@code -Dlc.inFlightRegions=N},
     * {@code -Dlc.inFlightColumns=N}, {@code -Dlc.writeBufferMb=N}.
     *
     * @param converter the converter to apply the overrides to.
     */
    private static void applyOverrides(WorldConverter converter) {
        Integer regions = readIntProperty("lc.inFlightRegions");
        Integer columns = readIntProperty("lc.inFlightColumns");
        Integer writeBufferMb = readIntProperty("lc.writeBufferMb");

        if (regions == null && columns == null && writeBufferMb == null) return;

        if (regions != null) converter.setInFlightRegions(regions);
        if (columns != null) converter.setInFlightColumns(columns);
        if (writeBufferMb != null) converter.setLevelDBWriteBufferSize(writeBufferMb * 1024 * 1024);

        System.out.println("[engine] budgets: threads=" + converter.getWorkerThreads()
                + ", inFlightRegions=" + converter.getInFlightRegions()
                + ", inFlightColumns=" + converter.getInFlightColumns()
                + ", writeBufferMb=" + (converter.getLevelDBWriteBufferSize() / (1024 * 1024)));
    }

    /**
     * Read an int from a system property, tolerating a missing or malformed value.
     *
     * @param name the property name.
     * @return the parsed value, or null when it is not set or not a number.
     */
    private static Integer readIntProperty(String name) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Exception unwrap(ExecutionException failure) {
        Throwable cause = failure.getCause();
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return failure;
    }

    /**
     * Resolve {@code JAVA_1.21.6} / {@code BEDROCK_1.21.120} into a writer.
     */
    private static Optional<? extends LevelWriter> resolveWriter(String id, WorldConverter converter, File outputDirectory) {
        if (id == null) {
            return Optional.empty();
        }
        int separator = id.indexOf('_');
        String edition = (separator == -1 ? id : id.substring(0, separator)).toUpperCase(Locale.ROOT);
        Version version = separator == -1 ? null : Version.fromString(id.substring(separator + 1));

        for (EncodingType type : EncodingType.getWriteableTypes()) {
            if (type.getName().equalsIgnoreCase(edition)) {
                return type.createWriter(outputDirectory, version, converter);
            }
        }
        return Optional.empty();
    }
}
