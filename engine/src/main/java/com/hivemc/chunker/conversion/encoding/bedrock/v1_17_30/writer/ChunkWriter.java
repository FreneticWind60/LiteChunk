package com.hivemc.chunker.conversion.encoding.bedrock.v1_17_30.writer;

import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.identifier.BedrockBlockCompoundTag;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBChunkType;
import com.hivemc.chunker.conversion.encoding.bedrock.util.LevelDBKey;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.hivemc.chunker.nbt.io.Writer;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.WriteBatch;

public class ChunkWriter extends com.hivemc.chunker.conversion.encoding.bedrock.v1_17.writer.ChunkWriter {
    public ChunkWriter(Converter converter, BedrockResolvers resolvers, DB database, Dimension dimension, ChunkerColumn chunkerColumn) {
        super(converter, resolvers, database, dimension, chunkerColumn);
    }

    @Override
    protected void writeBlockPalette(ChunkerChunk chunk, WriteBatch writeBatch) throws Exception {
        // Presized ByteArrayDataOutput, see BedrockChunkWriter#writeBlockPalette: the stream based version spent a
        // fifth of the whole conversion growing and synchronising a 32 byte buffer one byte at a time.
        ByteArrayDataOutput dataOutput = ByteStreams.newDataOutput(PALETTE_OUTPUT_SIZE);
        Writer writer = Writer.toBedrockWriter(dataOutput);

        writer.writeByte((byte) 9); // Version 9 palette with extra index

        Palette<BedrockBlockCompoundTag> blockPalette = chunk.getPalette().map(resolvers::writeBlock);

        // Calculate waterlogged states
        boolean containsWaterlogged = blockPalette.containsValue(BedrockBlockCompoundTag::waterlogged);

        // Write size
        int layers = containsWaterlogged ? 2 : 1;
        writer.writeByte((byte) layers); // Size

        // Write index
        writer.writeByte(chunk.getY());

        // Write each layer
        writeLayers(writer, layers, blockPalette);

        // Collect the byte array for leveldb
        byte[] bytes = dataOutput.toByteArray();

        // Write the entry
        writeChunkBytes(chunk, bytes, writeBatch);
    }

    @Override
    protected void writeChunkBytes(ChunkerChunk chunk, byte[] bytes, WriteBatch writeBatch) {
        byte subChunkY = chunk.getY();
        writeBatch.put(LevelDBKey.key(dimension, chunkerColumn.getPosition(), subChunkY, LevelDBChunkType.SUB_CHUNK_PREFIX), bytes);
    }
}
