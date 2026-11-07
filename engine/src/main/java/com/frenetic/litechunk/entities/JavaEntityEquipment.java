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
 * Reads the equipment of a Bedrock actor into the Java form.
 * <p>
 * This is the mirror of {@link EntityEquipment}. Bedrock keeps three lists - {@code Mainhand} and {@code Offhand}
 * with one entry each, and {@code Armor} with exactly five in the order head, chest, legs, feet, body. Java either
 * has one {@code equipment} compound keyed by slot name (1.21.5 and later) or the two legacy lists
 * {@code HandItems} (main, off) and {@code ArmorItems} (feet, legs, chest, head), plus {@code ArmorItem} for the body
 * slot a horse's armour lives in.
 * <p>
 * The empty entries matter in both directions. A Bedrock save always writes all five armour slots, and a short legacy
 * {@code ArmorItems} list would shift every piece down, so the legacy list is always padded to four.
 */
final class JavaEntityEquipment {

    /** Bedrock order. Pinned by a real save: the helmet sits at index 0 of a mob's Armor list. */
    private static final String[] BEDROCK_ARMOUR_SLOTS = {"head", "chest", "legs", "feet", "body"};

    /** Index of the body slot, which holds horse armour, wolf armour and the saddle on Java. */
    private static final int BODY_SLOT = 4;

    private static final int LEGACY_ARMOUR_SLOTS = 4;

    private JavaEntityEquipment() {
    }

    /**
     * Apply the equipment carried by a Bedrock actor.
     *
     * @param javaEntity the Java entity tag being built, modified in place.
     * @param actor      the Bedrock actor tag that was read.
     * @param source     resolvers of the Bedrock version that was read, may be null.
     * @param target     resolvers of the Java version being written, may be null.
     * @param modern     true when the target uses the 1.21.5 {@code equipment} compound.
     */
    static void apply(CompoundTag javaEntity, CompoundTag actor,
                      @Nullable BedrockResolvers source, @Nullable JavaResolvers target, boolean modern) {
        if (source == null || target == null) {
            // Without both item resolvers nothing can be translated, and writing the Bedrock item into a Java world
            // would give the game a tag it cannot read. Empty slots are the safe outcome.
            return;
        }

        CompoundTag mainhand = fromList(actor, "Mainhand", 0, source, target);
        CompoundTag offhand = fromList(actor, "Offhand", 0, source, target);

        CompoundTag[] armour = new CompoundTag[BEDROCK_ARMOUR_SLOTS.length];
        ListTag<CompoundTag, ?> bedrockArmour = actor.getList("Armor", CompoundTag.class, null);
        if (bedrockArmour != null) {
            for (int index = 0; index < armour.length && index < bedrockArmour.size(); index++) {
                armour[index] = JavaItemStacks.convert(bedrockArmour.get(index), source, target);
            }
        }

        boolean anyArmour = false;
        for (CompoundTag slot : armour) {
            anyArmour |= slot != null;
        }
        if (mainhand == null && offhand == null && !anyArmour) {
            return;
        }

        if (modern) {
            modern(javaEntity, mainhand, offhand, armour);
        } else {
            legacy(javaEntity, mainhand, offhand, armour);
        }
    }

    /**
     * 1.21.5 and later: one compound, slot names as keys.
     */
    private static void modern(CompoundTag javaEntity, @Nullable CompoundTag mainhand, @Nullable CompoundTag offhand,
                               CompoundTag[] armour) {
        CompoundTag equipment = new CompoundTag(7);
        put(equipment, "mainhand", mainhand);
        put(equipment, "offhand", offhand);
        for (int index = 0; index < BEDROCK_ARMOUR_SLOTS.length; index++) {
            put(equipment, BEDROCK_ARMOUR_SLOTS[index], armour[index]);
        }
        if (equipment.size() > 0) {
            javaEntity.put("equipment", equipment);
        }
    }

    /**
     * Before 1.21.5: {@code HandItems} (main, off), {@code ArmorItems} (feet, legs, chest, head) and {@code ArmorItem}
     * for the body slot.
     */
    private static void legacy(CompoundTag javaEntity, @Nullable CompoundTag mainhand, @Nullable CompoundTag offhand,
                               CompoundTag[] armour) {
        ListTag<CompoundTag, Map<String, Tag<?>>> hands = new ListTag<>(TagType.COMPOUND, 2);
        hands.add(mainhand == null ? new CompoundTag() : mainhand);
        hands.add(offhand == null ? new CompoundTag() : offhand);
        if (mainhand != null || offhand != null) {
            javaEntity.put("HandItems", hands);
        }

        ListTag<CompoundTag, Map<String, Tag<?>>> armourItems = new ListTag<>(TagType.COMPOUND, LEGACY_ARMOUR_SLOTS);
        boolean any = false;
        for (int legacyIndex = 0; legacyIndex < LEGACY_ARMOUR_SLOTS; legacyIndex++) {
            // Legacy order is feet first, Bedrock's first four are head first - the same flip EntityEquipment makes
            // when it writes them, so a helmet does not end up on the mob's feet.
            CompoundTag item = armour[EntityEquipment.legacySlot(legacyIndex)];
            armourItems.add(item == null ? new CompoundTag() : item);
            any |= item != null;
        }
        if (any) {
            javaEntity.put("ArmorItems", armourItems);
        }

        // The body slot had a field of its own before the unified equipment compound. A saddle is not written here:
        // EntityStates puts it there, because on Bedrock it is a flag and it would never appear in Armor anyway.
        if (armour[BODY_SLOT] != null) {
            javaEntity.put("ArmorItem", armour[BODY_SLOT]);
        }
    }

    @Nullable
    private static CompoundTag fromList(CompoundTag actor, String name, int index,
                                        BedrockResolvers source, JavaResolvers target) {
        ListTag<CompoundTag, ?> list = actor.getList(name, CompoundTag.class, null);
        if (list == null || index >= list.size()) {
            return null;
        }
        return JavaItemStacks.convert(list.get(index), source, target);
    }

    private static void put(CompoundTag equipment, String slot, @Nullable CompoundTag item) {
        if (item != null) {
            equipment.put(slot, item);
        }
    }
}
