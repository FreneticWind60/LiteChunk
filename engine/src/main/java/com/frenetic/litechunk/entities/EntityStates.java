package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Creature state that is neither a variant, an item slot nor a container: saddles, collar colours, sitting and the
 * villager's job.
 * <p>
 * All four are stored differently on the two sides, and none of them is a plain field copy:
 * <ul>
 *     <li>the <strong>saddle</strong> is an item in the Java {@code equipment.body} slot since 1.21.5 (confirmed by
 *     summoning a saddled pig and a saddled horse into 1.21.8 and reading back what the game kept: {@code Saddle}
 *     and {@code SaddleItem} are both dropped, {@code equipment.body} survives), but on Bedrock it is a
 *     {@code Saddled} flag plus a {@code <mob>_saddled} component group;</li>
 *     <li>the <strong>collar</strong> is {@code CollarColor} on Java and the generic {@code Color} byte on Bedrock;</li>
 *     <li><strong>sitting</strong> is a byte on both sides, but a sitting camel additionally swaps
 *     {@code camel_standing} for {@code camel_sitting};</li>
 *     <li>a <strong>villager</strong> carries {@code VillagerData{profession, level, type}} on Java, while Bedrock
 *     turns the job into a bare component group ({@code farmer}, {@code librarian}) and the biome into a
 *     {@code <biome>_villager} one, with the level living in {@code TradeTier}.</li>
 * </ul>
 * The trades themselves are handled by {@link EntityTrades}: a villager the player never traded with is still left to
 * the game, which builds a fuller list from the profession and level than a copy of Java's could be.
 */
final class EntityStates {

    private static final String NAMESPACE = "minecraft:";
    private static final String SADDLE = "minecraft:saddle";

    /**
     * Creatures that take a saddle. The group name is the identifier plus {@code _saddled}, which the behaviour pack
     * defines for exactly these.
     */
    private static final Set<String> MOUNTS = Set.of(
            "minecraft:pig", "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:camel", "minecraft:strider", "minecraft:skeleton_horse", "minecraft:zombie_horse");

    private EntityStates() {
    }

    /**
     * Apply the state fields this class understands.
     *
     * @param actor       the actor tag being built, modified in place.
     * @param identifier  the Bedrock identifier.
     * @param javaEntity  the Java entity NBT.
     * @param definitions the definition list being built, modified in place.
     */
    static void apply(CompoundTag actor, String identifier, CompoundTag javaEntity, List<String> definitions) {
        saddle(actor, identifier, javaEntity, definitions);
        collar(actor, identifier, javaEntity);
        sitting(actor, identifier, javaEntity, definitions);
        villager(actor, identifier, javaEntity, definitions);
    }

    // ---------------------------------------------------------------------------------------------
    // Saddle
    // ---------------------------------------------------------------------------------------------

    private static void saddle(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        if (!MOUNTS.contains(identifier)) {
            return;
        }
        CompoundTag body = bodySlot(entity);
        if (body == null || !SADDLE.equals(body.getString("id", null))) {
            return;
        }
        actor.put("Saddled", (byte) 1);
        // A saddle only means something on a tamed animal, and the pack keeps the two states in exclusive groups.
        EntityVariants.saddled(definitions, identifier);
    }

    /**
     * The body equipment slot, which is where a mount's saddle and its armour live since 1.21.5.
     * <p>
     * Before that the two had fields of their own, so they are read as a fallback rather than ignored: an old world
     * would otherwise convert every saddled horse into a wild one.
     *
     * @param entity the entity tag.
     * @return the item in the body slot, or null when there is none.
     */
    @Nullable
    static CompoundTag bodySlot(CompoundTag entity) {
        CompoundTag equipment = entity.getCompound("equipment", null);
        if (equipment != null) {
            CompoundTag body = equipment.getCompound("body", null);
            if (body != null) {
                return body;
            }
        }
        CompoundTag saddle = entity.getCompound("SaddleItem", null);
        if (saddle != null) {
            return saddle;
        }
        return entity.getCompound("ArmorItem", null);
    }

    // ---------------------------------------------------------------------------------------------
    // Collar
    // ---------------------------------------------------------------------------------------------

    private static void collar(CompoundTag actor, String identifier, CompoundTag entity) {
        if (!"minecraft:cat".equals(identifier) && !"minecraft:wolf".equals(identifier)) {
            return;
        }
        if (!entity.contains("CollarColor")) {
            return;
        }
        // The collar has a key of its own on Bedrock (`CollarColor` is a literal in the game binary, next to the cat
        // and wolf component groups). Writing `Color` instead tinted the whole animal: that is the generic entity
        // colour channel the renderer multiplies into the body texture, which is why a red-collared cat arrived as a
        // red cat. Java spells the field the same way, and the dye order matches, so the value goes straight across.
        int colour = intValue(entity, "CollarColor", 0);
        actor.put("CollarColor", (byte) Math.max(0, Math.min(15, colour)));
    }

    // ---------------------------------------------------------------------------------------------
    // Sitting
    // ---------------------------------------------------------------------------------------------

    private static void sitting(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        if (intValue(entity, "Sitting", 0) == 0) {
            return;
        }
        actor.put("Sitting", (byte) 1);
        if ("minecraft:camel".equals(identifier)) {
            // The camel is the one mob whose pose is a component group pair rather than a flag the game reads: it
            // spawns with `camel_standing` from the table, so that has to be taken back off.
            EntityVariants.addGroup(definitions, identifier, "camel_sitting");
            EntityVariants.removeGroup(definitions, identifier, "camel_standing");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Villager
    // ---------------------------------------------------------------------------------------------

    private static void villager(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        CompoundTag data = entity.getCompound("VillagerData", null);
        if (data == null) {
            return;
        }
        String profession = bare(data.getString("profession", null));
        if (profession != null && !profession.isEmpty() && !"none".equals(profession)) {
            EntityVariants.addGroup(definitions, identifier, profession);
        }

        // The biome variant is a group of its own, but plains is the default and the pack has no group for it.
        String type = bare(data.getString("type", null));
        if (type != null && !type.isEmpty() && !"plains".equals(type)) {
            EntityVariants.addGroup(definitions, identifier, type + "_villager");
        }

        // Bedrock keeps the level as a zero based tier (0 = novice) and a separate experience counter; Java stores the
        // level itself, 1 based, plus the experience the villager has towards the next trade.
        int level = intValue(data, "level", 1);
        actor.put("TradeTier", Math.max(0, Math.min(4, level - 1)));

        int experience = intValue(entity, "Xp", 0);
        if (experience > 0) {
            actor.put("TradeExperience", experience);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * @param identifier a namespaced identifier.
     * @return the same identifier without the {@code minecraft:} prefix.
     */
    private static String bare(String identifier) {
        return identifier.startsWith(NAMESPACE) ? identifier.substring(NAMESPACE.length()) : identifier;
    }

    /**
     * Java stores several of these counters as bytes or shorts depending on the entity, so they are read as numbers.
     */
    private static int intValue(CompoundTag entity, String name, int defaultValue) {
        Object value = entity.contains(name) ? entity.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
