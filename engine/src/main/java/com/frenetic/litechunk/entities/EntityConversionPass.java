package com.frenetic.litechunk.entities;

import com.frenetic.litechunk.api.ProgressListener;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBKey;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.table.BloomFilterPolicy;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Second conversion pass: entity records, written after the terrain pass has finished.
 * <p>
 * Why a separate pass at all: entity conversion is optional, it is entirely independent of blocks, and keeping it out
 * of {@code WorldConverter} means the entity work can be lifted out of the engine as one package. The terrain pass
 * runs with {@code processEntities(false)}, so nothing here overwrites or races with it.
 * <p>
 * What it reads: the Java {@code entities/r.X.Z.mca} region files directly (1.17+), falling back to {@code region/}
 * for older worlds where entities live inside the chunk NBT. No block data is decoded.
 * <p>
 * What it writes: one {@code actorprefix} record per actor and one {@code digp} record per column, both keyed exactly
 * the way the engine's Bedrock writer keys them, so the game sees the same layout it would have seen from the in-line
 * path.
 * <p>
 * Failure model: a broken region or an unreadable entity is logged and skipped. The worst case is a world without
 * entities, never a half-written world - the terrain pass has already closed its database by the time this runs.
 */
public final class EntityConversionPass {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int COLUMNS = 32 * 32;

    private final File inputDirectory;
    private final File outputDirectory;
    private final int threads;
    private final Consumer<String> log;
    private final JavaResolvers javaResolvers;
    private final BedrockResolvers bedrockResolvers;

    private final AtomicLong nextUniqueId = new AtomicLong();

    /**
     * @param inputDirectory   the Java world that was converted.
     * @param outputDirectory  the Bedrock world that was produced.
     * @param threads          worker count, clamped to at least 1.
     * @param log              receives non-fatal problems, may be null.
     * @param javaResolvers    resolvers of the Java version that was read, used for item stacks, may be null.
     * @param bedrockResolvers resolvers of the Bedrock version being written, used for item stacks, may be null.
     */
    public EntityConversionPass(File inputDirectory, File outputDirectory, int threads, Consumer<String> log,
                                @Nullable JavaResolvers javaResolvers,
                                @Nullable BedrockResolvers bedrockResolvers) {
        this.inputDirectory = inputDirectory;
        this.outputDirectory = outputDirectory;
        this.threads = Math.max(1, threads);
        this.log = log;
        this.javaResolvers = javaResolvers;
        this.bedrockResolvers = bedrockResolvers;
    }

    /**
     * Run the pass.
     *
     * @param listener progress for this pass only (0..1), may be null.
     * @return the number of actors written.
     * @throws Exception when the output database cannot be opened at all.
     */
    public int run(ProgressListener listener) throws Exception {
        File database = new File(outputDirectory, "db");
        if (!database.isDirectory()) {
            // Converting to Java: this pass only writes Bedrock actor records for now.
            return 0;
        }

        List<Task> tasks = collectTasks();
        if (tasks.isEmpty()) {
            return 0;
        }

        AtomicInteger finished = new AtomicInteger();
        AtomicLong written = new AtomicLong();

        try (DB db = new Iq80DBFactory().open(database, options())) {
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(threads, tasks.size()), runnable -> {
                        Thread thread = new Thread(runnable, "entity-pass");
                        thread.setDaemon(true);
                        return thread;
                    });
            try {
                List<Future<?>> futures = new ArrayList<>(tasks.size());
                for (Task task : tasks) {
                    futures.add(pool.submit(() -> {
                        written.addAndGet(convertRegion(db, task));
                        int done = finished.incrementAndGet();
                        if (listener != null) {
                            listener.onProgress(done / (double) tasks.size(), "转换实体");
                        }
                    }));
                }
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                pool.shutdown();
                //noinspection ResultOfMethodCallIgnored
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }
        }

        return (int) written.get();
    }

    // ---------------------------------------------------------------------------------------------
    // Work collection
    // ---------------------------------------------------------------------------------------------

    private List<Task> collectTasks() {
        List<Task> tasks = new ArrayList<>();
        // 26.1 moved every dimension into dimensions/<namespace>/<name>; the older layouts keep the overworld at the
        // root and the other two in DIM-1 / DIM1. Both are tried, newest first, and the first that exists wins.
        addDimension(tasks, Dimension.OVERWORLD, "dimensions/minecraft/overworld", "");
        addDimension(tasks, Dimension.NETHER, "dimensions/minecraft/the_nether", "DIM-1");
        addDimension(tasks, Dimension.THE_END, "dimensions/minecraft/the_end", "DIM1");
        return tasks;
    }

    /**
     * @param tasks      the list to add to.
     * @param dimension  the dimension the files belong to.
     * @param candidates possible dimension directories relative to the world root, in preference order.
     */
    private void addDimension(List<Task> tasks, Dimension dimension, String... candidates) {
        for (String candidate : candidates) {
            File base = candidate.isEmpty() ? inputDirectory : new File(inputDirectory, candidate);
            // 1.17 moved entities into their own region folder; older worlds keep them in the chunk NBT.
            File folder = new File(base, "entities");
            if (!folder.isDirectory()) {
                folder = new File(base, "region");
            }
            File[] files = folder.listFiles((directory, name) -> REGION_FILE.matcher(name).matches());
            if (files == null || files.length == 0) {
                continue;
            }
            for (File file : files) {
                tasks.add(new Task(dimension, file));
            }
            return;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Conversion
    // ---------------------------------------------------------------------------------------------

    private int convertRegion(DB db, Task task) {
        Matcher matcher = REGION_FILE.matcher(task.file.getName());
        if (!matcher.matches()) {
            return 0;
        }
        int regionX = Integer.parseInt(matcher.group(1));
        int regionZ = Integer.parseInt(matcher.group(2));

        int written = 0;
        try (JavaRegionReader reader = new JavaRegionReader(task.file)) {
            for (int index = 0; index < COLUMNS; index++) {
                if (!reader.has(index)) {
                    continue;
                }
                CompoundTag column;
                try {
                    column = reader.read(index);
                } catch (Exception failure) {
                    report("读取区块失败 " + task.file.getName() + "#" + index + ": " + failure);
                    continue;
                }
                if (column == null) {
                    continue;
                }
                ListTag<CompoundTag, Map<String, Tag<?>>> entities = column.getList("Entities", CompoundTag.class, null);
                if (entities == null || entities.size() == 0) {
                    continue;
                }
                written += writeColumn(db, task.dimension, regionX, regionZ, index, entities);
            }
        } catch (Exception failure) {
            report("读取区域失败 " + task.file.getName() + ": " + failure);
        }
        return written;
    }

    private int writeColumn(DB db, Dimension dimension, int regionX, int regionZ, int index,
                            ListTag<CompoundTag, Map<String, Tag<?>>> entities) throws IOException {
        List<byte[]> keys = new ArrayList<>(entities.size());
        try (WriteBatch batch = db.createWriteBatch()) {
            for (CompoundTag entity : entities) {
                long uniqueId = nextUniqueId.getAndIncrement() | (0xFFFFFFFFL << 32);
                CompoundTag actor;
                try {
                    actor = BedrockActorBuilder.build(entity, uniqueId, javaResolvers, bedrockResolvers);
                } catch (Exception failure) {
                    report("构建实体失败: " + failure);
                    continue;
                }
                if (actor == null) {
                    continue;
                }
                byte[] key = storageKey(uniqueId);
                batch.put(LevelDBKey.key(LevelDBKey.ACTOR_PREFIX, key), Tag.writeBedrockNBT(actor));
                keys.add(key);
            }

            if (keys.isEmpty()) {
                return 0;
            }

            // digp is the per column index of actor keys; the game uses it to find the actors of a chunk.
            ByteArrayOutputStream digp = new ByteArrayOutputStream(keys.size() * 8);
            for (byte[] key : keys) {
                digp.write(key);
            }
            int columnX = (regionX << 5) | (index & 31);
            int columnZ = (regionZ << 5) | (index >> 5);
            batch.put(LevelDBKey.key(LevelDBKey.DIGP_PREFIX, dimension, new ChunkCoordPair(columnX, columnZ)),
                    digp.toByteArray());

            db.write(batch);
        }
        return keys.size();
    }

    /**
     * Mirror of the engine's {@code generateStorageKeyForEntity}: the top half is flipped and 0x100000000 is added,
     * so a key derived from a negative unique id stays unique and sorts sensibly.
     */
    private static byte[] storageKey(long uniqueId) {
        long storageKey = 0x100000000L + (uniqueId ^ 0xFFFFFFFF00000000L);
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(storageKey).array();
    }

    private static Options options() {
        Options options = new Options();
        options.compressionType(org.iq80.leveldb.CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        options.createIfMissing(false);
        return options;
    }

    private void report(String message) {
        if (log != null) {
            log.accept(message);
        }
    }

    /**
     * One region file and the dimension it belongs to.
     */
    private static final class Task {
        private final Dimension dimension;
        private final File file;

        private Task(Dimension dimension, File file) {
            this.dimension = dimension;
            this.file = file;
        }
    }
}
