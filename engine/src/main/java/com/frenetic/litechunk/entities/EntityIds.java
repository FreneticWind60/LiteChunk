package com.frenetic.litechunk.entities;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Java entity id to Bedrock identifier.
 * <p>
 * Most of the 129 vanilla entities share their id, so the table only lists the ones that do not. The engine already
 * has richer resolvers for this ({@code JavaEntityTypeResolver} / {@code BedrockEntityTypeResolver}), but they drag
 * in the whole version/resolver graph; the entity pass runs on its own and only needs the id, so a table is both
 * smaller and easier to audit against the vanilla data.
 * <p>
 * Unknown ids fall through unchanged. The actor is then written with an id the game does not know, which it drops
 * silently - the same outcome as an unmapped entity in the old path, but without taking the column down with it.
 */
final class EntityIds {

    private static final Map<String, String> BEDROCK = new HashMap<>();

    static {
        // Renamed between editions.
        BEDROCK.put("minecraft:zombified_piglin", "minecraft:zombie_pigman");
        BEDROCK.put("minecraft:evoker", "minecraft:evocation_illager");
        BEDROCK.put("minecraft:experience_orb", "minecraft:xp_orb");
        BEDROCK.put("minecraft:experience_bottle", "minecraft:xp_bottle");
        BEDROCK.put("minecraft:eye_of_ender", "minecraft:eye_of_ender_signal");
        BEDROCK.put("minecraft:firework_rocket", "minecraft:fireworks_rocket");
        BEDROCK.put("minecraft:fishing_bobber", "minecraft:fishing_hook");
        BEDROCK.put("minecraft:trident", "minecraft:thrown_trident");
        BEDROCK.put("minecraft:potion", "minecraft:splash_potion");

        // Same entity, different spelling. Both were missed until a real 1.21.8 world was converted: the actor kept a
        // Java id the game has never heard of, so the entity was written and then silently dropped on load.
        BEDROCK.put("minecraft:tropical_fish", "minecraft:tropicalfish");
        BEDROCK.put("minecraft:end_crystal", "minecraft:ender_crystal");

        // Bedrock only has the second generation of these two.
        BEDROCK.put("minecraft:villager", "minecraft:villager_v2");
        BEDROCK.put("minecraft:zombie_villager", "minecraft:zombie_villager_v2");

        // Java splits boats by wood type, Bedrock keeps one entity and a variant number (see EntityVariants.boat for
        // the index table). Bamboo is a raft in Java but the same `minecraft:boat` actor on Bedrock - the behaviour
        // pack picks the can_ride_bamboo group with `is_variant != 7`, which is where the 7 comes from.
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
                "cherry", "pale_oak", "poplar"}) {
            BEDROCK.put("minecraft:" + wood + "_boat", "minecraft:boat");
            BEDROCK.put("minecraft:" + wood + "_chest_boat", "minecraft:chest_boat");
        }
        BEDROCK.put("minecraft:bamboo_raft", "minecraft:boat");
        BEDROCK.put("minecraft:bamboo_chest_raft", "minecraft:chest_boat");

        // Java's mannequin (a static player-shaped display entity) has no Bedrock counterpart: Bedrock's only
        // player-shaped actor is the NPC, which is a dialogue prop with sixty fixed skins. Mapping it keeps the
        // position, the rotation and the name; the skin cannot follow, because the client picks it from
        // `Array.skins[query.skin_id]` - a closed list under textures/entity/npc.
        BEDROCK.put("minecraft:mannequin", "minecraft:npc");
    }

    private EntityIds() {
    }

    /**
     * @param javaId the Java identifier, e.g. {@code minecraft:pig}.
     * @return the Bedrock identifier, or null when the input has no id.
     */
    @Nullable
    static String toBedrock(@Nullable String javaId) {
        if (javaId == null) {
            return null;
        }
        return BEDROCK.getOrDefault(javaId, javaId);
    }
}
