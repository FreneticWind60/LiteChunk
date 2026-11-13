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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Share of this pass spent collecting leash knots before any actor is written. */
    private static final double INDEX_SHARE = 0.30;

    private final File inputDirectory;
    private final File outputDirectory;
    private final int threads;
    private final Consumer<String> log;
    private final JavaResolvers javaResolvers;
    private final BedrockResolvers bedrockResolvers;

    private final AtomicLong nextUniqueId = new AtomicLong();

    /**
     * Where every leash knot of the world stands, by the id its UUID derives to.
     * <p>
     * A lead tied to a fence points at a knot entity in the chunk the fence is in, while the animal is usually in the
     * neighbouring chunk. One pass over the whole world fills this in before any column is written, so the animal's
     * column can turn the reference into coordinates no matter which worker gets it first.
     */
    private final Map<Long, long[]> leashKnots = new ConcurrentHashMap<>();

    /** Whether to look for leash knot entities before writing; false for worlds that store the post themselves. */
    private final boolean indexKnots;

    /**
     * Knots that still have to be written, by the column that owns them.
     * <p>
     * A knot belongs to the chunk the fence stands in, which is usually a chunk the animal is not in - and often a
     * chunk with no entities at all, so its digp is never written by the normal pass. They are collected here and
     * merged in at the end, once every column has been written and there is nobody left to race with.
     */
    private final Map<Dimension, Map<ChunkCoordPair, List<long[]>>> pendingKnots = new ConcurrentHashMap<>();

    /**
     * @param inputDirectory   the Java world that was converted.
     * @param outputDirectory  the Bedrock world that was produced.
     * @param threads          worker count, clamped to at least 1.
     * @param log              receives non-fatal problems, may be null.
     * @param javaResolvers    resolvers of the Java version that was read, used for item stacks, may be null.
     * @param bedrockResolvers resolvers of the Bedrock version being written, used for item stacks, may be null.
     * @param indexKnots       true when the world may contain leash knot entities, which have to be located before any
     *                         animal can be written. Only worlds from before 1.21 do: that version stores the post on
     *                         the animal itself instead.
     */
    public EntityConversionPass(File inputDirectory, File outputDirectory, int threads, Consumer<String> log,
                                @Nullable JavaResolvers javaResolvers,
                                @Nullable BedrockResolvers bedrockResolvers,
                                boolean indexKnots) {
        this.inputDirectory = inputDirectory;
        this.outputDirectory = outputDirectory;
        this.threads = Math.max(1, threads);
        this.log = log;
        this.javaResolvers = javaResolvers;
        this.bedrockResolvers = bedrockResolvers;
        this.indexKnots = indexKnots;
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
            // The knots first: an animal tied to a fence in an older world cannot be written until we know where the
            // knot it points at stands, and the knot is almost never in the same region file. This pass only reads,
            // and the second pass then hits the page cache, so the extra cost is one more parse of the entity files
            // rather than one more read from disk. 1.21 and later store the post on the animal, so this is skipped.
            final double share = indexKnots ? INDEX_SHARE : 0;
            if (indexKnots) {
                indexLeashKnots(tasks, listener);
            }

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
                            listener.onProgress(share + (1 - share) * done / (double) tasks.size(), "转换实体");
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

            written.addAndGet(writePendingKnots(db));
        }

        return (int) written.get();
    }

    // ---------------------------------------------------------------------------------------------
    // Leash knots
    // ---------------------------------------------------------------------------------------------

    /**
     * Collect the position of every leash knot in the world.
     *
     * @param tasks    the region files to read.
     * @param listener progress for the first {@link #INDEX_SHARE} of this pass, may be null.
     * @throws Exception when a worker is interrupted.
     */
    private void indexLeashKnots(List<Task> tasks, ProgressListener listener) throws Exception {
        AtomicInteger finished = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(threads, tasks.size()), runnable -> {
                    Thread thread = new Thread(runnable, "entity-knots");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Task task : tasks) {
                futures.add(pool.submit(() -> {
                    indexRegion(task);
                    int done = finished.incrementAndGet();
                    if (listener != null) {
                        listener.onProgress(INDEX_SHARE * done / (double) tasks.size(), "读取拴绳");
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

    /**
     * @param task one region file.
     */
    private void indexRegion(Task task) {
        try (JavaRegionReader reader = new JavaRegionReader(task.file)) {
            for (int index = 0; index < COLUMNS; index++) {
                if (!reader.has(index)) {
                    continue;
                }
                CompoundTag column;
                try {
                    column = reader.read(index);
                } catch (Exception failure) {
                    continue;
                }
                if (column == null) {
                    continue;
                }
                ListTag<CompoundTag, Map<String, Tag<?>>> entities =
                        column.getList("Entities", CompoundTag.class, null);
                if (entities == null || entities.size() == 0) {
                    continue;
                }
                for (CompoundTag entity : entities) {
                    if (!EntityLinks.isLeashKnot(entity)) {
                        continue;
                    }
                    long id = uniqueIdFromUuid(entity.getIntArray("UUID", null));
                    long[] position = EntityLinks.position(entity);
                    if (id != 0L && position != null) {
                        leashKnots.put(id, position);
                    }
                }
            }
        } catch (Exception failure) {
            report("读取拴绳位置失败 " + task.file.getName() + ": " + failure);
        }
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
                writeActor(batch, keys, entity, 0L, dimension);
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
     * Write one actor, and the entities nested inside it.
     * <p>
     * Java keeps a rider inside its vehicle's {@code Passengers} list, so a pig in a boat is not a top level entity at
     * all. Bedrock wants it as an actor of its own that links back to the vehicle, which is why this recurses and adds
     * every passenger to the column's key list as well.
     *
     * @param batch     the write batch for this column.
     * @param keys      the actor keys of this column, appended to.
     * @param entity    the Java entity tag.
     * @param vehicleId unique id of the actor this one rides, or 0 when it is not a passenger.
     */
    private void writeActor(WriteBatch batch, List<byte[]> keys, CompoundTag entity, long vehicleId,
                            Dimension dimension) throws IOException {
        // Bedrock has no actor for the knot a lead is tied to - the post is stored as coordinates on the animal - so
        // writing one would leave an invisible, undefined entity standing in the world.
        if (EntityLinks.isLeashKnot(entity)) {
            return;
        }

        // Item frames are blocks on Bedrock, not actors. The column writer places them (the frame entity is read into
        // the column for exactly that reason), so writing an actor here would add a second, unknown entity on top.
        String javaId = entity.getString("id", "");
        if ("minecraft:item_frame".equals(javaId) || "minecraft:glow_item_frame".equals(javaId)) {
            return;
        }

        long uniqueId = uniqueIdOf(entity);
        CompoundTag actor;
        try {
            actor = BedrockActorBuilder.build(entity, uniqueId, javaResolvers, bedrockResolvers);
        } catch (Exception failure) {
            report("构建实体失败: " + failure);
            return;
        }
        if (actor == null) {
            return;
        }

        ListTag<CompoundTag, Map<String, Tag<?>>> passengers =
                entity.getList("Passengers", CompoundTag.class, null);

        List<Long> riderIds = null;
        if (passengers != null && passengers.size() > 0) {
            riderIds = new ArrayList<>(passengers.size());
            for (CompoundTag passenger : passengers) {
                riderIds.add(uniqueIdOf(passenger));
            }
        }
        // Both halves of every relationship, in one tag: an actor can be a passenger and carry passengers at the same
        // time, and writing them separately replaced the first list with the second.
        EntityLinks.link(actor, vehicleId, riderIds);

        EntityLinks.Leash leash = EntityLinks.leash(entity, this::uniqueIdFromUuid);
        if (leash != null) {
            long[] block = leash.isBlock()
                    ? new long[]{leash.x(), leash.y(), leash.z()}
                    // An older world (or a lead held by a mob) points at a knot entity in the chunk the fence stands
                    // in, which is rarely the animal's own chunk; that position came from the whole world pass.
                    : leashKnots.get(leash.id());
            if (block != null) {
                // Bedrock keeps a knot actor of its own and the animal only says which one; anything else and the
                // lead simply does not render.
                long knotId = EntityLinks.knotId(block[0], block[1], block[2]);
                EntityLinks.leashed(actor, knotId);
                registerKnot(dimension, block, knotId);
            } else {
                EntityLinks.leashed(actor, leash.id());
            }
        }

        byte[] key = storageKey(uniqueId);
        batch.put(LevelDBKey.key(LevelDBKey.ACTOR_PREFIX, key), Tag.writeBedrockNBT(actor));
        keys.add(key);

        if (passengers != null) {
            for (CompoundTag passenger : passengers) {
                writeActor(batch, keys, passenger, uniqueId, dimension);
            }
        }
    }

    /**
     * Remember that a knot has to exist at this block.
     *
     * @param dimension the dimension the fence is in.
     * @param block     the block the lead is tied to.
     * @param knotId    the unique id the animal already points at.
     */
    private void registerKnot(Dimension dimension, long[] block, long knotId) {
        ChunkCoordPair column = new ChunkCoordPair((int) (block[0] >> 4), (int) (block[2] >> 4));
        pendingKnots.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(column, key -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(new long[]{knotId, block[0], block[1], block[2]});
    }

    /**
     * Write every knot collected during the pass, merging its key into the digp of the column it stands in.
     *
     * @param db the output database.
     * @return the number of actors written.
     * @throws IOException when a knot cannot be written.
     */
    private int writePendingKnots(DB db) throws IOException {
        int written = 0;
        for (Map.Entry<Dimension, Map<ChunkCoordPair, List<long[]>>> dimension : pendingKnots.entrySet()) {
            for (Map.Entry<ChunkCoordPair, List<long[]>> column : dimension.getValue().entrySet()) {
                byte[] digpKey = LevelDBKey.key(LevelDBKey.DIGP_PREFIX, dimension.getKey(), column.getKey());
                ByteArrayOutputStream digp = new ByteArrayOutputStream();
                byte[] existing = db.get(digpKey);
                if (existing != null) {
                    digp.write(existing);
                }
                Set<Long> done = new HashSet<>();
                for (long[] knot : column.getValue()) {
                    // Two animals tied to the same fence share one knot; the second one must not write it twice.
                    if (!done.add(knot[0])) {
                        continue;
                    }
                    byte[] key = storageKey(knot[0]);
                    digp.write(key);
                    db.put(LevelDBKey.key(LevelDBKey.ACTOR_PREFIX, key),
                            Tag.writeBedrockNBT(EntityLinks.knot(knot[0], knot[1], knot[2], knot[3])));
                    written++;
                }
                db.put(digpKey, digp.toByteArray());
            }
        }
        return written;
    }

    /**
     * The id an actor is written with.
     * <p>
     * Derived from the entity's UUID rather than handed out by a counter, because links between actors have to be
     * resolvable from either end: the vehicle and its rider are usually in different region files, and those are
     * converted in parallel, so a shared counter would make the reference depend on which thread got there first.
     * <p>
     * The visible cost is that the ids no longer look like the game's own (which are {@code 0xFFFFFFFF00000000} plus a
     * counter). Nothing reads them but the storage key function, which is a bijection over any {@code long}.
     *
     * @param entity the Java entity tag.
     * @return a stable id, never 0.
     */
    private long uniqueIdOf(CompoundTag entity) {
        long derived = uniqueIdFromUuid(entity.getIntArray("UUID", null));
        return derived != 0L ? derived : nextUniqueId.getAndIncrement() | (0xFFFFFFFFL << 32);
    }

    /**
     * @param uuid the four {@code int}s of a Java {@code UUID:[I;a,b,c,d]} tag, may be null.
     * @return a 64 bit id derived from the 128 bit UUID, or 0 when there is no UUID to derive it from.
     */
    private long uniqueIdFromUuid(@Nullable int[] uuid) {
        if (uuid == null || uuid.length < 4) {
            return 0L;
        }
        long high = ((long) uuid[0] << 32) | (uuid[1] & 0xFFFFFFFFL);
        long low = ((long) uuid[2] << 32) | (uuid[3] & 0xFFFFFFFFL);
        // A 128 bit input folded into 64 bits and run through the splitmix64 finaliser: a collision needs two UUIDs
        // that differ in both halves in exactly the way the fold cancels out, which no real world will produce.
        long mixed = mix(high ^ mix(low));
        return mixed == 0L ? 1L : mixed;
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
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
