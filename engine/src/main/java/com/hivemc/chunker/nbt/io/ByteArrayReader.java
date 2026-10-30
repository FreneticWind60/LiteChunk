package com.hivemc.chunker.nbt.io;

import org.jetbrains.annotations.NotNull;

import java.io.EOFException;
import java.io.IOException;
import java.io.UTFDataFormatException;

/**
 * A {@link Reader} which decodes straight out of a byte array.
 * <p>
 * The stream based readers put four objects (ByteArrayInputStream, BufferedInputStream, DataInputStream and the reader
 * wrapper) in front of every NBT document and then cross them once per field. This reads the array directly, which is
 * what the NBT parser actually wants: a cursor and a length.
 * <p>
 * Strings are decoded as Java's modified UTF-8 (the encoding {@link java.io.DataOutputStream#writeUTF(String)} uses),
 * matching {@link Reader#toJavaReader(java.io.DataInput)}. The string length is used as the output size bound, so no
 * intermediate buffer is needed.
 */
public final class ByteArrayReader implements Reader {
    private final byte[] data;
    private final int limit;
    private int position;

    /**
     * Create a reader over the whole array.
     *
     * @param data the array to read from.
     */
    public ByteArrayReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Create a reader over a section of an array.
     *
     * @param data   the array to read from.
     * @param offset the first byte to read.
     * @param length the number of bytes which can be read.
     */
    public ByteArrayReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    "Invalid section %s+%s for a %s byte array".formatted(offset, length, data.length));
        }
        this.data = data;
        this.position = offset;
        this.limit = offset + length;
    }

    /**
     * Get the number of bytes left to read.
     *
     * @return the remaining byte count.
     */
    public int remaining() {
        return limit - position;
    }

    private void require(int count) throws EOFException {
        if (limit - position < count) {
            throw new EOFException("Needed " + count + " bytes with only " + (limit - position) + " remaining");
        }
    }

    @Override
    public short readShort() throws IOException {
        require(2);
        int index = position;
        position = index + 2;
        return (short) (((data[index] & 0xFF) << 8) | (data[index + 1] & 0xFF));
    }

    @Override
    public void readBytes(byte @NotNull [] array) throws IOException {
        require(array.length);
        System.arraycopy(data, position, array, 0, array.length);
        position += array.length;
    }

    @Override
    public byte readByte() throws IOException {
        require(1);
        return data[position++];
    }

    @Override
    public int readUnsignedByte() throws IOException {
        require(1);
        return data[position++] & 0xFF;
    }

    @Override
    public int readInt() throws IOException {
        require(4);
        int index = position;
        position = index + 4;
        return ((data[index] & 0xFF) << 24)
                | ((data[index + 1] & 0xFF) << 16)
                | ((data[index + 2] & 0xFF) << 8)
                | (data[index + 3] & 0xFF);
    }

    @Override
    public int readUnsignedInt24() throws IOException {
        require(3);
        int index = position;
        position = index + 3;
        return ((data[index] & 0xFF) << 16)
                | ((data[index + 1] & 0xFF) << 8)
                | (data[index + 2] & 0xFF);
    }

    @Override
    public long readLong() throws IOException {
        require(8);
        int index = position;
        position = index + 8;
        return ((long) (data[index] & 0xFF) << 56)
                | ((long) (data[index + 1] & 0xFF) << 48)
                | ((long) (data[index + 2] & 0xFF) << 40)
                | ((long) (data[index + 3] & 0xFF) << 32)
                | ((long) (data[index + 4] & 0xFF) << 24)
                | ((long) (data[index + 5] & 0xFF) << 16)
                | ((long) (data[index + 6] & 0xFF) << 8)
                | (data[index + 7] & 0xFF);
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public ByteString readShortPrefixedByteString(int maxLength) throws IOException {
        return new ByteString(readShortPrefixedUTF(maxLength));
    }

    @Override
    public @NotNull String readString(int maxLength) throws IOException {
        return readShortPrefixedUTF(maxLength);
    }

    /**
     * Read a length prefixed modified UTF-8 string, matching {@link java.io.DataInput#readUTF()}.
     * <p>
     * The length is an <em>unsigned</em> short of bytes and the limit is applied to the decoded character count, which
     * is what the stream based Java reader did (readUTF then a length check). Checking the byte count instead would
     * reject long text values whose characters take three bytes each.
     *
     * @param maxLength the maximum accepted character count.
     * @return the decoded string.
     * @throws IOException if the bytes are not valid modified UTF-8.
     */
    private String readShortPrefixedUTF(int maxLength) throws IOException {
        require(2);
        int length = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        position += 2;
        String value = readModifiedUTF(length);
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("Could not read String with length " + value.length());
        }
        return value;
    }

    /**
     * Decode {@code length} bytes of modified UTF-8 (as written by DataOutputStream#writeUTF).
     *
     * @param length the number of bytes to consume.
     * @return the decoded string.
     * @throws IOException if the bytes are not valid modified UTF-8.
     */
    private String readModifiedUTF(int length) throws IOException {
        require(length);
        int index = position;
        position += length;
        int end = index + length;

        // A modified UTF-8 sequence never decodes to more characters than it has bytes
        char[] chars = new char[length];
        int count = 0;
        while (index < end) {
            int first = data[index] & 0xFF;
            if (first < 0x80) {
                chars[count++] = (char) first;
                index++;
            } else if ((first & 0xE0) == 0xC0) {
                if (index + 1 >= end) throw new UTFDataFormatException("Malformed modified UTF-8 input");
                chars[count++] = (char) (((first & 0x1F) << 6) | (data[index + 1] & 0x3F));
                index += 2;
            } else if ((first & 0xF0) == 0xE0) {
                if (index + 2 >= end) throw new UTFDataFormatException("Malformed modified UTF-8 input");
                chars[count++] = (char) (((first & 0x0F) << 12)
                        | ((data[index + 1] & 0x3F) << 6)
                        | (data[index + 2] & 0x3F));
                index += 3;
            } else {
                throw new UTFDataFormatException("Malformed modified UTF-8 input");
            }
        }
        return new String(chars, 0, count);
    }
}
