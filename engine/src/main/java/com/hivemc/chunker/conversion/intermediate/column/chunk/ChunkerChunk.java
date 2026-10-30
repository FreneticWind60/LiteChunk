package com.hivemc.chunker.conversion.intermediate.column.chunk;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.SingleValuePalette;
import org.jetbrains.annotations.Nullable;

/**
 * A chunk which is part of a ChunkerColumn.
 */
public class ChunkerChunk {
    /**
     * Length of a light array: 16x16x16 values, one byte each.
     */
    public static final int LIGHT_ARRAY_LENGTH = 16 * 16 * 16;

    private byte y;
    private Palette<ChunkerBlockIdentifier> palette = SingleValuePalette.chunk(ChunkerBlockIdentifier.AIR);
    private byte @Nullable [] blockLight;
    private byte @Nullable [] skyLight;

    /**
     * Build the index used by the flat light arrays.
     * <p>
     * The light of a chunk used to be {@code byte[16][16][16]}, which is 273 arrays (1 outer + 16 + 256) to hold
     * 4096 bytes: the object headers alone doubled the cost of the data, and a {@code [x][y][z]} walk jumped to a
     * different array per block. It is now a single flat array in {@code (y, z, x)} order with x varying fastest,
     * the same order the formats read and write it in.
     *
     * @param x the x position (0-15).
     * @param y the y position (0-15).
     * @param z the z position (0-15).
     * @return the index into the flat light array.
     */
    public static int lightIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /**
     * Create a new chunk.
     *
     * @param y the position of the chunk.
     */
    public ChunkerChunk(byte y) {
        this.y = y;
    }

    /**
     * Get the Y position of this chunk in the column.
     *
     * @return the Y position.
     */
    public byte getY() {
        return y;
    }

    /**
     * Set the Y position of this chunk in the column.
     *
     * @param y the Y position.
     */
    public void setY(byte y) {
        this.y = y;
    }

    /**
     * Get the palette used for this chunk.
     *
     * @return the palette used for this chunk, empty if there is no blocks.
     */
    public Palette<ChunkerBlockIdentifier> getPalette() {
        return palette;
    }

    /**
     * Set the palette used for this chunk.
     *
     * @param palette the palette used for this chunk, null if there is no blocks.
     */
    public void setPalette(Palette<ChunkerBlockIdentifier> palette) {
        this.palette = palette;
    }

    /**
     * Get the block light for the chunk.
     *
     * @return a flat {@link #LIGHT_ARRAY_LENGTH} byte array indexed by {@link #lightIndex(int, int, int)}, or null if
     * not present.
     */
    public byte @Nullable [] getBlockLight() {
        return blockLight;
    }

    /**
     * Set the block light for the chunk.
     *
     * @param blockLight a flat {@link #LIGHT_ARRAY_LENGTH} byte array indexed by
     *                   {@link #lightIndex(int, int, int)}, or null if not present.
     */
    public void setBlockLight(byte @Nullable [] blockLight) {
        this.blockLight = blockLight;
    }

    /**
     * Get the skylight for the chunk.
     *
     * @return a flat {@link #LIGHT_ARRAY_LENGTH} byte array indexed by {@link #lightIndex(int, int, int)}, or null if
     * not present.
     */
    public byte @Nullable [] getSkyLight() {
        return skyLight;
    }

    /**
     * Set the skylight for the chunk.
     *
     * @param skyLight a flat {@link #LIGHT_ARRAY_LENGTH} byte array indexed by
     *                 {@link #lightIndex(int, int, int)}, or null if not present.
     */
    public void setSkyLight(byte @Nullable [] skyLight) {
        this.skyLight = skyLight;
    }

    /**
     * Check whether this chunk is empty.
     *
     * @return true if the block only contains air.
     */
    public boolean isEmpty() {
        if (palette.isEmpty()) return true;

        // If the palette contains a not air block, it's not empty
        return !palette.containsKey(block -> !block.isAir());
    }
}
