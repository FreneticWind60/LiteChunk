package com.hivemc.chunker.conversion.encoding.bedrock.util;

import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.EmptyPalette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.SingleValuePalette;
import com.hivemc.chunker.nbt.io.Writer;
import com.hivemc.chunker.scheduling.function.ThrowableConsumer;
import com.hivemc.chunker.scheduling.function.ThrowableFunction;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Util methods for writing various types of Bedrock palettes.
 */
public class PaletteUtil {
    /**
     * The supported bits per entry that a palette can contain.
     */
    public static final int[] BITS_PER_ENTRY = {1, 2, 3, 4, 5, 6, 8, 16};

    /**
     * The ID for the special empty palette.
     */
    public static final int EMPTY_PALETTE_BITS = 127;

    /**
     * Maps the Bedrock word order, {@code (x << 8) | (z << 4) | y} with y varying fastest, to the palette's flat
     * order, {@code (y << 8) | (z << 4) | x} with x varying fastest.
     * <p>
     * Packing and unpacking a section is a 16x16x16 transpose, so the same permutation of three bit fields shows up on
     * both paths. A section is 4096 blocks, which makes this table 16 KiB - small enough to stay in L2 while the
     * sections stream past - and it replaces three masks and two shifts per block. The conversion does that tens of
     * millions of times, and the write side alone was the largest single item in the profile.
     */
    private static final int[] BEDROCK_TO_FLAT = new int[4096];

    static {
        for (int i = 0; i < BEDROCK_TO_FLAT.length; i++) {
            BEDROCK_TO_FLAT[i] = ((i & 0xF) << 8) | (i & 0xF0) | ((i >> 8) & 0xF);
        }
    }

    /**
     * Read a chunk based palette (16x16x16).
     *
     * @param buffer           the input buffer.
     * @param readPaletteEntry a function to read a palette entry, providing whether runtime encoding is enabled as a
     *                         parameter
     * @param <T>              the type the palette reads.
     * @return the read palette.
     * @throws Exception if it failed to read.
     */
    public static <T> Palette<T> readChunkPalette(ByteBuffer buffer, ThrowableFunction<Boolean, T> readPaletteEntry) throws Exception {
        int paletteData = buffer.get() & 0xFF;
        int bitsPerEntry = paletteData >> 1;

        // Return the empty palette if this palette has no value
        if (bitsPerEntry == EMPTY_PALETTE_BITS) {
            return EmptyPalette.chunk();
        }

        // Calculate how many valuesPerWord
        int valuesPerWord = bitsPerEntry == 0 ? 0 : (int) Math.floor(32D / bitsPerEntry); // Word is 4 bytes
        int wordSize = valuesPerWord == 0 ? 0 : (int) Math.ceil(4096D / valuesPerWord);
        boolean runtimeEncoding = (paletteData & 0x1) != 0;

        // Record where we are as we'll read the keys first (skipping the words)
        int beforeWords = buffer.position();
        buffer.position(beforeWords + (wordSize * 4));

        // Read the palette before we read the blocks (as we can determine if it's useful to read the blocks)
        int size = bitsPerEntry == 0 ? 1 : buffer.getInt();

        // If by chance it's empty, return the empty palette
        if (size == 0) return EmptyPalette.chunk();

        // If it has a single value, then it is a single value palette
        if (size == 1) return SingleValuePalette.chunk(readPaletteEntry.apply(runtimeEncoding));

        // Collect all the keys
        List<T> keys = new ArrayList<>(size);
        for (int j = 0; j < size; j++) {
            keys.add(readPaletteEntry.apply(runtimeEncoding));
        }

        // Position the buffer back to before the words (values)
        int afterKeys = buffer.position();
        buffer.position(beforeWords);

        // Read values
        short[] values = new short[4096];
        int mask = (1 << bitsPerEntry) - 1;
        int i = 0;
        for (int word = 0; word < wordSize; word++) {
            int wordValue = buffer.getInt();
            int shift = 0;

            // Skip if over 4096 (extra padding on bitsPerEntry 3,5,6)
            for (int block = 0; block < valuesPerWord && i < 4096; block++) {
                // Bedrock packs values as (x << 8) | (z << 4) | y, the flat palette is (y << 8) | (z << 4) | x; the
                // permutation table does that transpose, and the shift is carried rather than recomputed from a
                // remainder of the cell index.
                values[BEDROCK_TO_FLAT[i]] = (short) ((wordValue >>> shift) & mask);
                shift += bitsPerEntry;
                i++;
            }
        }

        // Put the buffer back to the end
        buffer.position(afterKeys);

        return new ShortBasedPalette<>(keys, values, 16);
    }

    /**
     * Write a chunk based palette (16x16x16).
     *
     * @param writer            the writer to output to.
     * @param palette           the palette to write.
     * @param runtimeEncoding   whether runtime encoding is being used.
     * @param supportsEmpty     whether the empty palette ID is supported (127) otherwise it will write empty entries.
     * @param writePaletteEntry a function to write a palette entry.
     * @param <T>               the type the palette writes.
     * @throws Exception if it failed to write.
     */
    public static <T> void writeChunkPalette(Writer writer, Palette<T> palette, boolean runtimeEncoding, boolean supportsEmpty, ThrowableConsumer<T> writePaletteEntry) throws Exception {
        // If it supports empty write it
        boolean emptyPalette = palette.isEmpty();
        if (supportsEmpty && emptyPalette) {
            // Write palette info (127)
            writer.writeByte((byte) ((runtimeEncoding ? (byte) 1 : (byte) 0) | EMPTY_PALETTE_BITS << 1));
            return;
        }

        // Calculate bitsPerBlock,
        // https://github.com/mjungnickel18/papyruscs/blob/master/Maploader/World/World.cs#L251
        int index = 0;
        int bitsPerBlock = BITS_PER_ENTRY[index];
        if (!emptyPalette) {
            while (palette.getKeyCount() > 1 << bitsPerBlock) bitsPerBlock = BITS_PER_ENTRY[++index];
        }

        int maxValue = (1 << bitsPerBlock) - 1;
        int blocksPerWord = (int) Math.floor(32.0 / bitsPerBlock); // Word is 4 bytes
        int wordSize = (int) Math.ceil(4096.0 / blocksPerWord);

        // Packed words in the writer's byte order (little endian, matching DataOutputWriterLE), filled while packing so
        // the data never has to be copied out of an int[] afterwards.
        byte[] packed = new byte[wordSize * 4];
        int i = 0;

        // Only write the palette if it's not empty and the key count is more than 1
        if (!emptyPalette && palette.getKeyCount() > 1 && palette instanceof ShortBasedPalette<T> shortBasedPalette) {
            short[] values = shortBasedPalette.getValues();
            int[] permutation = BEDROCK_TO_FLAT;
            for (int wordIndex = 0; wordIndex < wordSize; wordIndex++) {
                // The word starts out empty, so the bits can simply be shifted in: the old shape masked the target
                // range out first, which is a no-op on a freshly allocated array, and multiplied to find the shift.
                int word = 0;
                int shift = 0;
                for (int blockIndex = 0; blockIndex < blocksPerWord && i < 4096; blockIndex++) {
                    word |= (values[permutation[i]] & maxValue) << shift;
                    shift += bitsPerBlock;
                    i++;
                }

                int offset = wordIndex << 2;
                packed[offset] = (byte) word;
                packed[offset + 1] = (byte) (word >>> 8);
                packed[offset + 2] = (byte) (word >>> 16);
                packed[offset + 3] = (byte) (word >>> 24);
            }
        }

        // Write palette info
        writer.writeByte((byte) ((runtimeEncoding ? (byte) 1 : (byte) 0) | bitsPerBlock << 1));

        writer.writeBytes(packed);

        // Write each palette entry
        int keyCount = palette.getKeyCount();
        writer.writeInt(keyCount);

        if (keyCount > 0) {
            for (int keyIndex = 0; keyIndex < keyCount; keyIndex++) {
                writePaletteEntry.accept(palette.getKey(keyIndex));
            }
        }
    }
}
