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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Dumps the actor records of a Bedrock world so the entity writer can be inspected without the game.
 * <p>
 * This exists because "entities do not appear in game" has two very different possible causes -- the records are
 * missing from the database, or they are present but the game refuses to load them -- and only the database can tell
 * the two apart.
 * <p>
 * Usage: ActorDump &lt;worldDir&gt; [maxSamples]
 */
public final class ActorDump {

    private ActorDump() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ActorDump <worldDir> [maxSamples]");
            System.exit(2);
        }
        File directory = new File(args[0], "db");
        int maxSamples = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        if (!directory.isDirectory()) {
            System.err.println("no db directory in " + args[0]);
            System.exit(2);
        }

        Options options = new Options();
        options.compressionType(org.iq80.leveldb.CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        options.createIfMissing(false);

        int digpCount = 0;
        int digpReferenced = 0;
        int actorCount = 0;
        List<byte[]> sampleKeys = new ArrayList<>();
        Map<String, Integer> identifiers = new TreeMap<>();

        try (DB database = new Iq80DBFactory().open(directory, options)) {
            try (DBIterator iterator = database.iterator()) {
                while (iterator.hasNext()) {
                    Map.Entry<byte[], byte[]> entry = iterator.next();
                    byte[] key = entry.getKey();
                    if (startsWith(key, "digp")) {
                        digpCount++;
                        int length = entry.getValue().length;
                        digpReferenced += length / 8;
                    } else if (startsWith(key, "actorprefix")) {
                        actorCount++;
                        if (sampleKeys.size() < 24) {
                            sampleKeys.add(key);
                        }
                    }
                }
            }

            System.out.println("digp records: " + digpCount + ", referenced actor keys: " + digpReferenced);
            System.out.println("actorprefix records: " + actorCount);

            // Second pass: decode the samples, skipping ones digp no longer points at would hide the problem, so
            // decode every actor and count identifiers.
            int decoded = 0;
            int missingDefinitions = 0;
            List<CompoundTag> samples = new ArrayList<>();
            try (DBIterator iterator = database.iterator()) {
                while (iterator.hasNext()) {
                    Map.Entry<byte[], byte[]> entry = iterator.next();
                    if (!startsWith(entry.getKey(), "actorprefix")) {
                        continue;
                    }
                    CompoundTag tag = decode(entry.getValue());
                    if (tag == null) {
                        continue;
                    }
                    decoded++;
                    String identifier = tag.getString("identifier", "<none>");
                    identifiers.merge(identifier, 1, Integer::sum);
                    if (!tag.contains("definitions")) {
                        missingDefinitions++;
                    }
                    if (samples.size() < maxSamples) {
                        samples.add(tag);
                    }
                }
            }

            System.out.println("decoded: " + decoded + ", without definitions: " + missingDefinitions);
            System.out.println("identifiers: " + identifiers);
            for (CompoundTag sample : samples) {
                System.out.println("--- sample ---");
                List<String> keys = new ArrayList<>();
                for (Map.Entry<String, Tag<?>> named : sample) {
                    keys.add(named.getKey());
                }
                System.out.println("keys: " + keys);
                System.out.println(sample);
            }
        }
    }

    private static CompoundTag decode(byte[] value) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(value);
             DataInputStream stream = new DataInputStream(input)) {
            TagWithName<CompoundTag> decoded = Tag.decodeNamed(Reader.toBedrockReader(stream), CompoundTag.class);
            return decoded == null ? null : decoded.tag();
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean startsWith(byte[] key, String prefix) {
        byte[] expected = prefix.getBytes(StandardCharsets.UTF_8);
        if (key.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (key[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
