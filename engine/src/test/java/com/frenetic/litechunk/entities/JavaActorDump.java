package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dumps the entity NBT of a Java world, for finding out what the Java side actually stores.
 * <p>
 * The Bedrock side is covered by {@code ActorDiff} / {@code ActorDump}; this is the mirror for the input side, and it
 * exists because the field names differ per version and per entity ({@code Color} vs {@code color}, {@code Variant}
 * packing colour and markings into one int, {@code Type} vs {@code variant}). Guessing those is how wrong output gets
 * written, so they are read from a real world instead.
 * <p>
 * Read only: it never writes to the world it is pointed at.
 */
public final class JavaActorDump {

    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private JavaActorDump() {
    }

    /**
     * @param args {@code <world directory> <id filter> [samples per type]}.
     * @throws Exception when the world cannot be read.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: JavaActorDump <world directory> [id filter] [samples per type]");
            return;
        }
        File world = new File(args[0]);
        String filter = args.length > 1 ? args[1].toLowerCase() : "";
        int limit = args.length > 2 ? Integer.parseInt(args[2]) : 2;

        // Keeps the output to one sample per entity type unless a higher limit is asked for, in the order the world
        // was walked so the samples are stable between runs.
        Map<String, List<CompoundTag>> byType = new LinkedHashMap<>();

        for (Dimension dimension : Dimension.values()) {
            File folder = dimension.entitiesFolder(world);
            File[] files = folder.listFiles((dir, name) -> REGION_FILE.matcher(name).matches());
            if (files == null) {
                continue;
            }
            for (File file : files) {
                try (JavaRegionReader reader = new JavaRegionReader(file)) {
                    for (int index = 0; index < 32 * 32; index++) {
                        if (!reader.has(index)) {
                            continue;
                        }
                        CompoundTag column;
                        try {
                            column = reader.read(index);
                        } catch (Exception failure) {
                            continue;
                        }
                        if (column == null) {
                            continue;
                        }
                        ListTag<CompoundTag, Map<String, Tag<?>>> entities =
                                column.getList("Entities", CompoundTag.class, null);
                        if (entities == null) {
                            continue;
                        }
                        for (CompoundTag entity : entities) {
                            String id = entity.getString("id", "?");
                            if (!filter.isEmpty() && !id.toLowerCase().contains(filter)) {
                                continue;
                            }
                            List<CompoundTag> samples = byType.computeIfAbsent(id, key -> new ArrayList<>());
                            if (samples.size() < limit) {
                                samples.add(entity);
                            }
                        }
                    }
                } catch (Exception failure) {
                    System.out.println("!! " + file.getName() + ": " + failure);
                }
            }
        }

        if (byType.isEmpty()) {
            System.out.println("no entities matched \"" + filter + "\"");
            return;
        }
        byType.forEach((id, samples) -> {
            System.out.println("### " + id + " (" + samples.size() + " samples)");
            for (CompoundTag sample : samples) {
                System.out.println("  " + sample.toSNBT());
            }
            System.out.println();
        });
    }

    /**
     * The three vanilla dimensions and where their entities live.
     */
    private enum Dimension {
        OVERWORLD(""),
        NETHER("DIM-1"),
        END("DIM1");

        private final String prefix;

        Dimension(String prefix) {
            this.prefix = prefix;
        }

        private File entitiesFolder(File world) {
            File base = prefix.isEmpty() ? world : new File(world, prefix);
            File entities = new File(base, "entities");
            // Before 1.17 the entities are stored inside the chunk NBT of the region file.
            return entities.isDirectory() ? entities : new File(base, "region");
        }
    }
}
