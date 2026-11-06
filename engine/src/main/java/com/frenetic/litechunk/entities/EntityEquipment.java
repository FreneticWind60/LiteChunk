package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Writes the equipment of an actor: what it holds and what it wears.
 * <p>
 * The two formats do not line up one to one:
 * <ul>
 *     <li>Since 1.21.5 Java keeps everything in a single {@code equipment} compound keyed by slot name
 *     ({@code mainhand}, {@code offhand}, {@code head}, {@code chest}, {@code legs}, {@code feet}, {@code body}).</li>
 *     <li>Before that it was two fixed lists: {@code HandItems} (main hand, off hand) and {@code ArmorItems}
 *     (feet, legs, chest, head).</li>
 *     <li>Bedrock uses three lists: {@code Mainhand} and {@code Offhand} with one entry each, and {@code Armor} with
 *     exactly five, ordered head, chest, legs, feet, body. A real save always writes all five, filling the empty ones
 *     with {@code {Name:"",Count:0b,Damage:0s}}.</li>
 * </ul>
 * The empty entries matter: a short {@code Armor} list shifts every piece by one, which is how a converted world ends
 * up wearing leggings on its head. So when any armour is present the list is padded to five.
 * <p>
 * Everything goes through {@link ItemStacks}, so the same item mapping the terrain pass used for chest contents is
 * used here; a piglin's crossbow has to come out with the same name in both places.
 */
final class EntityEquipment {

    /** Bedrock order. Pinned by a real save: the helmet sits at index 0 of a mob's Armor list. */
    private static final String[] JAVA_ARMOUR_SLOTS = {"head", "chest", "legs", "feet", "body"};

    /** Legacy Java {@code ArmorItems} order, which is bottom up. */
    private static final String[] LEGACY_ARMOUR_SLOTS = {"feet", "legs", "chest", "head"};

    /** Index of the body slot, which holds horse armour, wolf armour and - on Java - the saddle. */
    private static final int BODY_SLOT = 4;

    private static final String SADDLE = "minecraft:saddle";

    private EntityEquipment() {
    }

    /**
     * @param item an item tag.
     * @return true when the stack is a saddle, which Bedrock stores as a flag rather than as armour.
     */
    static boolean isSaddle(@Nullable CompoundTag item) {
        return item != null && SADDLE.equals(item.getString("id", null));
    }

    /**
     * Apply the equipment of an entity.
     *
     * @param actor      the actor tag being built, modified in place.
     * @param javaEntity the Java entity NBT.
     * @param source     resolvers of the Java version that was read, may be null.
     * @param target     resolvers of the Bedrock version being written, may be null.
     */
    static void apply(CompoundTag actor, CompoundTag javaEntity,
                      @Nullable JavaResolvers source, @Nullable BedrockResolvers target) {
        if (source == null || target == null) {
            // Without the item resolvers nothing can be translated, and writing the raw Java item would give the game
            // a tag it cannot read. Leaving the slots empty is the safe outcome.
            return;
        }

        CompoundTag equipment = javaEntity.getCompound("equipment", null);
        if (equipment != null) {
            write(actor, equipment, source, target);
        } else {
            writeLegacy(actor, javaEntity, source, target);
        }
    }

    /**
     * 1.21.5 and later: one compound, slot names as keys.
     */
    private static void write(CompoundTag actor, CompoundTag equipment,
                              JavaResolvers source, BedrockResolvers target) {
        CompoundTag mainhand = ItemStacks.convert(equipment.getCompound("mainhand", null), source, target);
        if (mainhand != null) {
            actor.put("Mainhand", single(mainhand));
        }
        CompoundTag offhand = ItemStacks.convert(equipment.getCompound("offhand", null), source, target);
        if (offhand != null) {
            actor.put("Offhand", single(offhand));
        }

        CompoundTag[] armour = new CompoundTag[JAVA_ARMOUR_SLOTS.length];
        boolean any = false;
        for (int index = 0; index < JAVA_ARMOUR_SLOTS.length; index++) {
            CompoundTag item = equipment.getCompound(JAVA_ARMOUR_SLOTS[index], null);
            if (index == BODY_SLOT && isSaddle(item)) {
                // A mount's saddle travels in the body slot on Java but is a flag on Bedrock, not armour: putting it
                // in Armor would make a horse wear a saddle on its back instead of being ridden. EntityStates writes
                // the flag; skipping it here keeps the two from disagreeing.
                continue;
            }
            armour[index] = ItemStacks.convert(item, source, target);
            any |= armour[index] != null;
        }
        if (any) {
            actor.put("Armor", armourList(armour));
        }
    }

    /**
     * Before 1.21.5: {@code HandItems} (main, off) and {@code ArmorItems} (feet, legs, chest, head).
     */
    private static void writeLegacy(CompoundTag actor, CompoundTag entity,
                                    JavaResolvers source, BedrockResolvers target) {
        ListTag<CompoundTag, ?> hands = entity.getList("HandItems", CompoundTag.class, null);
        if (hands != null && hands.size() > 0) {
            CompoundTag mainhand = ItemStacks.convert(get(hands, 0), source, target);
            if (mainhand != null) {
                actor.put("Mainhand", single(mainhand));
            }
            CompoundTag offhand = ItemStacks.convert(get(hands, 1), source, target);
            if (offhand != null) {
                actor.put("Offhand", single(offhand));
            }
        }

        CompoundTag[] armour = new CompoundTag[JAVA_ARMOUR_SLOTS.length];
        boolean any = false;
        ListTag<CompoundTag, ?> armourItems = entity.getList("ArmorItems", CompoundTag.class, null);
        if (armourItems != null) {
            for (int index = 0; index < LEGACY_ARMOUR_SLOTS.length; index++) {
                CompoundTag item = ItemStacks.convert(get(armourItems, index), source, target);
                if (item == null) {
                    continue;
                }
                armour[legacySlot(index)] = item;
                any = true;
            }
        }

        // Horse armour and wolf armour had their own field before 1.21.5 and belong in the same body slot the newer
        // format uses. The saddle is the exception: it is a flag, so EntityStates writes it instead of this.
        CompoundTag body = entity.getCompound("ArmorItem", null);
        if (body != null && !isSaddle(body)) {
            CompoundTag item = ItemStacks.convert(body, source, target);
            if (item != null) {
                armour[BODY_SLOT] = item;
                any = true;
            }
        }

        if (any) {
            actor.put("Armor", armourList(armour));
        }
    }

    /**
     * Map a legacy {@code ArmorItems} index to the Bedrock {@code Armor} index.
     * <p>
     * Legacy order is feet, legs, chest, head; Bedrock's first four slots are head, chest, legs, feet. The fifth
     * Bedrock slot (body, for wolf and horse armour) has no legacy equivalent.
     *
     * @param legacyIndex 0..3, feet first.
     * @return 0..3, head first.
     */
    static int legacySlot(int legacyIndex) {
        return 3 - legacyIndex;
    }

    /**
     * @param item an item tag.
     * @return a one entry list, which is how Bedrock stores the two hands.
     */
    private static ListTag<CompoundTag, Map<String, Tag<?>>> single(CompoundTag item) {
        ListTag<CompoundTag, Map<String, Tag<?>>> list = new ListTag<>(TagType.COMPOUND, 1);
        list.add(item);
        return list;
    }

    /**
     * @param armour the five slots in Bedrock order, with nulls for empty ones.
     * @return the fixed length list a real save has.
     */
    private static ListTag<CompoundTag, Map<String, Tag<?>>> armourList(CompoundTag[] armour) {
        ListTag<CompoundTag, Map<String, Tag<?>>> list = new ListTag<>(TagType.COMPOUND, armour.length);
        for (CompoundTag slot : armour) {
            list.add(slot == null ? emptyItem() : slot);
        }
        return list;
    }

    /**
     * @return the placeholder a real save uses for an empty slot.
     */
    private static CompoundTag emptyItem() {
        CompoundTag item = new CompoundTag(4);
        item.put("Name", "");
        item.put("Count", (byte) 0);
        item.put("Damage", (short) 0);
        return item;
    }

    @Nullable
    private static CompoundTag get(ListTag<CompoundTag, ?> list, int index) {
        return index < list.size() ? list.get(index) : null;
    }
}
