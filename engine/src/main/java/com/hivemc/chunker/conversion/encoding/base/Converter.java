package com.hivemc.chunker.conversion.encoding.base;

import com.google.common.base.CaseFormat;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.conversion.intermediate.world.DimensionRegistry;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A converter is an interface for converter settings / feeding information back to the user.
 */
public interface Converter {
    /**
     * Whether compaction is enabled for LevelDB. Bedrock only.
     *
     * @return true if compaction should happen after conversion.
     */
    boolean shouldLevelDBCompaction();

    /**
     * Whether entity/block entity types without a handler should still be carried through conversion.
     * <p>
     * Upstream ties this to {@link #shouldAllowNBTCopying()}, which is only true when the input and output editions
     * match. Cross edition that means every mob is dropped at read time: Chunker only implements handlers for
     * paintings, item frames and a couple of oddities, so a plain cow has no handler and is reported as a missing
     * mapping. Writing it as an "unknown" entity instead keeps the identifier, position and enough of a definition
     * list for Bedrock to spawn it with its default components, which is far more useful than silently deleting it.
     *
     * @return true if unknown entity types should be preserved rather than dropped.
     */
    default boolean shouldPreserveUnknownEntities() {
        return shouldAllowNBTCopying();
    }

    /**
     * Whether the in-game maps should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessMaps();

    /**
     * Whether items should be processed otherwise air is used.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessItems();

    /**
     * Whether entities should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessEntities();

    /**
     * Whether block entities should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessBlockEntities();

    /**
     * Whether the loot tables should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessLootTables();

    /**
     * Whether biomes should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessBiomes();

    /**
     * Whether the height-map should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessHeightMap();

    /**
     * Whether pre-transform should be run. Pre-Transform waits for required neighbouring columns before processing
     * them to infer missing information.
     *
     * @return true if pre-transform is enabled
     */
    boolean shouldProcessColumnPreTransform();

    /**
     * How many regions a reader may keep in flight at once.
     * <p>
     * A region holds up to 32x32 chunk columns and their converted output stays in memory until the region is
     * flushed, so this is the single biggest lever on peak heap for large worlds. Readers that process regions in one
     * unbounded batch defeat the purpose: every region piles up before any of them is released.
     *
     * @return the maximum number of regions in flight, at least 1.
     */
    default int getInFlightRegions() {
        return 4;
    }

    /**
     * How many chunk columns a reader may have in flight at once.
     * <p>
     * A region holds up to 1024 columns and a column is by far the largest intermediate object in the pipeline: it
     * carries every section's block states for the full 384 block height. Readers that submit a whole region at once
     * queue a thousand of them and the heap goes before the queue drains, which is why threading this up makes the
     * problem worse rather than better.
     *
     * @return the maximum number of columns in flight, at least 1.
     */
    default int getInFlightColumns() {
        return 128;
    }

    /**
     * Size of the LevelDB write buffer used when writing a Bedrock world.
     * <p>
     * This is a pure in-heap allocation: upstream sets it to 400 MB, which is fine on a desktop JVM but on a phone
     * it is most of the heap before a single column has been converted. A smaller buffer just means the memtable is
     * flushed to an SST file more often, which costs some throughput but keeps the process alive.
     *
     * @return the write buffer size in bytes.
     */
    default int getLevelDBWriteBufferSize() {
        return 400 * 1024 * 1024;
    }

    /**
     * Declare how many regions the read phase is about to process, enabling a progress estimate based on completed
     * regions.
     * <p>
     * The task tree's own weighting breaks down once reading is batched: the queued batches carry the weight of work
     * that has not started, so the reported number sits at one value for a long stretch and then jumps. Region counts
     * are known up front and only ever go up, which makes them a far better signal for a progress bar.
     *
     * @param total the number of regions that will be processed, 0 disables the estimate.
     */
    default void setRegionProgressTotal(int total) {
    }

    /**
     * Record that one region finished reading and writing.
     */
    default void regionProcessed() {
    }

    /**
     * Whether lighting should be processed.
     *
     * @return true if it should be processed.
     */
    boolean shouldProcessLighting();

    /**
     * Whether a dimension should be processed.
     *
     * @param dimension the dimension.
     * @return true if the dimension should be processed.
     */
    boolean shouldProcessDimension(Dimension dimension);

    /**
     * Whether a region inside a world should be processed.
     *
     * @param dimension  the dimension.
     * @param regionPair the co-ordinates of the region.
     * @return true if the region should be processed.
     */
    boolean shouldProcessRegion(Dimension dimension, RegionCoordPair regionPair);

    /**
     * Whether a column inside a world should be processed.
     *
     * @param dimension  the dimension.
     * @param columnPair the co-ordinates of the column.
     * @return true if the column should be processed.
     */
    boolean shouldProcessColumn(Dimension dimension, ChunkCoordPair columnPair);

    /**
     * Whether NBT is allowed to be copied from the input to output (must be same format and version).
     *
     * @return true if copying is allowed.
     */
    boolean shouldAllowNBTCopying();

    /**
     * Whether custom identifiers should be processed.
     *
     * @return true if custom identifiers should be used.
     */
    boolean shouldAllowCustomIdentifiers();

    /**
     * Get the block / item user mappings.
     *
     * @return the mappings or null if none present.
     */
    @Nullable
    MappingsFileResolvers getBlockMappings();

    /**
     * Get the dimension register, by default populated with Vanilla dimensions
     *
     * @return the dimension registry
     */
    DimensionRegistry getDimensionRegistry();

    /**
     * Log a non-fatal exception.
     *
     * @param throwable the exception.
     */
    default void logNonFatalException(Throwable throwable) {
        throwable.printStackTrace();
    }

    /**
     * Log a missing mapping.
     *
     * @param type       the type of the missing mapping.
     * @param identifier the identifier as a string (can be a number, can be any format).
     */
    default void logMissingMapping(MissingMappingType type, String identifier) {
        System.err.println("Missing " + type.getName().replace('_', ' ') + " mapping for " + identifier);
    }

    /**
     * Whether empty chunks should be discarded instead of written.
     *
     * @return true if chunks should be removed instead of written.
     */
    boolean shouldDiscardEmptyChunks();

    /**
     * Whether biome blending should be prevented. Java only.
     *
     * @return true if biome blending should be prevented.
     */
    boolean shouldPreventYBiomeBlending();

    /**
     * Get the dimension mapping given an input.
     *
     * @param dimension the input dimension.
     * @return the new dimension or absent if the dimension should be removed.
     */
    Optional<Dimension> getNewDimension(Dimension dimension);

    /**
     * Get the biome mapping given an input.
     *
     * @param biome the input biome.
     * @return the new biome.
     */
    ChunkerBiome getNewBiome(ChunkerBiome biome);

    /**
     * Get the main level data.
     *
     * @return the level or empty if the level hasn't been processed yet.
     */
    Optional<ChunkerLevel> level();

    /**
     * The type of missing mapping.
     */
    enum MissingMappingType {
        BLOCK,
        ITEM,
        POTION,
        EFFECT,
        BIOME,
        ENTITY,
        ENTITY_TYPE,
        BLOCK_ENTITY,
        HORN,
        PAINTING,
        ENCHANTMENT,
        TRIM_MATERIAL,
        TRIM_PATTERN;

        private final String name;

        /**
         * Create a new missing mapping type.
         */
        MissingMappingType() {
            name = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_UNDERSCORE, name());
        }

        /**
         * Get the name of the missing mapping in lower underscore case.
         *
         * @return the name.
         */
        public String getName() {
            return name;
        }
    }
}
