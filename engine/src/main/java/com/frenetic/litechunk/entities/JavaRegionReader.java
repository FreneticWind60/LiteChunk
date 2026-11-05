package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Minimal reader for an Anvil region file ({@code r.X.Z.mca}).
 * <p>
 * Deliberately self-contained: the entity pass must not pull in the column readers, because those read terrain as
 * well and the whole point of the second pass is to avoid decoding blocks twice. Only the 4 KiB offset table and the
 * individual chunk records are touched.
 * <p>
 * Oversized chunks (compression flag 0x80, stored externally as {@code c.X.Z.mcc}) are skipped rather than followed;
 * they only appear in worlds written by mods and would complicate a pass that is not the primary converter.
 */
final class JavaRegionReader implements Closeable {

    private static final int SECTOR = 4096;
    private static final int COLUMNS = 32 * 32;

    private final RandomAccessFile file;
    private final int[] offsets = new int[COLUMNS];

    JavaRegionReader(File file) throws IOException {
        this.file = new RandomAccessFile(file, "r");
        try {
            readOffsetTable();
        } catch (IOException failure) {
            this.file.close();
            throw failure;
        }
    }

    private void readOffsetTable() throws IOException {
        // A region that was created but never written is zero bytes; reading the table would fail on it.
        if (file.length() < SECTOR) {
            return;
        }
        byte[] header = new byte[SECTOR];
        file.seek(0L);
        file.readFully(header);
        for (int index = 0; index < COLUMNS; index++) {
            int position = index << 2;
            int offset = ((header[position] & 0xFF) << 16)
                    | ((header[position + 1] & 0xFF) << 8)
                    | (header[position + 2] & 0xFF);
            offsets[index] = offset;
        }
    }

    /**
     * @param index the column index inside the region, {@code (z << 5) | x}.
     * @return true when the region file holds a column at that index.
     */
    boolean has(int index) {
        return offsets[index] > 0;
    }

    /**
     * Read one column.
     *
     * @param index the column index inside the region.
     * @return the chunk NBT, or null when the entry is empty, externally stored or compressed in a way we do not read.
     */
    @Nullable
    CompoundTag read(int index) throws IOException {
        int offset = offsets[index];
        if (offset <= 0) {
            return null;
        }

        long position = (long) offset * SECTOR;
        // A region that was grown lazily can still have an offset table entry pointing past the end of the file; that
        // is not corruption, it is a column the server never finished writing.
        if (position + 5L > file.length()) {
            return null;
        }
        file.seek(position);
        int length = file.readInt();
        int compression = file.readUnsignedByte();
        if (length <= 1 || (compression & 0x80) != 0) {
            return null;
        }

        if (position + 4L + length > file.length()) {
            return null; // truncated record, the server died mid-write
        }
        byte[] payload = new byte[length - 1];
        file.readFully(payload);

        return switch (compression & 0x7F) {
            case 1 -> Tag.readGZipJavaNBT(payload);
            case 2 -> Tag.readZLibJavaNBT(payload);
            case 3 -> Tag.readUncompressedJavaNBT(payload, 0, payload.length);
            case 4 -> Tag.readLZ4JavaNBT(payload);
            default -> null;
        };
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
