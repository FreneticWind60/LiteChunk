package com.frenetic.litechunk.engine;

import com.hivemc.chunker.nbt.io.Reader;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.table.BloomFilterPolicy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dumps the block entities of a Bedrock world, and in particular the command stored in a command block.
 * <p>
 * "The particles are gone after converting" has two very different causes - the command block never made it across,
 * or it made it across with a command the other edition cannot parse - and only the database can tell them apart.
 * <p>
 * Usage: BlockEntityDump &lt;worldDir&gt;
 */
public final class BlockEntityDump {

    /** The storage type byte for a single chunk's block entity data (LevelDBChunkType.BLOCK_ENTITY). */
    private static final byte BLOCK_ENTITY = 49;

    private BlockEntityDump() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: BlockEntityDump <worldDir>");
            System.exit(2);
        }
        File directory = new File(args[0], "db");
        if (!directory.isDirectory()) {
            System.err.println("no db directory in " + args[0]);
            System.exit(2);
        }

        Options options = new Options();
        options.compressionType(org.iq80.leveldb.CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        options.createIfMissing(false);

        Map<String, Integer> identifiers = new TreeMap<>();
        int commands = 0;

        try (DB database = new Iq80DBFactory().open(directory, options)) {
            try (DBIterator iterator = database.iterator()) {
                while (iterator.hasNext()) {
                    Map.Entry<byte[], byte[]> entry = iterator.next();
                    byte[] key = entry.getKey();
                    // Overworld columns are 4 + 4 + 1 bytes, other dimensions add the dimension id.
                    if ((key.length != 9 && key.length != 13) || key[key.length - 1] != BLOCK_ENTITY) {
                        continue;
                    }
                    byte[] value = entry.getValue();
                    if (value == null || value.length == 0) {
                        continue;
                    }

                    try (ByteArrayInputStream input = new ByteArrayInputStream(value);
                         DataInputStream stream = new DataInputStream(input)) {
                        Reader reader = Reader.toBedrockReader(stream);
                        while (input.available() > 0) {
                            TagWithName<CompoundTag> pair = Tag.decodeNamed(reader, CompoundTag.class);
                            if (pair == null) {
                                break;
                            }
                            CompoundTag tag = pair.tag();
                            String id = tag.getString("id", "<none>");
                            identifiers.merge(id, 1, Integer::sum);
                            if (tag.contains("Command")) {
                                System.out.println("COMMAND [" + id + "]: " + tag.getString("Command", ""));
                                commands++;
                            }
                        }
                    }
                }
            }
        }

        System.out.println("block entities: " + identifiers);
        System.out.println("with a Command field: " + commands);
    }
}
