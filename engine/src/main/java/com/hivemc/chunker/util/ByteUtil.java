package com.hivemc.chunker.util;

/**
 * Utility for based-based operations.
 */
public class ByteUtil {
    /**
     * Get a "nibble" from a byte, a nibble is half a byte.
     *
     * @param input      the input byte (two-nibbles).
     * @param lowestBits whether to get the lowest bits of the nibble.
     * @return the relevant part of the nibble.
     */
    public static byte getNibbleFromByte(byte input, boolean lowestBits) {
        return lowestBits ? (byte) (input & 0xF) : (byte) ((input >> 4) & 0xF);
    }

    /**
     * Update part of a nibble, a nibble is half a byte.
     *
     * @param existingValue the existing value (two-nibbles)
     * @param input         the value to set (in the first half of the byte).
     * @param lowestBits    whether to set the lowest bits of the nibble or the highest.
     * @return the newly updated two-nibble.
     */
    public static byte updateNibble(byte existingValue, byte input, boolean lowestBits) {
        byte otherValue = getNibbleFromByte(existingValue, !lowestBits);
        if (lowestBits) {
            return (byte) ((otherValue << 4) | (input & 0xF));
        } else {
            return (byte) (((input & 0xF) << 4) | otherValue);
        }
    }

    /**
     * Convert a flat light array into a 2048-bit nibble array.
     * <p>
     * The input is already in (y, z, x) order with x varying fastest, which is the order the nibble array uses, so
     * the index can be used directly instead of being decomposed into coordinates.
     *
     * @param input the flat input array, length 4096.
     * @return an output array containing the compacted data with 2048 bytes length.
     */
    public static byte[] convertToNibbleArray(byte[] input) {
        byte[] output = new byte[2048];
        for (int i = 0; i < input.length; i++) {
            boolean lowestBits = (i & 1) == 0;
            int nibbleIndex = i >> 1;

            // Update the relevant nibble with the new value
            output[nibbleIndex] = ByteUtil.updateNibble(output[nibbleIndex], input[i], lowestBits);
        }
        return output;
    }
}
