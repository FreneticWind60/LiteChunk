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
 * <strong>The Java side is now confirmed; the Bedrock side is still inferred.</strong> Summoning a donkey with
 * {@code ChestedHorse:1b} and an {@code Items} list into 1.21.8 and reading the save back shows the game keeps both -
 * and that {@code Chested} (the spelling used before 1.21.5) is dropped along with any inventory that came with it.
 * What has not been observed is a Bedrock actor with a chest, so the shape written here is taken from the engine's own
 * container serialisation rather than from a live one. The failure mode if it is wrong is that the contents simply do
 * not appear - the same outcome as not writing them at all - which is why it is worth shipping before it can be
 * confirmed.
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
                // A minecart's inventory is called `ChestItems` on Bedrock, not `Items`. This is not a guess: a world
                // the game saved itself carries ChestItems on both the chest and the hopper cart, and an actor written
                // with `Items` comes back from the game with that list dropped entirely - which is why converted chest
                // minecarts arrived empty.
                if (source != null && target != null) {
                    items(actor, javaEntity, source, target, "ChestItems");
                }
            }
            case "minecraft:llama", "minecraft:trader_llama", "minecraft:donkey", "minecraft:mule" -> {
                // The chest has to be visible before the contents make sense, and Java stores that as its own flag.
                // 1.21.8 spells it `ChestedHorse`; `Chested` was the earlier one and is still read so old worlds keep
                // their donkey's inventory.
                int chested = javaEntity.contains("ChestedHorse")
                        ? intValue(javaEntity, "ChestedHorse", 0)
                        : intValue(javaEntity, "Chested", 0);
                if (chested != 0) {
                    actor.put("Chested", (byte) 1);
                }
                if (source != null && target != null) {
                    // A chested animal is the one container Bedrock spells `Items`, because its inventory component is
                    // of the `horse` type rather than one of the minecart ones.
                    items(actor, javaEntity, source, target, "Items");
                }
            }
            default -> {
                // Not a container.
            }
        }
    }

    /**
     * Translate the items list into the field the target container uses.
     *
     * @param actor   the actor tag, modified in place.
     * @param entity  the Java entity NBT.
     * @param source  resolvers of the Java version that was read.
     * @param target  resolvers of the Bedrock version being written.
     * @param field   the Bedrock field name, {@code ChestItems} for a minecart and {@code Items} for an animal.
     */
    private static void items(CompoundTag actor, CompoundTag entity, JavaResolvers source, BedrockResolvers target,
                              String field) {
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
            actor.put(field, output);
        }
    }

    private static int intValue(CompoundTag entity, String name, int defaultValue) {
        Object value = entity.contains(name) ? entity.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
