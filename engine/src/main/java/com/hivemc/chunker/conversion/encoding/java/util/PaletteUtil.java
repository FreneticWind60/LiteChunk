package com.hivemc.chunker.conversion.encoding.java.util;

import org.jetbrains.annotations.Nullable;

/**
 * Util methods for writing various types of Java palettes.
 */
public class PaletteUtil {

    /**
     * The minimum amount of bits for a biome palette.
     */
    public static final int MINIMUM_BITS_PER_ENTRY_BIOMES = 1;
    /**
     * The minimum amount of bits for a block palette.
     */
    public static final int MINIMUM_BITS_PER_ENTRY_BLOCKS = 4;

    /**
     * Write values to a 1.13 formatted palette.
     *
     * @param minimumBitsPerEntry the minimum number of bits allowed per entry.
     * @param dimensionSize       the dimension size of the palette, e.g. 16 for a normal chunk.
     * @param keyCount            the number of possible keys.
     * @param values              the flat lookup to encode, if null it uses empty values.
     * @return the encoded palette values.
     */
    public static long[] writePaletteValues1_13(int minimumBitsPerEntry, int dimensionSize, int keyCount, short @Nullable [] values) {
        // Calculate bitsPerEntry
        int bitsPerEntry = minimumBitsPerEntry;
        while (keyCount > 1 << bitsPerEntry) bitsPerEntry++;
        long maxEntryValue = (1L << bitsPerEntry) - 1;

        int valuesPerPalette = dimensionSize * dimensionSize * dimensionSize;

        // Based on code from https://github.com/ViaVersion/ViaVersion/blob/9ea6c34543d6052f1e91b5035e87b756cb2358b8/common/src/main/java/us/myles/ViaVersion/util/CompactArrayUtil.java
        long[] encodedValues = new long[(int) Math.ceil(valuesPerPalette * bitsPerEntry / 64.0)];
        if (values != null) {
            for (int i = 0; i < valuesPerPalette; i++) {
                // The flat palette array is stored in (y, z, x) order with x varying fastest, which is the same order
                // i walks in, so the value is simply values[i] - no index maths on the hot path.
                short value = values[i];
                int bitIndex = i * bitsPerEntry;
                int startIndex = bitIndex / 64;
                int endIndex = ((i + 1) * bitsPerEntry - 1) / 64;
                int startBitSubIndex = bitIndex % 64;
                encodedValues[startIndex] = (encodedValues[startIndex] & ~(maxEntryValue << startBitSubIndex)) | ((value & maxEntryValue) << startBitSubIndex);
                if (startIndex != endIndex) {
                    int endBitSubIndex = 64 - startBitSubIndex;
                    encodedValues[endIndex] = ((encodedValues[endIndex] >> endBitSubIndex) << endBitSubIndex) | ((value & maxEntryValue) >> endBitSubIndex);
                }
            }
        }

        return encodedValues;
    }

    /**
     * Write values to a 1.16 formatted palette.
     *
     * @param minimumBitsPerEntry the minimum number of bits allowed per entry.
     * @param dimensionSize       the dimension size of the palette, e.g. 16 for a normal chunk.
     * @param keyCount            the number of possible keys.
     * @param values              the flat lookup to encode, if null it uses empty values.
     * @return the encoded palette values.
     */
    public static long[] writePaletteValues1_16(int minimumBitsPerEntry, int dimensionSize, int keyCount, short @Nullable [] values) {
        // Calculate bitsPerEntry
        int bitsPerEntry = minimumBitsPerEntry;
        while (keyCount > 1 << bitsPerEntry) bitsPerEntry++;
        long maxEntryValue = (1L << bitsPerEntry) - 1;

        int valuesPerPalette = dimensionSize * dimensionSize * dimensionSize;

        int valuesPerLong = 64 / bitsPerEntry;

        long[] encodedValues = new long[(valuesPerPalette + valuesPerLong - 1) / valuesPerLong];
        if (values != null) {
            // Fill one long at a time. The cells sharing a long are consecutive in the flat array, so the shift can be
            // carried; the old shape divided every cell index by valuesPerLong (again through the game's magic
            // multiply) to find which long it belonged to and at what offset.
            int valueIndex = 0;
            for (int longIndex = 0; longIndex < encodedValues.length; longIndex++) {
                long word = 0;
                int shift = 0;
                for (int position = 0; position < valuesPerLong && valueIndex < valuesPerPalette; position++) {
                    word |= (long) (values[valueIndex++] & maxEntryValue) << shift;
                    shift += bitsPerEntry;
                }
                encodedValues[longIndex] = word;
            }
        }

        return encodedValues;
    }

    /**
     * Read values from a palette and use the length to detect the version.
     *
     * @param minimumBitsPerEntry the minimum number of bits per an entry.
     * @param dimensionSize       the dimension size of the array, e.g. [16][16][16] for a normal chunk.
     * @param encodedValues       the encoded values.
     * @return the decoded palette values.
     */
    public static short[] readPaletteValues(int minimumBitsPerEntry, int dimensionSize, int keyCount, long[] encodedValues) {
        // Calculate bitsPerEntry
        int bitsPerEntry = minimumBitsPerEntry;
        while (keyCount > 1 << bitsPerEntry) bitsPerEntry++;

        // Calculate the values per long
        char valuesPerLong = (char) (64 / bitsPerEntry);
        int valuesPerPalette = dimensionSize * dimensionSize * dimensionSize;

        // If the values line up then it is likely the 1.16 format and can be read using that algorithm, otherwise fallback
        double expectedLength = Math.ceil((double) valuesPerPalette / valuesPerLong);
        if (encodedValues.length != expectedLength) {
            bitsPerEntry = (int) Math.floor(encodedValues.length * 64 / (double) valuesPerPalette);
            return readPaletteValues1_13(bitsPerEntry, dimensionSize, encodedValues); // Fallback to 1.13
        } else {
            return readPaletteValues1_16(bitsPerEntry, dimensionSize, encodedValues);
        }
    }

    /**
     * Read values from a 1.13 formatted palette.
     *
     * @param bitsPerEntry  the number of bits used per palette key index.
     * @param dimensionSize the dimension size of the palette, e.g. 16 for a normal chunk.
     * @param encodedValues the encoded values.
     * @return the decoded palette values.
     */
    public static short[] readPaletteValues1_13(int bitsPerEntry, int dimensionSize, long[] encodedValues) {
        short[] output = new short[dimensionSize * dimensionSize * dimensionSize];
        int valuesPerPalette = dimensionSize * dimensionSize * dimensionSize;

        // Based on code from https://github.com/ViaVersion/ViaVersion/blob/9ea6c34543d6052f1e91b5035e87b756cb2358b8/common/src/main/java/us/myles/ViaVersion/util/CompactArrayUtil.java
        long maxEntryValue = (1L << bitsPerEntry) - 1;
        for (int i = 0; i < valuesPerPalette; i++) {
            int bitIndex = i * bitsPerEntry;
            int startIndex = bitIndex / 64;
            int endIndex = ((i + 1) * bitsPerEntry - 1) / 64;
            int startBitSubIndex = bitIndex % 64;
            short value;
            if (startIndex == endIndex) {
                value = (short) (((encodedValues[startIndex] >>> startBitSubIndex) & maxEntryValue) & 0xFFFF);
            } else {
                int endBitSubIndex = 64 - startBitSubIndex;
                value = (short) ((((encodedValues[startIndex] >>> startBitSubIndex) | (encodedValues[endIndex] << endBitSubIndex)) & maxEntryValue) & 0xFFFF);
            }

            // Set entry - i walks (y, z, x) with x fastest, which is the flat array layout
            output[i] = value;
        }

        return output;
    }

    /**
     * Read values from a 1.16 formatted palette.
     *
     * @param bitsPerEntry  the number of bits used per palette key index.
     * @param dimensionSize the dimension size of the palette, e.g. 16 for a normal chunk.
     * @param encodedValues the encoded values.
     * @return the decoded palette values.
     */
    public static short[] readPaletteValues1_16(int bitsPerEntry, int dimensionSize, long[] encodedValues) {
        short[] output = new short[dimensionSize * dimensionSize * dimensionSize];
        int valuesPerPalette = output.length;

        // Use maxEntryValue for bitmask
        long maxEntryValue = (1L << bitsPerEntry) - 1;
        int valuesPerLong = 64 / bitsPerEntry;

        // Walk the longs and shift each one down in place. The previous shape decoded cell i on its own, which meant
        // dividing i by valuesPerLong for every cell - done with the same "magic number" multiply the game uses,
        // because a real division per cell was even slower. Iterating the other way round makes both the division
        // and the multiply disappear: the long is already in hand, and each cell is one mask and one shift.
        int outputIndex = 0;
        for (int longIndex = 0; longIndex < encodedValues.length && outputIndex < valuesPerPalette; longIndex++) {
            long remaining = encodedValues[longIndex];
            for (int position = 0; position < valuesPerLong && outputIndex < valuesPerPalette; position++) {
                output[outputIndex++] = (short) (remaining & maxEntryValue);
                remaining >>>= bitsPerEntry;
            }
        }

        return output;
    }
}
