package com.hivemc.chunker.conversion.encoding.bedrock.util;

import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.vanilla.VanillaBlockStates;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import it.unimi.dsi.fastutil.bytes.Byte2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectSortedSet;

import java.util.OptionalInt;

/**
 * Utilities for Chunker Columns for Bedrock.
 */
public class ColumnUtil {
    /**
     * Cache of whether a block type can carry the slab state, indexed by enum ordinal.
     * <p>
     * 0 = not computed, 1 = yes, 2 = no. {@code type.getStates()} is a Guava {@code Sets.union} view, so asking it
     * whether it contains the slab state is two hash lookups; the height map used to ask that once per non-air block,
     * which showed up as 4% of the conversion's self time. A type's states are immutable, so the answer is a constant
     * per ordinal. Written without locking because every thread computes the same value.
     */
    private static final byte[] SLAB_STATE_CAPABLE = new byte[ChunkerVanillaBlockType.values().length];

    /**
     * Check whether a block type can carry the slab state, caching the answer per type.
     *
     * @param type the block type.
     * @return true if the type has a slab state.
     */
    private static boolean hasSlabState(ChunkerBlockType type) {
        // Only the vanilla enum can be indexed by ordinal; anything else (a custom block type) is answered directly
        if (!(type instanceof ChunkerVanillaBlockType vanilla)) {
            return type.getStates().contains(VanillaBlockStates.SLAB_TYPE);
        }

        int ordinal = vanilla.ordinal();
        byte cached = SLAB_STATE_CAPABLE[ordinal];
        if (cached != 0) return cached == 1;

        boolean has = vanilla.getStates().contains(VanillaBlockStates.SLAB_TYPE);
        SLAB_STATE_CAPABLE[ordinal] = has ? (byte) 1 : (byte) 2;
        return has;
    }

    /**
     * Generate the legacy height map for a whole column in a single pass.
     * <p>
     * The previous shape called {@link #getHighestLitOrSlabBlock(ChunkerColumn, int, int)} 256 times per column, and
     * each call re-walked the sections from the top: it allocated a sorted-set iterator, asked every section whether
     * it was empty (which scans the palette keys with {@code equals}) and re-derived the slab capability of every
     * non-air block. In a profile of a 257 MB conversion that cluster was the largest single self-time consumer
     * (palette {@code containsKey} alone 15.9%, the Guava union 4.0%).
     * <p>
     * This walks the sections once, from the top down, and fills every unresolved cell as it goes: sections are
     * tested for emptiness once instead of once per cell, and the scan stops as soon as all 256 cells are resolved.
     * <p>
     * The cells which are still waiting are held in a compacted list instead of being re-tested through a
     * {@code resolved[]} flag. Air dominates the top of a column: the first section that is not empty clears most of
     * the list within a few layers, and from then on the walk only visits what is left. The previous shape tested all
     * 256 cells at every one of the 16 layers no matter how many were already done.
     *
     * @param column the column to generate the height map for.
     * @return a 16x16 height map where 0 means no block was found, matching {@code (y of the block) + 1}.
     */
    public static short[][] generateHeightMap(ChunkerColumn column) {
        short[][] heightMap = new short[16][16];

        ObjectSortedSet<Byte2ObjectMap.Entry<ChunkerChunk>> chunkSet = column.getChunks().byte2ObjectEntrySet();
        if (chunkSet.isEmpty()) return heightMap; // Empty column

        // A block at absolute y = -1 produces 0 through the +1, which is the same value as "nothing found", so the
        // resolved cells have to be tracked separately rather than by testing the height map values. Cells are held
        // as (z << 4) | x so the flat light index is one shift away.
        short[] pending = new short[16 * 16];
        for (short cell = 0; cell < 16 * 16; cell++) {
            pending[cell] = cell;
        }
        int pendingCount = pending.length;

        ObjectBidirectionalIterator<Byte2ObjectMap.Entry<ChunkerChunk>> iterator = chunkSet.iterator(chunkSet.last());
        while (iterator.hasPrevious() && pendingCount > 0) {
            ChunkerChunk chunk = iterator.previous().getValue();
            if (chunk.isEmpty()) continue;

            Palette<ChunkerBlockIdentifier> palette = chunk.getPalette();
            byte[] blockLight = chunk.getBlockLight();
            byte[] skyLight = chunk.getSkyLight();
            int chunkBase = chunk.getY() << 4;

            for (int y = 15; y >= 0 && pendingCount > 0; y--) {
                // The light arrays are flat in (y, z, x) order with x varying fastest
                int rowBase = y << 8;
                int index = 0;
                while (index < pendingCount) {
                    int cell = pending[index];
                    int x = cell & 0xF;
                    int z = cell >> 4;

                    ChunkerBlockIdentifier identifier = palette.get(x, y, z, ChunkerBlockIdentifier.AIR);
                    if (identifier.isAir()) {
                        index++;
                        continue;
                    }

                    int light = (blockLight == null ? 0 : blockLight[rowBase | cell])
                            + (skyLight == null ? 0 : skyLight[rowBase | cell]);
                    if (light == 0 && !hasSlabState(identifier.getType())) {
                        index++;
                        continue;
                    }

                    heightMap[x][z] = (short) ((chunkBase | y) + 1);

                    // Swap-remove: the order cells are visited in has no effect on the result
                    pending[index] = pending[--pendingCount];
                }
            }
        }

        return heightMap;
    }

    /**
     * Get the highest block which has light or is a slab.
     *
     * @param column the input column.
     * @param x      the local position X to look at.
     * @param z      the local position Z to look at.
     * @return an integer if a block was found of the Y otherwise empty.
     */
    public static OptionalInt getHighestLitOrSlabBlock(ChunkerColumn column, int x, int z) {
        // Start from the top
        ObjectSortedSet<Byte2ObjectMap.Entry<ChunkerChunk>> chunkSet = column.getChunks().byte2ObjectEntrySet();
        if (chunkSet.isEmpty()) return OptionalInt.empty(); // Empty chunk

        // Iterate through the chunk
        ObjectBidirectionalIterator<Byte2ObjectMap.Entry<ChunkerChunk>> iterator = chunkSet.iterator(chunkSet.last());
        while (iterator.hasPrevious()) {
            ChunkerChunk chunk = iterator.previous().getValue();
            if (!chunk.isEmpty()) {
                byte[] blockLight = chunk.getBlockLight();
                byte[] skyLight = chunk.getSkyLight();
                // The light arrays are flat in (y, z, x) order, so only the y part of the index changes as the
                // column is walked downwards.
                int lightIndexBase = (z << 4) | x;
                int y = 15;
                while (y >= 0) {
                    ChunkerBlockIdentifier identifier = chunk.getPalette().get(x, y, z, ChunkerBlockIdentifier.AIR);
                    if (!identifier.isAir()) {
                        // Fetch the light or if it's a slab
                        int lightIndex = (y << 8) | lightIndexBase;
                        int light = (blockLight == null ? 0 : blockLight[lightIndex]) + (skyLight == null ? 0 : skyLight[lightIndex]);
                        boolean litOrSlab = (light != 0 || identifier.getType().getStates().contains(VanillaBlockStates.SLAB_TYPE));
                        if (litOrSlab) {
                            // Return this height
                            return OptionalInt.of((chunk.getY() << 4) | y);
                        }
                    }
                    y--;
                }
            }
        }

        return OptionalInt.empty(); // Empty chunk
    }
}
