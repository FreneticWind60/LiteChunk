package com.hivemc.chunker.conversion.encoding.bedrock.base.writer;

import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.identifier.BedrockBlockCompoundTag;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBChunkType;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBKey;
import com.hivemc.chunker.conversion.encoding.bedrock.util.PaletteUtil;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.VanillaBlockStates;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.types.Bool;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.types.LiquidLevel;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.scheduling.function.ThrowableConsumer;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.WriteBatch;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A writer for Bedrock chunks.
 */
public class BedrockChunkWriter {
    protected final BedrockResolvers resolvers;
    protected final DB database;
    protected final Converter converter;
    protected final Dimension dimension;
    protected final ChunkerColumn chunkerColumn;

    /**
     * Starting size for a serialised section: one palette layer is 4096 entries of at most 16 bits plus a palette of
     * block states, 16 KB covers the common cases without a single reallocation.
     */
    protected static final int PALETTE_OUTPUT_SIZE = 16 * 1024;

    /**
     * Create a new Bedrock Chunk Writer.
     *
     * @param converter     the converter instance.
     * @param resolvers     the resolvers being used.
     * @param database      the LevelDB database.
     * @param dimension     the dimension of the chunk.
     * @param chunkerColumn the column being written.
     */
    public BedrockChunkWriter(Converter converter, BedrockResolvers resolvers, DB database, Dimension dimension, ChunkerColumn chunkerColumn) {
        this.resolvers = resolvers;
        this.database = database;
        this.converter = converter;
        this.dimension = dimension;
        this.chunkerColumn = chunkerColumn;
    }

    /**
     * Write the chunk to LevelDB.
     *
     * @param chunkerChunk the chunk being written.
     * @param writeBatch   the batch of the column being written.
     * @throws Exception if it failed to write the chunk.
     */
    public void writeChunk(@Nullable ChunkerChunk chunkerChunk, WriteBatch writeBatch) throws Exception {
        // Don't write if it's empty and discard is enabled
        if (chunkerChunk == null || converter.shouldDiscardEmptyChunks() && chunkerChunk.isEmpty()) return;
        if (!isChunkHeightSupported(chunkerChunk.getY())) return;

        // Write the palette straight into the caller's batch, no task boundary in between
        writeBlockPalette(chunkerChunk, writeBatch);
    }


    /**
     * Check whether a chunk height is supported.
     *
     * @param chunkY the index Y of the chunk.
     * @return true if the chunk height is supported.
     */
    protected boolean isChunkHeightSupported(byte chunkY) {
        return chunkY >= 0 && chunkY < 16; // 0-15 is supported for every version (higher needs a newer version)
    }

    /**
     * Write the block palette data to LevelDB.
     *
     * @param chunk      the chunk being written.
     * @param writeBatch the batch of the column being written.
     * @throws Exception if it failed to write the palette.
     */
    protected void writeBlockPalette(ChunkerChunk chunk, WriteBatch writeBatch) throws Exception {
        // A ByteArrayDataOutput rather than a ByteArrayOutputStream: every writeByte/writeInt of a palette used to
        // go through the synchronised, bounds checked ByteArrayOutputStream.write(int) one byte at a time (and grew
        // its 32 byte buffer over and over). Writing 1024 words per section that was the single hottest method in a
        // profile of a 257 MB world. This one writes straight into a presized array.
        ByteArrayDataOutput dataOutput = ByteStreams.newDataOutput(PALETTE_OUTPUT_SIZE);
        Writer writer = Writer.toBedrockWriter(dataOutput);

        writer.writeByte((byte) 8); // Version 8 chunks, palette

        Palette<BedrockBlockCompoundTag> blockPalette = chunk.getPalette().map(resolvers::writeBlock);

        // Calculate waterlogged states
        boolean containsWaterlogged = blockPalette.containsValue(BedrockBlockCompoundTag::waterlogged);

        // Write size
        int layers = containsWaterlogged ? 2 : 1;
        writer.writeByte((byte) layers); // Size

        // Write each layer
        writeLayers(writer, layers, blockPalette);

        // Collect the byte array for leveldb
        byte[] bytes = dataOutput.toByteArray();

        writeChunkBytes(chunk, bytes, writeBatch);
    }

    /**
     * Write the block palette for the chunk.
     *
     * @param chunk      the chunk input.
     * @param bytes      the bytes for the block palette.
     * @param writeBatch the batch of the column being written.
     */
    protected void writeChunkBytes(ChunkerChunk chunk, byte[] bytes, WriteBatch writeBatch) {
        // Write the entry
        byte subChunkY = chunk.getY();
        writeBatch.put(LevelDBKey.key(dimension, chunkerColumn.getPosition(), subChunkY, LevelDBChunkType.SUB_CHUNK_PREFIX), bytes);
    }

    /**
     * Write the block layer data to a writer.
     *
     * @param writer       the output writer.
     * @param layers       the number of layers (1 if a liquid layer should be generated).
     * @param blockPalette the block palette to use.
     * @throws Exception if it failed to write the palette.
     */
    protected void writeLayers(Writer writer, int layers, Palette<BedrockBlockCompoundTag> blockPalette) throws Exception {
        ThrowableConsumer<BedrockBlockCompoundTag> writeKey = (entry) -> Tag.encodeNamed(writer, "", entry.compoundTag());
        // Loop through each layer
        for (int layer = 0; layer < layers; layer++) {
            if (layer == 0) {
                PaletteUtil.writeChunkPalette(writer, blockPalette, false, false, writeKey);
            } else {
                // Generate liquid layer
                Palette<BedrockBlockCompoundTag> liquidPalette = generateLiquidPalette(blockPalette);
                PaletteUtil.writeChunkPalette(writer, liquidPalette, false, false, writeKey);
            }
        }
    }

    /**
     * Generate a liquid palette from the block palette, looking for waterlogged states.
     *
     * @param blockPalette the block palette input.
     * @return a generated liquid palette.
     */
    protected Palette<BedrockBlockCompoundTag> generateLiquidPalette(Palette<BedrockBlockCompoundTag> blockPalette) {
        short[] values = new short[4096];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BedrockBlockCompoundTag block = blockPalette.get(x, y, z);
                    values[(y << 8) | (z << 4) | x] = (short) (block == null || !block.waterlogged() ? 0 : 1);
                }
            }
        }
        List<BedrockBlockCompoundTag> keys = List.of(
                resolvers.writeBlock(ChunkerBlockIdentifier.AIR),
                resolvers.writeBlock(new ChunkerBlockIdentifier(ChunkerVanillaBlockType.WATER, Map.of(
                        VanillaBlockStates.LIQUID_LEVEL, LiquidLevel._0,
                        VanillaBlockStates.FLOWING, Bool.FALSE
                )))
        );
        return new ShortBasedPalette<>(keys, values, 16);
    }
}
