package com.frenetic.litechunk.commands;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Particle name mapping between the two editions.
 * <p>
 * The names are completely different vocabularies - Java's {@code flame} is Bedrock's
 * {@code basic_flame_particle}, Java's {@code totem_of_undying} is Bedrock's {@code totem_particle} - so a
 * {@code /particle} command copied across verbatim never shows anything: the game rejects the name and, because the
 * argument list also differs, usually rejects the whole command.
 * <p>
 * <strong>What this table is.</strong> Only pairs that describe the same visual effect. Where Bedrock has no
 * counterpart (its biome-ambient dusts such as {@code ash} and {@code crimson_spore} are tied to client-side biome
 * rendering, not to a spawnable particle) the entry is deliberately absent: converting those to a lookalike would
 * trade "nothing happens" for "the wrong thing happens", which is worse to debug. A missing name leaves the command
 * untouched so the user can see which one did not survive.
 * <p>
 * Sources: the Java particle list and the Bedrock particle list on the Minecraft Wiki, cross-checked against the ids
 * that exist in the 1.26.62 resource pack ({@code resource_packs/vanilla/particles.brarchive}).
 */
final class ParticleIds {

    private static final Map<String, String> JAVA_TO_BEDROCK = new HashMap<>(192);
    private static final Map<String, String> BEDROCK_TO_JAVA = new HashMap<>(192);

    /**
     * How many tokens of options an old-format Java command carries between the name and the position.
     * <p>
     * Since 1.20.5 the options live in an SNBT compound glued to the name ({@code dust{color:[...],scale:1}}), so the
     * count is only consulted for the older spelling where they are separate arguments. Anything not listed here
     * takes no options.
     */
    private static final Map<String, Integer> JAVA_OPTION_COUNT = new HashMap<>(16);

    static {
        // ---- blocks of similar effects -------------------------------------------------------------
        pair("flame", "basic_flame_particle");
        pair("small_flame", "small_flame_particle");
        pair("soul_fire_flame", "small_soul_fire_flame");
        pair("soul", "soul_particle");
        pair("copper_fire_flame", "green_flame_particle");
        pair("smoke", "basic_smoke_particle");
        pair("large_smoke", "basic_smoke_particle");
        pair("white_smoke", "white_smoke_particle");
        pair("campfire_cosy_smoke", "campfire_smoke_particle");
        pair("campfire_signal_smoke", "campfire_tall_smoke_particle");
        pair("poof", "explosion_manual");
        pair("lava", "lava_particle");
        pair("dripping_lava", "lava_drip_particle");
        pair("falling_lava", "lava_drip_particle");
        pair("landing_lava", "lava_drip_particle");
        pair("dripping_dripstone_lava", "stalactite_lava_drip_particle");
        pair("falling_dripstone_lava", "stalactite_lava_drip_particle");
        pair("dripping_water", "water_drip_particle");
        pair("falling_water", "water_drip_particle");
        pair("dripping_dripstone_water", "stalactite_water_drip_particle");
        pair("falling_dripstone_water", "stalactite_water_drip_particle");
        pair("dripping_honey", "honey_drip_particle");
        pair("falling_honey", "honey_drip_particle");
        pair("landing_honey", "honey_drip_particle");
        pair("falling_nectar", "nectar_drip_particle");
        pair("dripping_obsidian_tear", "obsidian_tear_particle");
        pair("falling_obsidian_tear", "obsidian_tear_particle");
        pair("landing_obsidian_tear", "obsidian_tear_particle");

        // ---- water ---------------------------------------------------------------------------------
        pair("bubble", "basic_bubble_particle");
        pair("bubble_pop", "basic_bubble_particle_manual");
        pair("bubble_column_up", "bubble_column_up_particle");
        pair("current_down", "bubble_column_down_particle");
        pair("splash", "water_splash_particle");
        pair("fishing", "fish_hook_particle");
        pair("underwater", "water_wake_particle");
        pair("dolphin", "dolphin_move_particle");
        pair("nautilus", "conduit_particle");
        pair("squid_ink", "ink_emitter");
        pair("glow_squid_ink", "ink_emitter");
        pair("rain", "rain_splash_particle");

        // ---- combat / magic ------------------------------------------------------------------------
        pair("crit", "basic_crit_particle");
        pair("enchanted_hit", "magic_critical_hit_emitter");
        pair("damage_indicator", "critical_hit_emitter");
        pair("sweep_attack", "critical_hit_emitter");
        pair("explosion", "explosion_particle");
        pair("explosion_emitter", "huge_explosion_emitter");
        pair("enchant", "enchanting_table_particle");
        pair("witch", "witchspell_emitter");
        pair("elder_guardian", "guardian_attack_particle");
        pair("totem_of_undying", "totem_particle");
        pair("sonic_boom", "sonic_explosion");
        pair("spit", "llama_spit_smoke");
        pair("sneeze", "sneeze");
        pair("portal", "mob_portal");
        pair("reverse_portal", "portal_reverse_particle");
        pair("end_rod", "endrod");
        pair("dragon_breath", "dragon_breath_trail");
        pair("firework", "sparkler_emitter");
        pair("glow", "glow_particle");
        pair("electric_spark", "electric_spark_particle");
        pair("sulfur_bubbles", "sulfur_bubble_particle");
        pair("sulfur_cube_goo", "sulfur_cube_goo");
        pair("noxious_gas", "noxious_gas_particle");
        pair("noxious_gas_cloud", "noxious_gas_particle");
        pair("geyser", "geyser_base");
        pair("geyser_base", "geyser_base");
        pair("geyser_plume", "geyser_plume");
        pair("geyser_poof", "geyser_poof");

        // ---- moods ---------------------------------------------------------------------------------
        pair("heart", "heart_particle");
        pair("angry_villager", "villager_angry");
        pair("happy_villager", "villager_happy");
        pair("note", "note_particle");
        pair("composter", "crop_growth_emitter");

        // ---- sculk / trial -------------------------------------------------------------
        pair("sculk_soul", "sculk_soul_particle");
        pair("sculk_charge", "sculk_charge_particle");
        pair("sculk_charge_pop", "sculk_charge_pop_particle");
        pair("shriek", "shriek_particle");
        pair("vibration", "vibration_signal");
        pair("trial_omen", "trial_omen_emitter");
        pair("raid_omen", "raid_omen_emitter");
        pair("ominous_spawning", "ominous_spawning_particle");
        pair("trial_spawner_detection", "trial_spawner_detection");
        pair("trial_spawner_detection_ominous", "trial_spawner_detection_ominous");
        pair("vault_connection", "vault_connection_particle");
        pair("infested", "infested_emitter");
        pair("wax_on", "wax_particle");
        pair("wax_off", "wax_particle");
        pair("scrape", "wax_particle");

        // ---- nature / ambient ---------------------------------------------------------------------
        pair("cherry_leaves", "cherry_leaves_particle");
        pair("pale_oak_leaves", "pale_oak_leaves_particle");
        pair("orange_poplar_leaves", "orange_poplar_leaves_particle");
        pair("red_poplar_leaves", "red_poplar_leaves_particle");
        pair("yellow_poplar_leaves", "yellow_poplar_leaves_particle");
        pair("tinted_leaves", "biome_tinted_leaves_particle");
        pair("spore_blossom_air", "spore_blossom_ambient_particle");
        pair("falling_spore_blossom", "spore_blossom_shower_particle");
        pair("mycelium", "mycelium_dust_particle");
        pair("dust_plume", "dust_plume");
        pair("egg_crack", "egg_destroy_emitter");
        pair("firefly", "firefly_particle");
        pair("snowflake", "snowflake_particle");
        pair("freezing", "snowflake_particle");
        pair("gust", "breeze_wind_explosion_emitter");
        pair("gust_emitter_large", "wind_explosion_emitter");
        pair("flash", "sparkler_emitter");

        // ---- particle types that take options -----------------------------------------------------
        pair("block", "block_destruct");
        pair("falling_dust", "falling_dust");
        pair("dust", "redstone_wire_dust_particle");
        pair("dust_color_transition", "sculk_sensor_redstone_particle");
        pair("item", "breaking_item_icon");
        pair("entity_effect", "mobspell_emitter");
        pair("effect", "splash_spell_emitter");
        pair("instant_effect", "splash_spell_emitter");
        pair("block_crumble", "block_destruct");
        pair("dust_pillar", "smash_ground_particle");
        pair("trail", "creaking_heart_trail");

        // Old-format option counts, only for the named types above that ever carried separate arguments.
        JAVA_OPTION_COUNT.put("block", 1);
        JAVA_OPTION_COUNT.put("block_crumble", 1);
        JAVA_OPTION_COUNT.put("block_marker", 1);
        JAVA_OPTION_COUNT.put("falling_dust", 1);
        JAVA_OPTION_COUNT.put("item", 1);
        JAVA_OPTION_COUNT.put("sculk_charge", 1);
        JAVA_OPTION_COUNT.put("shriek", 1);
        JAVA_OPTION_COUNT.put("dust_pillar", 1);
        JAVA_OPTION_COUNT.put("dust", 4);
        JAVA_OPTION_COUNT.put("entity_effect", 4);
        JAVA_OPTION_COUNT.put("vibration", 4);
        JAVA_OPTION_COUNT.put("trail", 4);
        JAVA_OPTION_COUNT.put("dust_color_transition", 7);
    }

    private ParticleIds() {
    }

    /**
     * @param name a bare Java particle name, e.g. {@code flame}.
     * @return the Bedrock name without its namespace, or null when Bedrock has no counterpart.
     */
    @Nullable
    static String toBedrock(String name) {
        return JAVA_TO_BEDROCK.get(name);
    }

    /**
     * @param name a bare Bedrock particle name, e.g. {@code basic_flame_particle}.
     * @return the Java name without its namespace, or null when Java has no counterpart.
     */
    @Nullable
    static String toJava(String name) {
        return BEDROCK_TO_JAVA.get(name);
    }

    /**
     * @param javaName a bare Java particle name.
     * @return how many separate option arguments the pre-1.20.5 spelling puts before the position.
     */
    static int javaOptionCount(String javaName) {
        Integer count = JAVA_OPTION_COUNT.get(javaName);
        return count == null ? 0 : count;
    }

    private static void pair(String javaName, String bedrockName) {
        // Keep the first mapping written: several Java particles collapse onto one Bedrock particle, and the reverse
        // table has to pick a single representative.
        JAVA_TO_BEDROCK.putIfAbsent(javaName, bedrockName);
        BEDROCK_TO_JAVA.putIfAbsent(bedrockName, javaName);
    }
}
