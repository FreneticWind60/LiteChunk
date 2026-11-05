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
 * Writes the contents of the entities that carry an inventory: chest and hopper minecarts, and the chested animals.
 * <p>
 * Java keeps the items on the entity under {@code Items}, each entry carrying its own {@code Slot}; Bedrock's container
 * records use the same shape (the engine's chest writer produces {@code Items} entries with a {@code Slot} byte), so
 * the conversion is a per-item translate plus the slot.
 * <p>
 * <strong>Not verified in game.</strong> Neither the world used for testing nor the reference save contains a minecart
 * with items or a chested animal, so the Bedrock side is inferred from the engine's own container serialisation rather
 * than observed on a live actor. The failure mode if it is wrong is that the contents simply do not appear - the same
 * outcome as not writing them at all - which is why it is worth shipping before it can be confirmed.
 */
final class EntityContainers {

    private EntityContainers() {
    }

    /**
     * Apply the inventory of an entity, if it is one that has one.
     *
     * @param actor      the actor tag being built, modified in place.
     * @param identifier the Bedrock identifier.
     * @param javaEntity the Java entity NBT.
     * @param source     resolvers of the Java version that was read, may be null.
     * @param target     resolvers of the Bedrock version being written, may be null.
     */
    static void apply(CompoundTag actor, String identifier, CompoundTag javaEntity,
                      @Nullable JavaResolvers source, @Nullable BedrockResolvers target) {
        switch (identifier) {
            case "minecraft:chest_minecart", "minecraft:hopper_minecart" -> {
                if (source != null && target != null) {
                    items(actor, javaEntity, source, target);
                }
            }
            case "minecraft:llama", "minecraft:trader_llama", "minecraft:donkey", "minecraft:mule" -> {
                // The chest has to be visible before the contents make sense, and Java stores that as its own flag.
                if (javaEntity.contains("Chested")) {
                    actor.put("Chested", (byte) (intValue(javaEntity, "Chested", 0) != 0 ? 1 : 0));
                }
                if (source != null && target != null) {
                    items(actor, javaEntity, source, target);
                }
            }
            default -> {
                // Not a container.
            }
        }
    }

    /**
     * Translate the {@code Items} list.
     */
    private static void items(CompoundTag actor, CompoundTag entity, JavaResolvers source, BedrockResolvers target) {
        ListTag<CompoundTag, Map<String, Tag<?>>> items = entity.getList("Items", CompoundTag.class, null);
        if (items == null || items.size() == 0) {
            return;
        }

        ListTag<CompoundTag, Map<String, Tag<?>>> output = new ListTag<>(TagType.COMPOUND, items.size());
        for (CompoundTag javaItem : items) {
            CompoundTag item = ItemStacks.convert(javaItem, source, target);
            if (item == null) {
                // Unknown or empty stack: skip it, but keep the other slots where they were.
                continue;
            }
            // The slot travels with the item on both sides; without it the list order decides, which shifts anything
            // that was not stored contiguously.
            item.put("Slot", (byte) intValue(javaItem, "Slot", output.size()));
            output.add(item);
        }

        if (output.size() > 0) {
            actor.put("Items", output);
        }
    }

    private static int intValue(CompoundTag entity, String name, int defaultValue) {
        Object value = entity.contains(name) ? entity.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
