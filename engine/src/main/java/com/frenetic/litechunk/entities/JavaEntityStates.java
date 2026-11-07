package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * Reads the creature state that is neither a variant, an item slot nor a container back into the Java form.
 * <p>
 * This is the mirror of {@link EntityStates}, and every one of the four is a different shape on the two sides:
 * <ul>
 *     <li>the <strong>saddle</strong> is a {@code Saddled} flag on Bedrock but an item in the Java body slot since
 *     1.21.5 (before that it had a {@code SaddleItem} field of its own);</li>
 *     <li>the <strong>collar</strong> is the generic {@code Color} byte on Bedrock and {@code CollarColor} on
 *     Java;</li>
 *     <li><strong>sitting</strong> is a flag with the same spelling on both sides, so it is only a rename of the
 *     numeric type;</li>
 *     <li>a <strong>villager</strong> carries {@code VillagerData{profession, level, type}} on Java, while Bedrock
 *     spreads the same information over two component groups and a {@code TradeTier} counter.</li>
 * </ul>
 * Trades are not converted in either direction: Java's {@code Offers} is a recipe list and Bedrock generates its own
 * from the profession and level.
 */
final class JavaEntityStates {

    private static final String NAMESPACE = "minecraft:";
    private static final String SADDLE = "minecraft:saddle";

    /**
     * Creatures that take a saddle. The Bedrock identifier is the Java one apart from the two villager-suffixed
     * types, which do not wear saddles.
     */
    private static final Set<String> MOUNTS = Set.of(
            "minecraft:pig", "minecraft:horse", "minecraft:donkey", "minecraft:mule",
            "minecraft:camel", "minecraft:strider", "minecraft:skeleton_horse", "minecraft:zombie_horse");

    /** Bedrock component groups that name a villager profession. Java uses the same names under {@code minecraft:}. */
    private static final Set<String> PROFESSIONS = Set.of(
            "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman", "fletcher", "leatherworker",
            "librarian", "mason", "nitwit", "none", "shepherd", "toolsmith", "weaponsmith");

    /** Bedrock names the biome group {@code <biome>_villager}; Java keeps only the biome. */
    private static final Set<String> BIOMES = Set.of(
            "desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga");

    private static final String VILLAGER_SUFFIX = "_villager";

    private JavaEntityStates() {
    }

    /**
     * Apply the state fields this class understands.
     *
     * @param javaEntity the Java entity tag being built, modified in place.
     * @param actor      the Bedrock actor tag that was read.
     * @param identifier the Bedrock identifier of the actor.
     * @param version    the Java version being written.
     * @param source     resolvers of the Bedrock version that was read, may be null.
     * @param target     resolvers of the Java version being written, may be null.
     */
    static void apply(CompoundTag javaEntity, CompoundTag actor, String identifier, Version version,
                      @Nullable BedrockResolvers source, @Nullable JavaResolvers target) {
        boolean modern = version.isGreaterThanOrEqual(1, 21, 5);
        saddle(javaEntity, actor, identifier, modern, source, target);
        collar(javaEntity, actor, identifier);
        sitting(javaEntity, actor);
        villager(javaEntity, actor, version);
    }

    // ---------------------------------------------------------------------------------------------
    // Saddle
    // ---------------------------------------------------------------------------------------------

    private static void saddle(CompoundTag javaEntity, CompoundTag actor, String identifier, boolean modern,
                               @Nullable BedrockResolvers source, @Nullable JavaResolvers target) {
        if (!MOUNTS.contains(identifier) || intValue(actor, "Saddled", 0) == 0) {
            return;
        }
        // The saddle is an ordinary item on Java, so it goes through the same item resolvers as everything else
        // rather than being spelled out here - the equipped-item shape changed in 1.20.5 and again in 1.21.5.
        CompoundTag saddle = JavaItemStacks.convert(JavaItemStacks.plain(SADDLE), source, target);
        if (saddle == null) {
            return;
        }
        if (modern) {
            CompoundTag equipment = javaEntity.getCompound("equipment", null);
            if (equipment == null) {
                equipment = new CompoundTag(1);
                javaEntity.put("equipment", equipment);
            }
            equipment.put("body", saddle);
        } else {
            javaEntity.put("SaddleItem", saddle);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Collar
    // ---------------------------------------------------------------------------------------------

    private static void collar(CompoundTag javaEntity, CompoundTag actor, String identifier) {
        if (!"minecraft:cat".equals(identifier) && !"minecraft:wolf".equals(identifier)) {
            return;
        }
        if (!actor.contains("Color")) {
            return;
        }
        int colour = intValue(actor, "Color", -1);
        if (colour < 0 || colour > 15) {
            return;
        }
        javaEntity.put("CollarColor", (byte) colour);
    }

    // ---------------------------------------------------------------------------------------------
    // Sitting
    // ---------------------------------------------------------------------------------------------

    private static void sitting(CompoundTag javaEntity, CompoundTag actor) {
        if (intValue(actor, "Sitting", 0) == 0) {
            return;
        }
        // Bedrock and Java agree on the name and the meaning; the camel's pose group is a Bedrock detail that the
        // Java side expresses with this same flag.
        javaEntity.put("Sitting", (byte) 1);
    }

    // ---------------------------------------------------------------------------------------------
    // Villager
    // ---------------------------------------------------------------------------------------------

    private static void villager(CompoundTag javaEntity, CompoundTag actor, Version version) {
        if (!version.isGreaterThanOrEqual(1, 14, 0)) {
            // Before 1.14 a villager had a numeric Profession and Career instead of VillagerData. Those worlds are
            // ancient, and writing the newer shape into them would make the game drop the entity.
            return;
        }

        List<String> definitions = actor.getListValues("definitions", StringTag.class, null);
        String profession = null;
        String biome = null;
        if (definitions != null) {
            for (String definition : definitions) {
                String name = bare(componentGroup(definition));
                if (name == null) {
                    continue;
                }
                if (PROFESSIONS.contains(name)) {
                    profession = name;
                } else if (name.endsWith(VILLAGER_SUFFIX)) {
                    String candidate = name.substring(0, name.length() - VILLAGER_SUFFIX.length());
                    if (BIOMES.contains(candidate)) {
                        biome = candidate;
                    }
                }
            }
        }

        boolean tiered = actor.contains("TradeTier");
        if (profession == null && biome == null && !tiered) {
            return;
        }

        CompoundTag data = new CompoundTag(3);
        data.put("profession", NAMESPACE + (profession == null ? "none" : profession));
        data.put("type", NAMESPACE + (biome == null ? "plains" : biome));
        // Bedrock keeps the level as a zero based tier (0 = novice); Java stores the level itself, one based.
        data.put("level", Math.max(1, Math.min(5, intValue(actor, "TradeTier", 0) + 1)));
        javaEntity.put("VillagerData", data);

        int experience = intValue(actor, "TradeExperience", 0);
        if (experience > 0) {
            javaEntity.put("Xp", experience);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * @param definition one entry of a Bedrock definition list, e.g. {@code +minecraft:farmer}.
     * @return the same name without the leading {@code +} and the namespace, or null when it is not a component group.
     */
    @Nullable
    private static String componentGroup(@Nullable String definition) {
        if (definition == null || definition.isEmpty() || definition.charAt(0) != '+') {
            return null;
        }
        return definition.substring(1);
    }

    @Nullable
    private static String bare(@Nullable String identifier) {
        if (identifier == null) {
            return null;
        }
        return identifier.startsWith(NAMESPACE) ? identifier.substring(NAMESPACE.length()) : identifier;
    }

    /**
     * Bedrock keeps several of these counters as bytes or ints depending on the entity, so they are read as numbers.
     */
    private static int intValue(CompoundTag tag, String name, int defaultValue) {
        Object value = tag.contains(name) ? tag.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
