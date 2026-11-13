package org.iq80.leveldb.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * ZLib helpers for LevelDB, rewritten so they run on every Android version the app supports.
 * <p>
 * The upstream class drives {@link Inflater} and {@link Deflater} through their {@link ByteBuffer} overloads
 * ({@code setInput(ByteBuffer)}, {@code inflate(ByteBuffer)}, ...). Those are Java 11 additions and only reached
 * Android in API 31, so on 8.1 through 12 the first compressed block of any world threw
 * {@code NoSuchMethodError: setInput(ByteBuffer)} and the conversion died while opening the database.
 * <p>
 * The behaviour is the same as upstream: a thread local codec is reused, the output starts at twice the input size
 * (at least one kibibyte) and doubles in place, an unreadable stream becomes an {@link IOException}, and the returned
 * buffer is flipped so it can be read from position zero. This class replaces the copy inside the leveldb jar at build
 * time - see {@code engineDex} in {@code engine/build.gradle.kts}.
 */
public final class ZLib {
    private static final ThreadLocal<Inflater> INFLATER = ThreadLocal.withInitial(Inflater::new);
    private static final ThreadLocal<Inflater> INFLATER_RAW = ThreadLocal.withInitial(() -> new Inflater(true));
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(
            () -> new Deflater(Deflater.DEFAULT_COMPRESSION, false));
    private static final ThreadLocal<Deflater> DEFLATER_RAW = ThreadLocal.withInitial(
            () -> new Deflater(Deflater.DEFAULT_COMPRESSION, true));

    private ZLib() {
    }

    /**
     * Decompress a block.
     *
     * @param input the compressed data, read from its current position.
     * @param raw   true for a raw deflate stream (what LevelDB uses) rather than a zlib wrapped one.
     * @return a buffer holding the decompressed bytes, ready to read.
     * @throws IOException when the stream cannot be decoded.
     */
    public static ByteBuffer uncompress(ByteBuffer input, boolean raw) throws IOException {
        Inflater inflater = (raw ? INFLATER_RAW : INFLATER).get();

        int inputSize = input.remaining();
        byte[] source = new byte[inputSize];
        input.get(source);

        int initial = Math.max(1024, inputSize * 2);
        ByteBuffer output = ByteBuffer.allocate(initial);
        try {
            inflater.setInput(source);
            while (!inflater.finished()) {
                int read = inflater.inflate(output.array(), output.position(), output.remaining());
                if (read > 0) {
                    output.position(output.position() + read);
                    continue;
                }
                if (output.remaining() > 0) {
                    // Nothing came out and there is still room: the stream is truncated, and growing the buffer
                    // again would spin forever.
                    break;
                }
                ByteBuffer bigger = ByteBuffer.allocate(output.capacity() + Math.max(1024, inputSize * 2));
                output.flip();
                bigger.put(output);
                output = bigger;
            }
        } catch (DataFormatException failure) {
            throw new IOException(failure);
        } finally {
            inflater.reset();
        }

        output.flip();
        return output;
    }

    /**
     * Compress a block into a caller supplied array.
     *
     * @param input        the bytes to compress.
     * @param inputOffset  the first byte to read.
     * @param length       how many bytes to read.
     * @param output       the array to write into.
     * @param outputOffset the first byte to write.
     * @param raw          true for a raw deflate stream.
     * @return the number of bytes written.
     */
    public static int compress(byte[] input, int inputOffset, int length, byte[] output, int outputOffset, boolean raw) {
        Deflater deflater = (raw ? DEFLATER_RAW : DEFLATER).get();
        try {
            deflater.setInput(input, inputOffset, length);
            deflater.finish();
            return deflater.deflate(output, outputOffset, output.length - outputOffset);
        } finally {
            deflater.reset();
        }
    }
}
