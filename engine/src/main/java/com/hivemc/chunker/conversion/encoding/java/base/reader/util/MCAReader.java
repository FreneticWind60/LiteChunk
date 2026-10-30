package com.hivemc.chunker.conversion.encoding.java.base.reader.util;

import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Utility for reading Java .mca files.
 * <p>
 * Reads are done with positional {@link FileChannel#read(ByteBuffer, long)} calls rather than a
 * {@link java.io.RandomAccessFile}. A RandomAccessFile owns a single file pointer, so every seek/read pair has to be
 * serialised with a lock - and since a region file holds 1024 columns, that lock serialised an entire region and left
 * most of the worker threads waiting instead of reading. A positional read carries its own offset and can be issued
 * from any number of threads at once.
 */
public class MCAReader implements AutoCloseable {
    /**
     * Per-thread scratch buffer for the shared 5 byte column header (length + compression type).
     */
    private static final ThreadLocal<ByteBuffer> HEADER = ThreadLocal.withInitial(() -> ByteBuffer.allocate(5));

    /**
     * Per-thread buffer the compressed column payload is read into. A converted region holds ~1000 columns and the
     * previous code allocated a byte[] of the exact column size for every one of them; this reuses one array per
     * worker instead, and {@code Inflater.setInput(byte[])} can consume it without another copy.
     */
    private static final ThreadLocal<ByteBuffer> COLUMN_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocate(64 * 1024));

    private final Converter converter;
    private final File folder;
    private final FileChannel channel;

    /**
     * Create a new MCAReader (must be closed after).
     *
     * @param converter the converter instance.
     * @param file      the mca file to read.
     * @throws FileNotFoundException if the file wasn't present.
     */
    public MCAReader(Converter converter, File file) throws FileNotFoundException {
        this.converter = converter;
        folder = file.getParentFile();
        try {
            channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        } catch (IOException e) {
            FileNotFoundException notFound = new FileNotFoundException(file.getPath());
            notFound.initCause(e);
            throw notFound;
        }
    }

    /**
     * Read the offset table used for indexing the columns from the .mca file.
     *
     * @return the table of indexes for the columns (1024 columns in an MCA file).
     * @throws IOException if it failed to read the offset table.
     */
    public int[] readOffsetTable() throws IOException {
        int[] offsets = new int[1024];

        // Read into temporary buffer
        byte[] temp = new byte[4096];
        readFully(ByteBuffer.wrap(temp), 0L);

        // Read the header which contains the chunk offsets
        for (int i = 0; i < 1024; i++) {
            int tempIndex = i << 2;
            int offset = ((temp[tempIndex] & 0xFF) << 16) |
                    ((temp[tempIndex + 1] & 0xFF) << 8) |
                    (temp[tempIndex + 2] & 0xFF);
            // Skip sector count byte (unused, we can validate without it)

            // Only record the offset if it's more than 0, the first 4096 is the header, so it's invalid to be there
            if (offset > 0) {
                offsets[i] = offset;
            }
        }

        return offsets;
    }

    /**
     * Read a column at an offset.
     *
     * @param columnCoordPair the global position being read at the offset (used for oversized chunks).
     * @param offset          the offset to read at.
     * @return the task which is reading and decompressing the chunk.
     * @throws IOException if it failed to read the .mca file.
     */
    public CompoundTag readColumn(ChunkCoordPair columnCoordPair, int offset) throws IOException {
        // Columns are padded to 4096 byte sectors. The record is a 4 byte big endian length (which includes the
        // compression type byte) followed by the compression type and the payload.
        long position = offset * 4096L;
        ByteBuffer header = HEADER.get();
        header.clear();
        readFully(header, position);

        int chunkLength = header.getInt(0) - 1; // Minus 1 as it includes compression type
        byte rawType = header.get(4);
        byte compressionType = (byte) (rawType & ~0x80);

        // Oversized chunks live in their own file, rare enough to keep the simple path
        if ((rawType & 0x80) != 0) {
            File file = new File(folder, "c." + columnCoordPair.chunkX() + "." + columnCoordPair.chunkZ() + ".mcc");
            byte[] compressed = Files.readAllBytes(file.toPath());
            return decompressColumn(columnCoordPair, compressionType, compressed, 0, compressed.length);
        }

        if (chunkLength < 0) {
            throw new IOException("Invalid column length %d for %s".formatted(chunkLength, columnCoordPair));
        }

        // Read, inflate and parse in one go on the calling worker: the payload lives in a per-thread buffer, so it
        // can't be handed to another thread (or the next column) while this one is still being decompressed. That is
        // also what removes the per-chunk byte[]: the bytes go straight from the channel into the buffer the inflater
        // consumes. The caller is already a per-column task, so this still runs in parallel across columns.
        ByteBuffer buffer = columnBuffer(chunkLength);
        buffer.clear();
        buffer.limit(chunkLength);
        readFully(buffer, position + 5L);
        return decompressColumn(columnCoordPair, compressionType,
                buffer.array(), buffer.arrayOffset(), chunkLength);
    }

    /**
     * Read exactly buffer.remaining() bytes starting at a file position.
     *
     * @param buffer   the destination buffer.
     * @param position the absolute file position to read from.
     * @throws IOException if the file could not be read or ended early.
     */
    private void readFully(ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            // The offset has to advance with the buffer, otherwise a short read (which a channel is allowed to do)
            // would re-read the same bytes forever.
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new EOFException("Unexpected end of file after " + buffer.position() + " bytes");
            }
        }
    }

    /**
     * Get the per-thread column buffer, grown if the column is larger than the current one.
     *
     * @param required the number of bytes needed.
     * @return a buffer with at least {@code required} bytes of capacity.
     */
    private static ByteBuffer columnBuffer(int required) {
        ByteBuffer buffer = COLUMN_BUFFER.get();
        if (buffer.capacity() < required) {
            buffer = ByteBuffer.allocate(Math.max(required, buffer.capacity() * 2));
            COLUMN_BUFFER.set(buffer);
        }
        return buffer;
    }

    /**
     * Decompress a column.
     *
     * @param chunkCoordPair  the co-ordinates of the chunk being decompressed (used for exceptions).
     * @param compressionType the compression type.
     * @param compressed      the array holding the compressed column.
     * @param offset          the first byte of the compressed column inside {@code compressed}.
     * @param length          the number of compressed bytes.
     * @return the decompressed compound tag.
     * @throws IOException if it failed to decompress / read.
     */
    protected CompoundTag decompressColumn(ChunkCoordPair chunkCoordPair, byte compressionType, byte[] compressed, int offset, int length) throws IOException {
        try {
            // LZ4 was added in 1.20.5, but there is no harm supporting it here
            return switch (compressionType) {
                case 0 -> null; // Empty
                case 1 -> Tag.readGZipJavaNBT(compressed, offset, length); // GZip
                case 2 -> Tag.readZLibJavaNBT(compressed, offset, length); // Deflate - Default
                case 3 -> Tag.readUncompressedJavaNBT(compressed, offset, length); // Uncompressed
                // LZ4 still goes through its own block stream, which wants an exactly sized array
                case 4 -> Tag.readLZ4JavaNBT(Arrays.copyOfRange(compressed, offset, offset + length));
                default -> {
                    converter.logNonFatalException(new Exception("Unsupported Chunk Compression Type " + compressionType));
                    yield null;
                }
            };
        } catch (Exception e) {
            throw new IOException("Failed to decompress column %s inside %s".formatted(chunkCoordPair, chunkCoordPair.getRegion()), e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
