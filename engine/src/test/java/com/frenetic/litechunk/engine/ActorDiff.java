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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builds the field dictionary for Bedrock actors: which NBT keys the game persists, per entity type.
 * <p>
 * This is the ground truth step for entity conversion. {@link ActorDump} answers "are the records there"; this
 * answers "what does a real world actually store", which is what a writer has to reproduce. The distinction
 * matters: a converted world written by us contains the six keys we emit, so dumping one proves nothing about
 * what the game expects.
 * <p>
 * Usage: ActorDiff &lt;worldDir&gt; [maxActorsPerType] [compareWorldDir]
 * <p>
 * With a second world, keys are diffed both ways so a writer can be checked against a reference.
 */
public final class ActorDiff {
    private static final int MAX_DISTINCT_VALUES = 12;
    private static final int MAX_VALUE_LENGTH = 220;

    private ActorDiff() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: ActorDiff <worldDir> [maxActorsPerType] [compareWorldDir]");
            System.exit(2);
        }

        int maxActors = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        Map<String, Map<String, KeyStats>> world = readWorld(new File(args[0]), maxActors);
        report(world);

        if (args.length > 2) {
            Map<String, Map<String, KeyStats>> reference = readWorld(new File(args[2]), maxActors);
            compare(world, reference, args[2]);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    private static Map<String, Map<String, KeyStats>> readWorld(File worldDir, int maxActors) throws Exception {
        File directory = new File(worldDir, "db");
        if (!directory.isDirectory()) {
            System.err.println("no db directory in " + worldDir);
            System.exit(2);
        }

        Options options = new Options();
        options.compressionType(org.iq80.leveldb.CompressionType.ZLIB_RAW);
        options.blockSize(160 * 1024);
        options.filterPolicy(new BloomFilterPolicy(10));
        options.createIfMissing(false);

        // identifier -> key -> stats, both sorted so the output is diffable between runs
        Map<String, Map<String, KeyStats>> result = new TreeMap<>();
        Map<String, Integer> totals = new TreeMap<>();
        int records = 0;

        try (DB database = new Iq80DBFactory().open(directory, options)) {
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
                    records++;

                    String identifier = tag.getString("identifier", "<none>");
                    int seen = totals.getOrDefault(identifier, 0);
                    if (seen >= maxActors) {
                        continue;
                    }
                    totals.merge(identifier, 1, Integer::sum);

                    Map<String, KeyStats> keys = result.computeIfAbsent(identifier, key -> new TreeMap<>());
                    for (Map.Entry<String, Tag<?>> named : tag) {
                        KeyStats stats = keys.computeIfAbsent(named.getKey(), key -> new KeyStats());
                        stats.type = named.getValue().getClass().getSimpleName();
                        stats.count++;
                        stats.add(distinctValue(named.getValue()));
                    }
                }
            }
        }

        System.out.println("records: " + records + ", identifiers: " + result.size());
        for (Map.Entry<String, Integer> total : totals.entrySet()) {
            int sampled = result.containsKey(total.getKey()) ? total.getValue() : 0;
            System.out.println("  " + total.getKey() + ": " + total.getValue()
                    + (sampled < total.getValue() ? " (sampled " + sampled + ")" : ""));
        }
        return result;
    }

    private static String distinctValue(Tag<?> tag) {
        String value;
        try {
            value = tag.toSNBT();
        } catch (RuntimeException failure) {
            value = String.valueOf(tag.getBoxedValue());
        }
        if (value == null) {
            return "null";
        }
        value = value.replace('\n', ' ');
        return value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) + "…" : value;
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

    // ---------------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------------

    private static void report(Map<String, Map<String, KeyStats>> world) {
        for (Map.Entry<String, Map<String, KeyStats>> type : world.entrySet()) {
            String identifier = type.getKey();
            Map<String, KeyStats> keys = type.getValue();
            int actors = keys.values().stream().mapToInt(stats -> stats.count).max().orElse(0);

            System.out.println();
            System.out.println("### " + identifier + "  (" + actors + " actors, " + keys.size() + " keys)");
            System.out.println("key\ttype\tpresence\tdistinct\tvalues");
            for (Map.Entry<String, KeyStats> key : sortByPresence(keys)) {
                KeyStats stats = key.getValue();
                System.out.println(key.getKey() + "\t" + stats.type + "\t" + stats.count + "/" + actors
                        + "\t" + stats.distinctLabel() + "\t" + stats.valuesSummary());
            }
        }

        System.out.println();
        System.out.println("### 所有类型共有（出现在 >=90% 的类型里）");
        Map<String, Integer> shared = new TreeMap<>();
        for (Map<String, KeyStats> keys : world.values()) {
            for (String key : keys.keySet()) {
                shared.merge(key, 1, Integer::sum);
            }
        }
        int types = world.size();
        for (Map.Entry<String, Integer> entry : shared.entrySet()) {
            if (entry.getValue() * 10 >= types * 9) {
                System.out.println("  " + entry.getKey() + "  (" + entry.getValue() + "/" + types + " 类型)");
            }
        }

        System.out.println();
        System.out.println("### 只出现在部分类型里（状态字段候选）");
        for (Map.Entry<String, Integer> entry : shared.entrySet()) {
            if (entry.getValue() * 10 < types * 9) {
                System.out.println("  " + entry.getKey() + "  (" + entry.getValue() + "/" + types + " 类型)");
            }
        }
    }

    private static List<Map.Entry<String, KeyStats>> sortByPresence(Map<String, KeyStats> keys) {
        List<Map.Entry<String, KeyStats>> ordered = new ArrayList<>(keys.entrySet());
        ordered.sort((a, b) -> {
            int byPresence = Integer.compare(b.getValue().count, a.getValue().count);
            return byPresence != 0 ? byPresence : a.getKey().compareTo(b.getKey());
        });
        return ordered;
    }

    private static void compare(Map<String, Map<String, KeyStats>> ours,
                                Map<String, Map<String, KeyStats>> reference,
                                String label) {
        System.out.println();
        System.out.println("### 对比 reference: " + label);
        for (Map.Entry<String, Map<String, KeyStats>> type : ours.entrySet()) {
            Map<String, KeyStats> referenceKeys = reference.get(type.getKey());
            if (referenceKeys == null) {
                System.out.println(type.getKey() + "\t(参考世界没有这个类型)");
                continue;
            }
            TreeSet<String> missing = new TreeSet<>(referenceKeys.keySet());
            missing.removeAll(type.getValue().keySet());
            TreeSet<String> extra = new TreeSet<>(type.getValue().keySet());
            extra.removeAll(referenceKeys.keySet());

            System.out.println(type.getKey() + "\t缺 " + missing.size() + " 键\t多 " + extra.size() + " 键");
            if (!missing.isEmpty()) {
                System.out.println("  缺: " + String.join(", ", missing));
            }
            if (!extra.isEmpty()) {
                System.out.println("  多: " + String.join(", ", extra));
            }
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

    /**
     * What one key looks like across every sampled actor of one type.
     */
    private static final class KeyStats {
        private String type = "";
        private int count;
        private final Map<String, Integer> values = new LinkedHashMap<>();

        private void add(String value) {
            if (values.size() < MAX_DISTINCT_VALUES || values.containsKey(value)) {
                values.merge(value, 1, Integer::sum);
            } else {
                values.put("<更多>", 1);
            }
        }

        private String distinctLabel() {
            return values.containsKey("<更多>") ? ">" + MAX_DISTINCT_VALUES : String.valueOf(values.size());
        }

        private String valuesSummary() {
            StringBuilder builder = new StringBuilder();
            for (String value : values.keySet()) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(value);
            }
            String summary = builder.toString();
            return summary.length() > 420 ? summary.substring(0, 420) + "…" : summary;
        }
    }
}
