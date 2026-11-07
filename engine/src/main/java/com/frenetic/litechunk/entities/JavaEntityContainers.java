package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Reads the contents of a Bedrock container entity back into the Java form: chest and hopper minecarts, and the
 * chested animals.
 * <p>
 * Both sides keep the items on the entity under {@code Items} with a {@code Slot} on each entry, so this is a
 * per-item translate plus the slot - the same shape {@link EntityContainers} writes. The chest flag is the part that
 * differs: Java spells it {@code ChestedHorse} (1.21.5 and later; {@code Chested} before that) while Bedrock's actor
 * field is {@code Chested}.
 * <p>
 * Everything goes through {@link JavaItemStacks}, so the contents end up with the same names the terrain pass used for
 * block entity inventories.
 */
final class JavaEntityContainers {

    /** Bedrock's flag for "this animal is wearing a chest". */
    private static final String BEDROCK_CHESTED = "Chested";

    /** Java's spelling, 1.21.5 and later. */
    private static final String JAVA_CHESTED = "ChestedHorse";

    private JavaEntityContainers() {
    }

    /**
     * Apply the inventory of the actor, if it is one that has one.
     *
     * @param javaEntity the Java entity tag being built, modified in place.
     * @param actor      the Bedrock actor tag that was read.
     * @param identifier the Bedrock identifier.
     * @param source     resolvers of the Bedrock version that was read, may be null.
     * @param target     resolvers of the Java version being written, may be null.
     */
    static void apply(CompoundTag javaEntity, CompoundTag actor, String identifier,
                      @Nullable BedrockResolvers source, @Nullable JavaResolvers target) {
        switch (identifier) {
            case "minecraft:chest_minecart", "minecraft:hopper_minecart" -> items(javaEntity, actor, source, target);
            case "minecraft:llama", "minecraft:trader_llama", "minecraft:donkey", "minecraft:mule" -> {
                if (intValue(actor, BEDROCK_CHESTED, 0) != 0) {
                    javaEntity.put(JAVA_CHESTED, (byte) 1);
                }
                items(javaEntity, actor, source, target);
            }
            default -> {
                // Not a container.
            }
        }
    }

    /**
     * Translate the {@code Items} list.
     */
    private static void items(CompoundTag javaEntity, CompoundTag actor,
                              @Nullable BedrockResolvers source, @Nullable JavaResolvers target) {
        if (source == null || target == null) {
            return;
        }
        ListTag<CompoundTag, ?> items = actor.getList("Items", CompoundTag.class, null);
        if (items == null || items.size() == 0) {
            return;
        }

        ListTag<CompoundTag, Map<String, Tag<?>>> output = new ListTag<>(TagType.COMPOUND, items.size());
        for (CompoundTag bedrockItem : items) {
            CompoundTag item = JavaItemStacks.convert(bedrockItem, source, target);
            if (item == null) {
                // Unknown or empty stack: skip it, but keep the other slots where they were.
                continue;
            }
            // The slot travels with the item on both sides; without it the list order decides, which shifts anything
            // that was not stored contiguously.
            item.put("Slot", (byte) intValue(bedrockItem, "Slot", output.size()));
            output.add(item);
        }

        if (output.size() > 0) {
            javaEntity.put("Items", output);
        }
    }

    private static int intValue(CompoundTag tag, String name, int defaultValue) {
        Object value = tag.contains(name) ? tag.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
