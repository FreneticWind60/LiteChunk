package com.hivemc.chunker.conversion.encoding.bedrock.base.writer;

import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.writer.ColumnWriter;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.bedrock.util.ColumnUtil;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBChunkType;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBKey;
import com.hivemc.chunker.conversion.handlers.pretransform.manager.PreTransformManager;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.column.heightmap.BedrockHeightMap;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.mapping.identifier.Identifier;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.WriteBatch;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A writer for Bedrock columns.
 *
 * <p>Everything a column produces is written synchronously, by the thread that was handed the column, into a single
 * {@link WriteBatch}. Two separate problems are fixed by that shape:</p>
 *
 * <ul>
 *   <li>LevelDB has one writer. One {@code put} per section meant ~24 round trips through the database mutex and the
 *   write-ahead log for every column - several million for a large world - and every one of them blocked the other
 *   workers. A column is now one {@code write()}.</li>
 *   <li>The work used to be split into five async tasks plus one async task per chunk plus one per chunk palette, all
 *   joined back together. Each of those tasks costs a queue insertion, two futures and three or four
 *   {@code synchronized} blocks in {@code TrackedTask}, while the useful work inside it is tens of microseconds. The
 *   overhead scales with the number of sections, and there are millions of sections.</li>
 * </ul>
 */
public class BedrockColumnWriter implements ColumnWriter {
    protected final BedrockWorldWriter parent;
    protected final Converter converter;
    protected final BedrockResolvers resolvers;
    protected final DB database;
    protected final Dimension dimension;

    /**
     * Create a new column writer.
     *
     * @param parent    the parent world writer.
     * @param converter the converter instance.
     * @param resolvers the resolvers being used.
     * @param database  the LevelDB database.
     * @param dimension the dimension of the column.
     */
    public BedrockColumnWriter(BedrockWorldWriter parent, Converter converter, BedrockResolvers resolvers, DB database, Dimension dimension) {
        this.parent = parent;
        this.converter = converter;
        this.resolvers = resolvers;
        this.database = database;
        this.dimension = dimension;
    }

    @Override
    public void writeColumn(ChunkerColumn chunkerColumn) throws Exception {
        // Run any preprocessing
        preProcessColumn(chunkerColumn);

        // Compact any chunk palettes to ensure unused values are removed from pre-processing
        chunkerColumn.getChunks().values().forEach(chunk -> {
            chunk.setPalette(chunk.getPalette().compact(ChunkerBlockIdentifier.AIR));
        });

        // One batch per column: one database lock, one WAL record, one memtable insert.
        try (WriteBatch writeBatch = database.createWriteBatch()) {
            // Each part is isolated the way the old per-part tasks were: a failure in one of them does not take the
            // rest of the column down with it.
            writeSection("Metadata", () -> writeMetadata(chunkerColumn, writeBatch));
            writeSection("HeightMap/Biomes", () -> writeHeightMapBiomes(chunkerColumn, writeBatch));
            writeSection("Entities", () -> writeEntities(chunkerColumn, writeBatch));
            writeSection("Block Entities", () -> writeBlockEntities(chunkerColumn, writeBatch));
            writeSection("Chunks", () -> writeChunks(chunkerColumn, writeBatch));

            database.write(writeBatch);
        }

        // Called after the column has been written
        postProcessColumn(chunkerColumn);
    }

    /**
     * Run one part of a column write, logging rather than propagating a failure.
     *
     * @param name  the name of the part, used in the log message.
     * @param write the work to run.
     */
    private void writeSection(String name, ThrowingRunnable write) {
        try {
            write.run();
        } catch (Exception e) {
            converter.logNonFatalException(new Exception("Failed to write " + name + " for column " + dimension, e));
        }
    }

    @Override
    public @Nullable PreTransformManager getPreTransformManager() {
        return resolvers.preTransformManager();
    }

    /**
     * Write any version metadata for the column.
     *
     * @param chunkerColumn the column being written.
     * @param writeBatch    the batch to add the entries to.
     * @throws Exception if it failed to write the metadata.
     */
    protected void writeMetadata(ChunkerColumn chunkerColumn, WriteBatch writeBatch) throws Exception {
        // Save Version (0x76)
        writeBatch.put(LevelDBKey.key(dimension, chunkerColumn.getPosition(), LevelDBChunkType.LEGACY_VERSION), new byte[]{7});
    }

    /**
     * Called before columns are written (sync).
     *
     * @param column the column being pre-processed.
     */
    protected void preProcessColumn(ChunkerColumn column) {
        // Create any block entities / entities which are based on blocks
        for (ChunkerChunk chunk : column.getChunks().values()) {
            resolvers.blockEntityResolver().generateBeforeWriteBlockEntities(column, chunk);
            resolvers.entityResolver().generateBeforeWriteEntities(column, chunk);
        }

        // Update the column from the block entities
        List<BlockEntity> blockEntities = column.getBlockEntities();
        for (int i = 0; i < blockEntities.size(); i++) {
            BlockEntity blockEntity = blockEntities.get(i);
            BlockEntity replacement = resolvers.blockEntityResolver().updateBeforeWrite(
                    column,
                    blockEntity.getX(),
                    blockEntity.getY(),
                    blockEntity.getZ(),
                    blockEntity
            );

            // Apply replacement if needed
            if (replacement != blockEntity) {
                blockEntities.set(i, replacement);
            }
        }

        // Update the column from the entities
        List<Entity> entities = column.getEntities();
        for (int i = 0; i < entities.size(); i++) {
            Entity entity = entities.get(i);
            Entity replacement = resolvers.entityResolver().updateBeforeWrite(
                    column,
                    entity
            );

            // Apply replacement if needed
            if (replacement != entity) {
                entities.set(i, replacement);
            }
        }

        // Run any block entity removal logic
        column.getBlockEntities().removeIf(blockEntity -> shouldRemoveBlockEntityBeforeWrite(column, blockEntity));

        // Run any entity removal logic
        column.getEntities().removeIf(entity -> resolvers.entityResolver().shouldRemoveBeforeWrite(
                column,
                entity
        ));
    }

    /**
     * Check whether a block entity should be removed before the data is written.
     *
     * @param column      the column writing the block entity.
     * @param blockEntity the block entity.
     * @return true if it should be removed.
     */
    protected boolean shouldRemoveBlockEntityBeforeWrite(ChunkerColumn column, BlockEntity blockEntity) {
        // Check the block entity doesn't resolve to air
        ChunkerBlockIdentifier blockIdentifier = column.getBlock(blockEntity.getX(), blockEntity.getY(), blockEntity.getZ());
        if (blockIdentifier.isAir())
            return true; // Don't write air block entities

        // Check the identifier after being resolved isn't air
        Optional<Identifier> result = resolvers.writeBlockIdentifier(blockIdentifier, false);
        if (result.isEmpty() || result.get().getIdentifier().equals("minecraft:air"))
            return true; // Don't write air block entities

        // Call the handlers for the specific block entity checks
        return resolvers.blockEntityResolver().shouldRemoveBeforeWrite(
                column,
                blockEntity.getX(),
                blockEntity.getY(),
                blockEntity.getZ(),
                blockEntity
        );
    }

    /**
     * Called after the column has been written.
     *
     * @param chunkerColumn the column that was written.
     */
    protected void postProcessColumn(ChunkerColumn chunkerColumn) {
    }

    /**
     * Generate a height map for the column.
     *
     * @param column the column.
     * @return the generated height map.
     */
    protected BedrockHeightMap generateHeightMap(ChunkerColumn column) {
        short[][] heightMap = new short[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                OptionalInt highest = ColumnUtil.getHighestLitOrSlabBlock(column, x, z);
                short value = (short) (highest.orElse(-1) + 1);
                heightMap[x][z] = value;
            }
        }
        return new BedrockHeightMap(heightMap);
    }

    /**
     * Write the height map and biomes for the column.
     *
     * @param column     the column being written.
     * @param writeBatch the batch to add the entries to.
     * @throws Exception if it failed to write the height map and biomes.
     */
    protected void writeHeightMapBiomes(ChunkerColumn column, WriteBatch writeBatch) throws Exception {
        // If the heightmap isn't bedrock specific, we need to regenerate it
        BedrockHeightMap heightMap;
        if (!(column.getHeightMap() instanceof BedrockHeightMap bedrockHeightMap)) {
            heightMap = generateHeightMap(column);
        } else {
            heightMap = bedrockHeightMap;
        }

        // Write Data2D into a presized array: 512 bytes of height map plus at most 256 bytes of biome ids.
        ByteArrayDataOutput dataOutput = ByteStreams.newDataOutput(1024);
        byte[] bytes;
        {
            Writer writer = Writer.toBedrockWriter(dataOutput);
            short[][] heightMapValues = heightMap.getHeightMap();

            // Write height map (LE Short)
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    writer.writeShort(heightMapValues[x][z]);
                }
            }

            // Write biome ids (byte) if present
            if (column.getBiomes() != null) {
                ChunkerBiome[] biomes = column.getBiomes().asColumn(resolvers.getFallbackBiome(dimension));
                for (ChunkerBiome chunkerBiome : biomes) {
                    writer.writeByte(resolvers.writeBiomeID(chunkerBiome, dimension));
                }
            } else {
                // Use the fallback biome
                ChunkerBiome fallbackBiome = resolvers.getFallbackBiome(dimension);
                for (int i = 0; i < 256; i++) {
                    writer.writeByte(resolvers.writeBiomeID(fallbackBiome, dimension));
                }
            }

            bytes = dataOutput.toByteArray();
        }

        // Save Data2D (0x2D) - Heightmap / Biome
        writeBatch.put(LevelDBKey.key(dimension, column.getPosition(), LevelDBChunkType.DATA_2D), bytes);
    }

    /**
     * Write the entities to LevelDB.
     *
     * @param column     the column being written.
     * @param writeBatch the batch to add the entries to.
     * @throws Exception if it failed to write the entities.
     */
    protected void writeEntities(ChunkerColumn column, WriteBatch writeBatch) throws Exception {
        // Write entities
        ByteArrayDataOutput dataOutput = ByteStreams.newDataOutput(4 * 1024);
        Writer entityWriter = Writer.toBedrockWriter(dataOutput);
        // Write each entity as a separate tag
        for (Entity entity : column.getEntities()) {
            try {
                // Process the tag
                CompoundTag tag = writeEntity(column, entity);
                if (tag != null) {
                    // Encode
                    Tag.encodeNamed(entityWriter, "", tag);
                }
            } catch (Exception e) {
                converter.logNonFatalException(new Exception("Failed to process Entity " + entity, e));
            }
        }

        // Write the byte array to the key
        writeBatch.put(LevelDBKey.key(dimension, column.getPosition(), LevelDBChunkType.ENTITY), dataOutput.toByteArray());
    }

    /**
     * Write an entity to a compound tag.
     *
     * @param chunkerColumn the column being written.
     * @param entity        the entity being written.
     * @return the compound tag that was read, otherwise null if it could not be read.
     */
    @Nullable
    protected CompoundTag writeEntity(ChunkerColumn chunkerColumn, Entity entity) {
        Optional<CompoundTag> compoundTag = resolvers.entityResolver().from(entity);
        if (compoundTag.isPresent()) {
            return compoundTag.get();
        } else {
            // Log as missing
            converter.logMissingMapping(Converter.MissingMappingType.ENTITY, String.valueOf(entity.getEntityType()));
            return null;
        }
    }

    /**
     * Write the block entities to LevelDB.
     *
     * @param column     the column being written.
     * @param writeBatch the batch to add the entries to.
     * @throws Exception if it failed to write the block entities.
     */
    protected void writeBlockEntities(ChunkerColumn column, WriteBatch writeBatch) throws Exception {
        // Write block entities
        ByteArrayDataOutput dataOutput = ByteStreams.newDataOutput(4 * 1024);
        Writer blockEntityWriter = Writer.toBedrockWriter(dataOutput);
        // Write each block entity as a separate tag
        for (BlockEntity blockEntity : column.getBlockEntities()) {
            try {
                // Process the tag
                CompoundTag tag = writeBlockEntity(column, blockEntity);
                if (tag != null) {
                    // Encode
                    Tag.encodeNamed(blockEntityWriter, "", tag);
                }
            } catch (Exception e) {
                converter.logNonFatalException(new Exception("Failed to process BlockEntity " + blockEntity, e));
            }
        }

        // Write the byte array to the key
        writeBatch.put(LevelDBKey.key(dimension, column.getPosition(), LevelDBChunkType.BLOCK_ENTITY), dataOutput.toByteArray());
    }

    /**
     * Write a block entity to a compound tag.
     *
     * @param chunkerColumn the column being written.
     * @param blockEntity   the block entity being written.
     * @return the compound tag that was read, otherwise null if it could not be read.
     */
    @Nullable
    protected CompoundTag writeBlockEntity(ChunkerColumn chunkerColumn, BlockEntity blockEntity) {
        Optional<CompoundTag> compoundTag = resolvers.blockEntityResolver().from(blockEntity);
        if (compoundTag.isPresent()) {
            return compoundTag.get();
        } else {
            // Log as missing
            converter.logMissingMapping(Converter.MissingMappingType.BLOCK_ENTITY, blockEntity.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Write all the chunks inside the column.
     *
     * @param column     the column being written.
     * @param writeBatch the batch to add the entries to.
     * @throws Exception if it failed to write a chunk.
     */
    protected void writeChunks(ChunkerColumn column, WriteBatch writeBatch) throws Exception {
        // Create the writer for the chunks
        BedrockChunkWriter chunkWriter = createChunkWriter(column);

        // Write each chunk, in this thread, into the same batch
        for (ChunkerChunk chunk : column.getChunks().values()) {
            chunkWriter.writeChunk(chunk, writeBatch);
        }
    }

    /**
     * Create a chunk writer for a column.
     *
     * @param column the column being written.
     * @return the newly created chunk writer.
     */
    public BedrockChunkWriter createChunkWriter(ChunkerColumn column) {
        return new BedrockChunkWriter(converter, resolvers, database, dimension, column);
    }

    /**
     * A runnable which can throw, so the section wrappers do not need a checked exception of their own.
     */
    @FunctionalInterface
    protected interface ThrowingRunnable {
        void run() throws Exception;
    }
}
