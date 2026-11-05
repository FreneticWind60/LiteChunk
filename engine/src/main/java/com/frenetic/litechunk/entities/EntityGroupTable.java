package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

import java.util.HashMap;
import java.util.Map;

/**
 * Per entity component groups, entity properties and variant integers, generated from the vanilla behaviour pack.
 * <p>
 * GENERATED FILE - do not edit. Regenerate with {@code docs/entity-tools/dump-entity-groups.py} followed by
 * {@code docs/entity-tools/generate-java-table.py}; the source of truth is the {@code __brarchive} inside a Bedrock
 * APK, described in {@code docs/entity-field-dictionary.md}.
 * <p>
 * Group names here are stored without the {@code minecraft:} prefix: the pack is inconsistent about it (the axolotl's
 * groups are written bare), and comparing names only works if one form is picked. The writer adds the prefix back.
 */
final class EntityGroupTable {

    private static final Map<String, Row> ROWS = new HashMap<>(256);
    private static final Map<String, VariantValue> VARIANTS = new HashMap<>(512);
    private static final Map<String, Integer> HEALTH = new HashMap<>(128);

    private EntityGroupTable() {
    }

    /**
     * @param identifier the Bedrock identifier, e.g. {@code minecraft:sheep}.
     * @return the row, or null when the vanilla behaviour pack does not define this entity, which is the case for
     * dropped items and experience orbs - the game owns those and writes an empty {@code definitions} list for them.
     */
    static Row of(String identifier) {
        return ROWS.get(identifier);
    }

    /**
     * @param identifier the entity the group belongs to.
     * @param group      the bare component group name, e.g. {@code cat_tabby}.
     * @return the integer that group sets, or null when the group does not set one.
     */
    static VariantValue variant(String identifier, String group) {
        return VARIANTS.get(identifier + '\u0000' + group);
    }

    /**
     * @param identifier the entity the group belongs to.
     * @return the default max health from the pack's {@code minecraft:health} component, or -1 when the component is
     * missing or is a range (the horse rolls 15-30, and Java stores the rolled value itself).
     */
    static int health(String identifier) {
        Integer value = HEALTH.get(identifier);
        return value == null ? -1 : value;
    }

    private static void health(String identifier, int value) {
        HEALTH.put(identifier, value);
    }

    private static void row(String identifier, String adult, String baby, String extras, String properties) {
        // Every entity of the behaviour pack is registered, even one with no groups at all. `of()` returning null
        // therefore means "the pack has never heard of this identifier", and only then is the leading
        // `+<identifier>` entry left out of the actor's definitions.
        ROWS.put(identifier, new Row(adult, baby, extras, properties));
    }

    private static void variant(String identifier, String group, String component, int value) {
        VARIANTS.put(identifier + '\u0000' + group, new VariantValue(component, value));
    }

    private static String[] splitList(String value, String separator) {
        return value.isEmpty() ? new String[0] : value.split(separator);
    }

    /**
     * One variant integer: which component it belongs to and the value the pack gives it.
     */
    record VariantValue(String component, int value) {
        /**
         * @return the actor NBT key for this component.
         */
        String actorKey() {
            return switch (component) {
                case "color" -> "Color";
                case "mark_variant" -> "MarkVariant";
                default -> "Variant";
            };
        }
    }

    /**
     * The groups and properties of one entity.
     */
    static final class Row {
        private final String[] adult;
        private final String[] baby;
        private final String[] extras;
        private final String[] propertyEntries;

        private Row(String adult, String baby, String extras, String properties) {
            this.adult = splitList(adult, ",");
            this.baby = splitList(baby, ",");
            this.extras = splitList(extras, ",");
            this.propertyEntries = splitList(properties, ";");
        }

        /**
         * @param isBaby whether the actor is a baby.
         * @return the component groups to enable, without the leading {@code +} or the namespace.
         */
        String[] groups(boolean isBaby) {
            return isBaby ? baby : adult;
        }

        /**
         * Whether the behaviour pack defines this component group for the entity.
         * <p>
         * The variant writer asks this before emitting a group: the pack does not carry all of them (sheep has no
         * group for magenta, lime, purple or green), and an actor that enables a group the game has never heard of is
         * worse than one that only carries the variant integer.
         *
         * @param group the bare group name.
         * @return true when the group exists on this entity.
         */
        boolean hasGroup(String group) {
            return contains(extras, group) || contains(adult, group) || contains(baby, group);
        }

        private static boolean contains(String[] values, String wanted) {
            for (String value : values) {
                if (value.equals(wanted)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return true when this entity carries entity properties.
         */
        boolean hasProperties() {
            return propertyEntries.length > 0;
        }

        /**
         * Build the {@code properties} compound. Only literal defaults are present; Molang ones were dropped while
         * generating the table, and an actor without them falls back to the entity's own default.
         *
         * @return the properties tag.
         */
        CompoundTag properties() {
            CompoundTag tag = new CompoundTag(propertyEntries.length);
            for (String entry : propertyEntries) {
                String[] parts = entry.split("\\|", 3);
                if (parts.length < 3) {
                    continue;
                }
                switch (parts[1]) {
                    case "bool" -> tag.put(parts[0], (byte) ("true".equalsIgnoreCase(parts[2]) ? 1 : 0));
                    case "int" -> tag.put(parts[0], Integer.parseInt(parts[2]));
                    case "float" -> tag.put(parts[0], Float.parseFloat(parts[2]));
                    default -> tag.put(parts[0], parts[2]);
                }
            }
            return tag;
        }
    }

    static {
        row("minecraft:agent", "", "", "", "");
        row("minecraft:allay", "", "", "pickup_item,pickup_item_delay", "");
        row("minecraft:area_effect_cloud", "", "", "", "");
        row("minecraft:armadillo", "adult,adult_unrolled,scanning,unrolled", "baby,baby_unrolled,scanning,unrolled", "rolled_up,rolled_up_with_threats,rolled_up_without_threats", "minecraft:armadillo_state|enum|unrolled");
        row("minecraft:armor_stand", "", "", "", "");
        row("minecraft:arrow", "", "", "mob_arrow,mob_crossbow_arrow,player_arrow,player_crossbow_arrow", "");
        row("minecraft:axolotl", "", "", "attack_cooldown,axolotl_adult,axolotl_baby,axolotl_blue,axolotl_cyan,axolotl_dried,axolotl_gold,axolotl_in_water,axolotl_lucy,axolotl_on_land,axolotl_on_land_in_rain,axolotl_wild", "");
        row("minecraft:bat", "", "", "", "");
        row("minecraft:bee", "bee_adult,look_for_food,shelter_detection,track_attacker", "", "abort_shelter_detection,add_poison_effect,add_wither_effect,angry_bee,bee_baby,countdown_to_perish,default_sound,easy_attack,escape_fire,find_hive,hard_attack,has_nectar,hive_full,normal_attack,perish,return_to_home,take_nearest_target", "");
        row("minecraft:blaze", "", "", "melee_mode,mode_switcher,ranged_mode", "");
        row("minecraft:boat", "", "", "above_bubble_column_down,above_bubble_column_up,can_ride_bamboo,can_ride_default,floating", "");
        row("minecraft:bogged", "", "", "bogged_sheared,melee_attack,ranged_attack,ranged_attack_hard", "");
        row("minecraft:breeze", "", "", "", "minecraft:is_playing_idle_ground_sound|bool|False");
        row("minecraft:breeze_wind_charge_projectile", "", "", "", "");
        row("minecraft:camel", "camel_adult,camel_standing", "", "camel_baby,camel_baby_sitting,camel_baby_standing,camel_saddled,camel_sitting", "");
        row("minecraft:camel_husk", "", "", "camel_husk_saddled,camel_husk_sitting,camel_husk_standing,camel_husk_with_hostile_rider,camel_husk_with_no_hostile_rider", "minecraft:has_rider_mounted|bool|False");
        row("minecraft:cat", "", "", "cat_adult,cat_baby,cat_black,cat_british,cat_calico,cat_gift_for_owner,cat_jellie,cat_persian,cat_ragdoll,cat_red,cat_siamese,cat_tabby,cat_tame,cat_tuxedo,cat_white,cat_wild", "minecraft:sound_variant|enum|default");
        row("minecraft:cave_spider", "", "", "spider_angry,spider_bogged_jockey,spider_hostile,spider_jockey,spider_neutral,spider_parched_jockey,spider_poison_easy,spider_poison_hard,spider_poison_normal,spider_stray_jockey,spider_wither_jockey", "");
        row("minecraft:chest_boat", "", "", "above_bubble_column_down,above_bubble_column_up,can_ride_bamboo,can_ride_default,floating", "");
        row("minecraft:chest_minecart", "", "", "", "");
        row("minecraft:chicken", "chicken_adult", "", "chicken_baby", "minecraft:climate_variant|enum|temperate;minecraft:sound_variant|enum|default");
        row("minecraft:cod", "", "", "", "");
        row("minecraft:command_block_minecart", "", "", "command_block_active,command_block_inactive", "");
        row("minecraft:copper_golem", "", "", "became_statue,becoming_statue,copper_oxidizing", "minecraft:oxidation_level|enum|unoxidized;minecraft:is_waxed|bool|False;minecraft:chest_interaction|enum|none;minecraft:has_flower|bool|False;minecraft:is_becoming_statue|bool|False");
        row("minecraft:cow", "cow_adult", "", "cow_baby", "minecraft:climate_variant|enum|temperate;minecraft:sound_variant|enum|default");
        row("minecraft:creaking", "", "", "crumbling,hostile,hostile_unobserved,immobile,mobile,neutral,spawned_by_creaking_heart,spawned_by_player,twitching", "minecraft:creaking_swaying_ticks|int|0;minecraft:creaking_state|enum|neutral");
        row("minecraft:creeper", "", "", "charged_creeper,charged_exploding,exploding,forced_charged_exploding,forced_exploding", "");
        row("minecraft:cushion", "", "", "", "");
        row("minecraft:dolphin", "", "", "dolphin_adult,dolphin_angry,dolphin_baby,dolphin_dried,dolphin_on_land,dolphin_on_land_in_rain,dolphin_swimming_navigation", "");
        row("minecraft:donkey", "donkey_adult,donkey_wild", "", "donkey_baby,donkey_chested,donkey_saddled,donkey_tamed,donkey_unchested", "");
        row("minecraft:dragon_fireball", "", "", "", "");
        row("minecraft:drowned", "", "", "adult_drowned,baby_drowned,can_break_doors,drowned_jockey,drowned_rider,hunter_mode,melee_equipment,melee_mode,mode_switcher,ranged_equipment,ranged_mode,wander_mode", "");
        row("minecraft:egg", "", "", "", "minecraft:climate_variant|enum|temperate");
        row("minecraft:elder_guardian", "", "", "", "");
        row("minecraft:ender_crystal", "", "", "crystal_exploding", "");
        row("minecraft:ender_dragon", "", "", "dragon_death,dragon_flying,dragon_sitting", "");
        row("minecraft:ender_pearl", "", "", "no_spawn", "");
        row("minecraft:enderman", "", "", "enderman_angry,enderman_calm,not_riding,riding", "");
        row("minecraft:endermite", "", "", "", "");
        row("minecraft:evocation_illager", "", "", "celebrate,raid_configuration,raid_despawn,raid_persistence", "");
        row("minecraft:eye_of_ender_signal", "", "", "", "");
        row("minecraft:fireball", "", "", "exploding", "");
        row("minecraft:fireworks_rocket", "", "", "", "");
        row("minecraft:fishing_hook", "", "", "loot_jungle", "");
        row("minecraft:fox", "", "", "defending_fox,docile_fox,fox_adult,fox_ambient_defending_target,fox_ambient_night,fox_ambient_normal,fox_ambient_sleep,fox_arctic,fox_baby,fox_day,fox_night,fox_red,fox_thunderstorm,fox_with_item,trusting_fox", "");
        row("minecraft:frog", "", "", "cold_frog,pregnant,temperate_frog,warm_frog", "");
        row("minecraft:ghast", "", "", "", "");
        row("minecraft:glow_squid", "", "", "squid_adult,squid_baby", "");
        row("minecraft:goat", "", "", "attack_cooldown,goat_adult,goat_baby,goat_default,goat_screamer,interact_default,interact_screamer,ram_default,ram_screamer", "");
        row("minecraft:guardian", "", "", "guardian_aggressive,guardian_passive", "");
        row("minecraft:happy_ghast", "adult,adult_mobile,adult_unharnessed,adult_without_passengers", "baby", "adult_harnessed,adult_immobile,adult_with_passengers", "minecraft:can_move|bool|True");
        row("minecraft:hoglin", "hoglin_adult,huntable_adult,zombification_sensor", "hoglin_baby,zombification_sensor", "angry_hoglin,attack_cooldown,become_zombie,start_zombification,unhuntable_adult", "");
        row("minecraft:hopper_minecart", "", "", "hopper_active,hopper_inactive", "");
        row("minecraft:horse", "horse_adult,horse_wild", "", "base_black,base_brown,base_chestnut,base_creamy,base_darkbrown,base_gray,base_white,horse_baby,horse_saddled,horse_tamed,markings_black_dots,markings_none,markings_white_details,markings_white_dots,markings_white_fields", "");
        row("minecraft:husk", "", "", "can_break_doors,convert_to_baby_zombie,convert_to_zombie,look_to_start_transformations,not_on_camel_husk,on_camel_husk,start_zombie_transformation,zombie_husk_adult,zombie_husk_baby,zombie_husk_jockey,zombie_husk_rider", "minecraft:is_riding_camel_husk|bool|False");
        row("minecraft:iron_golem", "", "", "player_created,village_created", "");
        row("minecraft:lightning_bolt", "", "", "", "");
        row("minecraft:lingering_potion", "", "", "", "");
        row("minecraft:llama", "llama_adult,llama_brown,llama_creamy,llama_gray,llama_unleashed,llama_white,llama_wild,strength_1,strength_2,strength_3,strength_4,strength_5", "llama_baby,llama_brown,llama_creamy,llama_gray,llama_unleashed,llama_white,strength_1,strength_2,strength_3,strength_4,strength_5", "in_caravan,llama_angry,llama_angry_wolf,llama_chested,llama_tamed,llama_unchested", "");
        row("minecraft:llama_spit", "", "", "", "");
        row("minecraft:magma_cube", "", "", "slime_aggressive,slime_calm,slime_large,slime_medium,slime_small", "");
        row("minecraft:minecart", "", "", "", "");
        row("minecraft:mooshroom", "", "", "cow_adult,cow_baby,mooshroom_become_cow,mooshroom_brown,mooshroom_brown_fed_allium,mooshroom_brown_fed_azure_bluet,mooshroom_brown_fed_blue_orchid,mooshroom_brown_fed_closed_eyeblossom,mooshroom_brown_fed_cornflower,mooshroom_brown_fed_dandelion,mooshroom_brown_fed_lily_of_the_valley,mooshroom_brown_fed_open_eyeblossom,mooshroom_brown_fed_oxeye_daisy,mooshroom_brown_fed_poppy,mooshroom_brown_fed_torchflower,mooshroom_brown_fed_tulips,mooshroom_brown_fed_wither_rose,mooshroom_fed_nothing,mooshroom_red", "");
        row("minecraft:mule", "mule_adult,mule_wild", "", "mule_baby,mule_chested,mule_saddled,mule_tamed,mule_unchested", "");
        row("minecraft:nautilus", "", "", "nautilus_adult,nautilus_ai_controlled,nautilus_baby,nautilus_leashable,nautilus_player_controlled,nautilus_tame,nautilus_tame_adult,nautilus_tame_saddled,nautilus_tame_saddled_in_water,nautilus_tame_saddled_on_ground,nautilus_tame_unsaddled,nautilus_wild_adult_angry,nautilus_wild_adult_calm", "");
        row("minecraft:npc", "", "", "", "");
        row("minecraft:ocelot", "", "", "ocelot_adult,ocelot_baby,ocelot_tame,ocelot_trusting,ocelot_wild,wild_child_ocelot_spawn", "");
        row("minecraft:ominous_item_spawner", "", "", "", "");
        row("minecraft:panda", "", "", "baby_scared,panda_adult,panda_aggressive,panda_angry,panda_baby,panda_brown,panda_lazy,panda_playful,panda_sneezing,panda_weak,panda_worried", "");
        row("minecraft:parched", "", "", "melee_attack,ranged_attack,ranged_attack_hard", "");
        row("minecraft:parrot", "", "", "parrot_adult,parrot_blue,parrot_cyan,parrot_green,parrot_not_riding_player,parrot_red,parrot_riding_player,parrot_silver,parrot_tame,parrot_wild", "");
        row("minecraft:phantom", "", "", "", "");
        row("minecraft:pig", "pig_adult,pig_unsaddled", "", "pig_baby,pig_saddled,pig_transform", "minecraft:climate_variant|enum|temperate;minecraft:sound_variant|enum|default");
        row("minecraft:piglin", "alert_for_attack_targets,attack_cooldown,hunter,interactable_piglin,melee_unit,piglin_adult,ranged_unit,zombification_sensor", "piglin_baby,piglin_jockey,zombification_sensor", "angry,become_zombie,not_hunter,start_zombification,take_target_as_response_to_block_break", "");
        row("minecraft:piglin_brute", "", "", "alert_for_attack_targets,angry,become_zombie,go_back_to_spawn,melee_unit,start_zombification,take_target_as_response_to_block_break,zombification_sensor", "");
        row("minecraft:pillager", "", "", "celebrate,illager_squad_captain,melee_attack,patrol_captain,patrol_follower,raid_configuration,raid_persistence,ranged_attack", "");
        row("minecraft:player", "", "", "add_raid_omen,clear_raid_omen_spell_effect,raid_trigger", "");
        row("minecraft:polar_bear", "", "", "adult,adult_hostile,adult_wild,baby,baby_scared,baby_wild", "");
        row("minecraft:pufferfish", "", "", "deflate_sensor,deflate_sensor_buffer,full_puff,half_puff_primary,half_puff_secondary,normal_puff,start_deflate", "");
        row("minecraft:rabbit", "", "", "adult,baby,coat_black,coat_brown,coat_desert,coat_salt,coat_splotched,coat_white", "");
        row("minecraft:ravager", "", "", "celebrate,evoker_rider_for_raid,hostile,pillager_captain_rider,pillager_rider,pillager_rider_for_raid,raid_configuration,raid_persistence,vindicator_captain_rider,vindicator_rider,roaring,stunned", "");
        row("minecraft:salmon", "", "", "scale_large,scale_normal,scale_small", "");
        row("minecraft:sheep", "loot_wooly,rideable_wooly,sheep_adult,sheep_dyeable", "sheep_baby,sheep_dyeable", "loot_sheared,rideable_sheared,sheep_black,sheep_blue,sheep_brown,sheep_cyan,sheep_gray,sheep_light_blue,sheep_light_gray,sheep_orange,sheep_pink,sheep_red,sheep_sheared,sheep_white,sheep_yellow", "");
        row("minecraft:shulker", "", "", "shulker_black,shulker_blue,shulker_brown,shulker_cyan,shulker_gray,shulker_green,shulker_light_blue,shulker_lime,shulker_magenta,shulker_orange,shulker_pink,shulker_purple,shulker_red,shulker_silver,shulker_undyed,shulker_white,shulker_yellow", "");
        row("minecraft:shulker_bullet", "", "", "", "");
        row("minecraft:silverfish", "", "", "silverfish_angry,silverfish_calm", "");
        row("minecraft:skeleton", "", "", "become_stray,got_out_of_powder_snow_environment_sensor,in_powder_snow,lightning_immune,melee_attack,ranged_attack,ranged_attack_hard", "");
        row("minecraft:skeleton_horse", "", "", "lightning_immune,skeleton_horse_adult,skeleton_horse_baby,skeleton_horse_r5_upgrade,skeleton_trap", "");
        row("minecraft:slime", "", "", "slime_aggressive,slime_calm,slime_large,slime_medium,slime_small", "");
        row("minecraft:small_fireball", "", "", "", "");
        row("minecraft:sniffer", "pushable,sniffer_adult", "", "feeling_happy,sniffer_baby,sniffer_pregnant,sniffer_search_and_dig,stand_up", "");
        row("minecraft:snow_golem", "", "", "snowman_sheared", "");
        row("minecraft:snowball", "", "", "", "");
        row("minecraft:spider", "", "", "spider_angry,spider_bogged_jockey,spider_hostile,spider_jockey,spider_neutral,spider_parched_jockey,spider_stray_jockey,spider_wither_jockey", "");
        row("minecraft:splash_potion", "", "", "", "");
        row("minecraft:squid", "", "", "squid_adult,squid_baby", "");
        row("minecraft:stray", "", "", "melee_attack,ranged_attack,ranged_attack_hard,revert_to_skeleton", "");
        row("minecraft:strider", "detect_suffocating,strider_adult,strider_pathing_behaviors,strider_unsaddled", "detect_suffocating,strider_baby,strider_pathing_behaviors", "start_suffocating,strider_parent_jockey,strider_piglin_jockey,strider_saddled", "");
        row("minecraft:sulfur_cube", "", "", "sulfur_cube_ai,sulfur_cube_bouncy,sulfur_cube_explosive,sulfur_cube_fast_flat,sulfur_cube_fast_sliding,sulfur_cube_high_resistance,sulfur_cube_hot,sulfur_cube_light,sulfur_cube_medium,sulfur_cube_medium_primed,sulfur_cube_medium_primed_by_explosion,sulfur_cube_medium_with_block,sulfur_cube_medium_with_block_interactable,sulfur_cube_medium_without_block,sulfur_cube_medium_without_block_can_pickup,sulfur_cube_medium_without_block_pickup_timeout,sulfur_cube_regular,sulfur_cube_slow_bouncy,sulfur_cube_slow_flat,sulfur_cube_slow_sliding,sulfur_cube_small,sulfur_cube_sticky,sulfur_cube_with_target,sulfur_cube_without_target", "minecraft:sulfur_cube_archetype|enum|none");
        row("minecraft:tadpole", "", "", "grow_up", "");
        row("minecraft:thrown_trident", "", "", "", "");
        row("minecraft:tnt", "", "", "from_explosion", "");
        row("minecraft:tnt_minecart", "", "", "inactive,instant_explode_tnt,primed_tnt", "");
        row("minecraft:trader_llama", "llama_adult,llama_brown,llama_creamy,llama_gray,llama_unleashed,llama_white,llama_wild,strength_1,strength_2,strength_3,strength_4,strength_5", "llama_baby,llama_brown,llama_creamy,llama_gray,llama_unleashed,llama_white,strength_1,strength_2,strength_3,strength_4,strength_5", "in_caravan,llama_angry,llama_angry_wolf,llama_chested,llama_defend_trader,llama_persistence,llama_tamed,llama_unchested,llama_wandering_trader", "");
        row("minecraft:tripod_camera", "", "", "", "");
        row("minecraft:tropicalfish", "", "", "anenonme,black_tang,blue_dory,butterfly_fish,cc_betta,cichlid,clownfish,dog_fish,e_red_snapper,goat_fish,moorish_idol,ornate_butterfly,parrot_fish,queen_angel_fish,red_cichlid,red_lipped_benny,red_snapper,threadfin,tomato_clown,triggerfish,tropicalfish_base_blue,tropicalfish_base_brown,tropicalfish_base_cyan,tropicalfish_base_gray,tropicalfish_base_green,tropicalfish_base_lightblue,tropicalfish_base_lightgreen,tropicalfish_base_magenta,tropicalfish_base_orange,tropicalfish_base_pink,tropicalfish_base_purple,tropicalfish_base_red,tropicalfish_base_silver,tropicalfish_base_white,tropicalfish_base_yellow,tropicalfish_pattern_blue,tropicalfish_pattern_brown,tropicalfish_pattern_cyan,tropicalfish_pattern_gray,tropicalfish_pattern_green,tropicalfish_pattern_lightblue,tropicalfish_pattern_lightgreen,tropicalfish_pattern_magenta,tropicalfish_pattern_orange,tropicalfish_pattern_pink,tropicalfish_pattern_purple,tropicalfish_pattern_red,tropicalfish_pattern_silver,tropicalfish_pattern_white,tropicalfish_pattern_yellow,tropicalfish_variant_a,tropicalfish_variant_b,tropicalfish_variant_pattern_1,tropicalfish_variant_pattern_2,tropicalfish_variant_pattern_3,tropicalfish_variant_pattern_4,tropicalfish_variant_pattern_5,tropicalfish_variant_pattern_6,yellow_tail_parrot,yellow_tang", "");
        row("minecraft:turtle", "", "", "adult,baby,pregnant,wants_to_lay_egg", "");
        row("minecraft:vex", "", "", "periodic_damage,start_damage_timer", "");
        row("minecraft:villager", "", "", "adult,armorer,baby,become_villager_v2,become_witch,become_zombie,behavior_non_peasant,behavior_peasant,butcher,cartographer,cleric,farmer,fisherman,fletcher,leatherworker,librarian,celebrate,shepherd,toolsmith,weaponsmith", "");
        row("minecraft:villager_v2", "", "", "adult,armorer,baby,basic_schedule,become_witch,become_zombie,bed_schedule_villager,behavior_non_peasant,behavior_peasant,butcher,cartographer,child_schedule,cleric,desert_villager,farmer,farmer_schedule,fisher_schedule,fisherman,fletcher,gather_schedule_villager,home_schedule_villager,job_specific_goals,jobless_schedule,jungle_villager,leatherworker,librarian,librarian_schedule,make_and_receive_love,mason,celebrate,nitwit,play_schedule_villager,savanna_villager,shepherd,snow_villager,swamp_villager,taiga_villager,toolsmith,trade_components,trade_resupply_component_group,unskilled,villager_skin_0,villager_skin_1,villager_skin_2,villager_skin_3,villager_skin_4,villager_skin_5,wander_schedule_villager,weaponsmith,work_schedule,work_schedule_farmer,work_schedule_fisher,work_schedule_librarian,work_schedule_villager", "");
        row("minecraft:vindicator", "", "", "celebrate,default_targeting,illager_squad_captain,patrol_captain,patrol_follower,raid_configuration,raid_despawn,raid_persistence,vindicator_aggro,vindicator_johnny", "");
        row("minecraft:wandering_trader", "", "", "despawning,managed,scared", "");
        row("minecraft:warden", "", "", "emerging,pushable", "");
        row("minecraft:wind_charge_projectile", "", "", "", "");
        row("minecraft:witch", "", "", "celebrate,raid_configuration,raid_persistence", "");
        row("minecraft:wither", "", "", "", "");
        row("minecraft:wither_skeleton", "", "", "", "");
        row("minecraft:wither_skull", "", "", "exploding", "");
        row("minecraft:wither_skull_dangerous", "", "", "exploding", "");
        row("minecraft:wolf", "", "", "on_tame_collar_color,wolf_adult,wolf_angry,wolf_armorable,wolf_ashen,wolf_baby,wolf_black,wolf_chestnut,wolf_increased_max_health,wolf_leashable,wolf_pale,wolf_rusty,wolf_snowy,wolf_spotted,wolf_striped,wolf_tame,wolf_wild,wolf_woods", "minecraft:is_armorable|bool|False;minecraft:has_increased_max_health|bool|False;minecraft:sound_variant|enum|default;minecraft:was_upgraded_to_1_21_100|bool|False");
        row("minecraft:xp_bottle", "", "", "", "");
        row("minecraft:xp_orb", "", "", "", "");
        row("minecraft:zoglin", "", "", "angry_zoglin,zoglin_adult,zoglin_baby", "");
        row("minecraft:zombie", "", "", "can_break_doors,can_have_equipment,convert_to_baby_drowned,convert_to_drowned,look_to_start_transformations,not_on_zombie_horse,on_zombie_horse,start_drowned_transformation,zombie_adult,zombie_baby,zombie_default,zombie_jockey,zombie_rider", "minecraft:is_riding_zombie_horse|bool|False");
        row("minecraft:zombie_horse", "horse_adult,horse_can_be_leashed,horse_wild", "", "horse_baby,horse_saddled,horse_tamed,horse_wild_with_rider", "minecraft:was_upgraded_to_1_21_130|bool|False");
        row("minecraft:zombie_nautilus", "", "", "zombie_nautilus_ai_controlled,zombie_nautilus_leashable,zombie_nautilus_player_controlled,zombie_nautilus_tame,zombie_nautilus_tame_saddled,zombie_nautilus_tame_saddled_in_water,zombie_nautilus_tame_saddled_on_ground,zombie_nautilus_tame_unsaddled,zombie_nautilus_tameable,zombie_nautilus_wild,zombie_nautilus_wild_angry,zombie_nautilus_wild_calm,zombie_nautilus_wild_mounted,zombie_nautilus_wild_unmounted", "minecraft:variant|enum|default");
        row("minecraft:zombie_pigman", "pig_zombie_adult,pig_zombie_calm", "pig_zombie_baby,pig_zombie_calm", "pig_zombie_angry,pig_zombie_jockey,strider_jockey", "");
        row("minecraft:zombie_villager", "", "", "adult,armorer,baby,become_zombie_villager_v2,butcher,can_break_doors,cartographer,cleric,farmer,fisherman,fletcher,from_abandoned_village,jockey,leatherworker,librarian,shepherd,to_villager,toolsmith,weaponsmith", "");
        row("minecraft:zombie_villager_v2", "", "", "adult,armorer,baby,butcher,can_break_doors,cartographer,cleric,desert_villager,farmer,fisherman,fletcher,from_abandoned_village,jockey,jungle_villager,leatherworker,librarian,mason,nitwit,savanna_villager,shepherd,snow_villager,swamp_villager,taiga_villager,to_villager,toolsmith,unskilled,villager_skin_0,villager_skin_1,villager_skin_2,villager_skin_3,villager_skin_4,villager_skin_5,weaponsmith", "");
    }

    static {
        variant("minecraft:axolotl", "axolotl_blue", "variant", 4);
        variant("minecraft:axolotl", "axolotl_cyan", "variant", 1);
        variant("minecraft:axolotl", "axolotl_gold", "variant", 2);
        variant("minecraft:axolotl", "axolotl_lucy", "variant", 0);
        variant("minecraft:axolotl", "axolotl_wild", "variant", 3);
        variant("minecraft:bee", "countdown_to_perish", "mark_variant", 1);
        variant("minecraft:cat", "cat_black", "variant", 9);
        variant("minecraft:cat", "cat_british", "variant", 4);
        variant("minecraft:cat", "cat_calico", "variant", 5);
        variant("minecraft:cat", "cat_jellie", "variant", 10);
        variant("minecraft:cat", "cat_persian", "variant", 6);
        variant("minecraft:cat", "cat_ragdoll", "variant", 7);
        variant("minecraft:cat", "cat_red", "variant", 2);
        variant("minecraft:cat", "cat_siamese", "variant", 3);
        variant("minecraft:cat", "cat_tabby", "variant", 8);
        variant("minecraft:cat", "cat_tame", "color", 14);
        variant("minecraft:cat", "cat_tuxedo", "variant", 1);
        variant("minecraft:cat", "cat_white", "variant", 0);
        variant("minecraft:fox", "fox_arctic", "variant", 1);
        variant("minecraft:fox", "fox_red", "variant", 0);
        variant("minecraft:frog", "cold_frog", "variant", 1);
        variant("minecraft:frog", "temperate_frog", "variant", 0);
        variant("minecraft:frog", "warm_frog", "variant", 2);
        variant("minecraft:goat", "goat_default", "variant", 0);
        variant("minecraft:goat", "goat_screamer", "variant", 1);
        variant("minecraft:horse", "base_black", "variant", 4);
        variant("minecraft:horse", "base_brown", "variant", 3);
        variant("minecraft:horse", "base_chestnut", "variant", 2);
        variant("minecraft:horse", "base_creamy", "variant", 1);
        variant("minecraft:horse", "base_darkbrown", "variant", 6);
        variant("minecraft:horse", "base_gray", "variant", 5);
        variant("minecraft:horse", "base_white", "variant", 0);
        variant("minecraft:horse", "markings_black_dots", "mark_variant", 4);
        variant("minecraft:horse", "markings_none", "mark_variant", 0);
        variant("minecraft:horse", "markings_white_details", "mark_variant", 1);
        variant("minecraft:horse", "markings_white_dots", "mark_variant", 3);
        variant("minecraft:horse", "markings_white_fields", "mark_variant", 2);
        variant("minecraft:llama", "llama_brown", "variant", 2);
        variant("minecraft:llama", "llama_creamy", "variant", 0);
        variant("minecraft:llama", "llama_gray", "variant", 3);
        variant("minecraft:llama", "llama_white", "variant", 1);
        variant("minecraft:magma_cube", "slime_large", "variant", 4);
        variant("minecraft:magma_cube", "slime_medium", "variant", 2);
        variant("minecraft:magma_cube", "slime_small", "variant", 1);
        variant("minecraft:mooshroom", "mooshroom_brown", "variant", 1);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_allium", "mark_variant", 7);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_azure_bluet", "mark_variant", 3);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_blue_orchid", "mark_variant", 6);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_closed_eyeblossom", "mark_variant", 12);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_cornflower", "mark_variant", 1);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_dandelion", "mark_variant", 5);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_lily_of_the_valley", "mark_variant", 4);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_open_eyeblossom", "mark_variant", 11);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_oxeye_daisy", "mark_variant", 8);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_poppy", "mark_variant", 0);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_torchflower", "mark_variant", 10);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_tulips", "mark_variant", 2);
        variant("minecraft:mooshroom", "mooshroom_brown_fed_wither_rose", "mark_variant", 9);
        variant("minecraft:mooshroom", "mooshroom_fed_nothing", "mark_variant", -1);
        variant("minecraft:mooshroom", "mooshroom_red", "variant", 0);
        variant("minecraft:panda", "panda_aggressive", "variant", 6);
        variant("minecraft:panda", "panda_brown", "variant", 4);
        variant("minecraft:panda", "panda_lazy", "variant", 1);
        variant("minecraft:panda", "panda_playful", "variant", 3);
        variant("minecraft:panda", "panda_weak", "variant", 5);
        variant("minecraft:panda", "panda_worried", "variant", 2);
        variant("minecraft:parrot", "parrot_blue", "variant", 1);
        variant("minecraft:parrot", "parrot_cyan", "variant", 3);
        variant("minecraft:parrot", "parrot_green", "variant", 2);
        variant("minecraft:parrot", "parrot_red", "variant", 0);
        variant("minecraft:parrot", "parrot_silver", "variant", 4);
        variant("minecraft:piglin", "melee_unit", "variant", 1);
        variant("minecraft:piglin", "ranged_unit", "variant", 0);
        variant("minecraft:piglin_brute", "melee_unit", "variant", 1);
        variant("minecraft:pillager", "illager_squad_captain", "variant", 1);
        variant("minecraft:pillager", "patrol_captain", "variant", 1);
        variant("minecraft:pufferfish", "full_puff", "variant", 2);
        variant("minecraft:pufferfish", "half_puff_primary", "variant", 1);
        variant("minecraft:pufferfish", "half_puff_secondary", "variant", 1);
        variant("minecraft:pufferfish", "normal_puff", "variant", 0);
        variant("minecraft:rabbit", "coat_black", "variant", 2);
        variant("minecraft:rabbit", "coat_brown", "variant", 0);
        variant("minecraft:rabbit", "coat_desert", "variant", 4);
        variant("minecraft:rabbit", "coat_salt", "variant", 5);
        variant("minecraft:rabbit", "coat_splotched", "variant", 3);
        variant("minecraft:rabbit", "coat_white", "variant", 1);
        variant("minecraft:sheep", "sheep_black", "color", 15);
        variant("minecraft:sheep", "sheep_blue", "color", 11);
        variant("minecraft:sheep", "sheep_brown", "color", 12);
        variant("minecraft:sheep", "sheep_cyan", "color", 9);
        variant("minecraft:sheep", "sheep_gray", "color", 7);
        variant("minecraft:sheep", "sheep_light_blue", "color", 3);
        variant("minecraft:sheep", "sheep_light_gray", "color", 8);
        variant("minecraft:sheep", "sheep_orange", "color", 1);
        variant("minecraft:sheep", "sheep_pink", "color", 6);
        variant("minecraft:sheep", "sheep_red", "color", 14);
        variant("minecraft:sheep", "sheep_white", "color", 0);
        variant("minecraft:sheep", "sheep_yellow", "color", 4);
        variant("minecraft:shulker", "shulker_black", "variant", 0);
        variant("minecraft:shulker", "shulker_blue", "variant", 4);
        variant("minecraft:shulker", "shulker_brown", "variant", 3);
        variant("minecraft:shulker", "shulker_cyan", "variant", 6);
        variant("minecraft:shulker", "shulker_gray", "variant", 8);
        variant("minecraft:shulker", "shulker_green", "variant", 2);
        variant("minecraft:shulker", "shulker_light_blue", "variant", 12);
        variant("minecraft:shulker", "shulker_lime", "variant", 10);
        variant("minecraft:shulker", "shulker_magenta", "variant", 13);
        variant("minecraft:shulker", "shulker_orange", "variant", 14);
        variant("minecraft:shulker", "shulker_pink", "variant", 9);
        variant("minecraft:shulker", "shulker_purple", "variant", 5);
        variant("minecraft:shulker", "shulker_red", "variant", 1);
        variant("minecraft:shulker", "shulker_silver", "variant", 7);
        variant("minecraft:shulker", "shulker_undyed", "variant", 16);
        variant("minecraft:shulker", "shulker_white", "variant", 15);
        variant("minecraft:shulker", "shulker_yellow", "variant", 11);
        variant("minecraft:slime", "slime_large", "variant", 4);
        variant("minecraft:slime", "slime_medium", "variant", 2);
        variant("minecraft:slime", "slime_small", "variant", 1);
        variant("minecraft:sulfur_cube", "sulfur_cube_medium", "variant", 2);
        variant("minecraft:sulfur_cube", "sulfur_cube_small", "variant", 1);
        variant("minecraft:trader_llama", "llama_brown", "variant", 2);
        variant("minecraft:trader_llama", "llama_creamy", "variant", 0);
        variant("minecraft:trader_llama", "llama_gray", "variant", 3);
        variant("minecraft:trader_llama", "llama_white", "variant", 1);
        variant("minecraft:tropicalfish", "anenonme", "variant", 1);
        variant("minecraft:tropicalfish", "black_tang", "variant", 1);
        variant("minecraft:tropicalfish", "blue_dory", "variant", 0);
        variant("minecraft:tropicalfish", "butterfly_fish", "variant", 0);
        variant("minecraft:tropicalfish", "cc_betta", "variant", 0);
        variant("minecraft:tropicalfish", "cichlid", "variant", 0);
        variant("minecraft:tropicalfish", "clownfish", "variant", 0);
        variant("minecraft:tropicalfish", "dog_fish", "variant", 1);
        variant("minecraft:tropicalfish", "e_red_snapper", "variant", 1);
        variant("minecraft:tropicalfish", "goat_fish", "variant", 0);
        variant("minecraft:tropicalfish", "moorish_idol", "variant", 1);
        variant("minecraft:tropicalfish", "ornate_butterfly", "variant", 1);
        variant("minecraft:tropicalfish", "parrot_fish", "variant", 0);
        variant("minecraft:tropicalfish", "queen_angel_fish", "variant", 0);
        variant("minecraft:tropicalfish", "red_cichlid", "variant", 1);
        variant("minecraft:tropicalfish", "red_lipped_benny", "variant", 0);
        variant("minecraft:tropicalfish", "red_snapper", "variant", 1);
        variant("minecraft:tropicalfish", "threadfin", "variant", 1);
        variant("minecraft:tropicalfish", "tomato_clown", "variant", 0);
        variant("minecraft:tropicalfish", "triggerfish", "variant", 0);
        variant("minecraft:tropicalfish", "tropicalfish_base_blue", "color", 11);
        variant("minecraft:tropicalfish", "tropicalfish_base_brown", "color", 12);
        variant("minecraft:tropicalfish", "tropicalfish_base_cyan", "color", 9);
        variant("minecraft:tropicalfish", "tropicalfish_base_gray", "color", 7);
        variant("minecraft:tropicalfish", "tropicalfish_base_green", "color", 13);
        variant("minecraft:tropicalfish", "tropicalfish_base_lightblue", "color", 3);
        variant("minecraft:tropicalfish", "tropicalfish_base_lightgreen", "color", 5);
        variant("minecraft:tropicalfish", "tropicalfish_base_magenta", "color", 2);
        variant("minecraft:tropicalfish", "tropicalfish_base_orange", "color", 1);
        variant("minecraft:tropicalfish", "tropicalfish_base_pink", "color", 6);
        variant("minecraft:tropicalfish", "tropicalfish_base_purple", "color", 10);
        variant("minecraft:tropicalfish", "tropicalfish_base_red", "color", 14);
        variant("minecraft:tropicalfish", "tropicalfish_base_silver", "color", 8);
        variant("minecraft:tropicalfish", "tropicalfish_base_white", "color", 0);
        variant("minecraft:tropicalfish", "tropicalfish_base_yellow", "color", 4);
        variant("minecraft:tropicalfish", "tropicalfish_variant_a", "variant", 0);
        variant("minecraft:tropicalfish", "tropicalfish_variant_b", "variant", 1);
        variant("minecraft:tropicalfish", "tropicalfish_variant_pattern_1", "mark_variant", 0);
        variant("minecraft:tropicalfish", "tropicalfish_variant_pattern_2", "mark_variant", 1);
        variant("minecraft:tropicalfish", "tropicalfish_variant_pattern_3", "mark_variant", 2);
        variant("minecraft:tropicalfish", "tropicalfish_variant_pattern_4", "mark_variant", 3);
        variant("minecraft:tropicalfish", "tropicalfish_variant_pattern_5", "mark_variant", 4);
        variant("minecraft:tropicalfish", "tropicalfish_variant_pattern_6", "mark_variant", 5);
        variant("minecraft:tropicalfish", "yellow_tail_parrot", "variant", 0);
        variant("minecraft:tropicalfish", "yellow_tang", "variant", 1);
        variant("minecraft:villager", "armorer", "variant", 3);
        variant("minecraft:villager", "butcher", "variant", 4);
        variant("minecraft:villager", "cartographer", "variant", 1);
        variant("minecraft:villager", "cleric", "variant", 2);
        variant("minecraft:villager", "farmer", "variant", 0);
        variant("minecraft:villager", "fisherman", "variant", 0);
        variant("minecraft:villager", "fletcher", "variant", 0);
        variant("minecraft:villager", "leatherworker", "variant", 4);
        variant("minecraft:villager", "librarian", "variant", 1);
        variant("minecraft:villager", "shepherd", "variant", 0);
        variant("minecraft:villager", "toolsmith", "variant", 3);
        variant("minecraft:villager", "weaponsmith", "variant", 3);
        variant("minecraft:villager_v2", "armorer", "variant", 8);
        variant("minecraft:villager_v2", "butcher", "variant", 11);
        variant("minecraft:villager_v2", "cartographer", "variant", 6);
        variant("minecraft:villager_v2", "cleric", "variant", 7);
        variant("minecraft:villager_v2", "desert_villager", "mark_variant", 1);
        variant("minecraft:villager_v2", "farmer", "variant", 1);
        variant("minecraft:villager_v2", "fisherman", "variant", 2);
        variant("minecraft:villager_v2", "fletcher", "variant", 4);
        variant("minecraft:villager_v2", "jungle_villager", "mark_variant", 2);
        variant("minecraft:villager_v2", "leatherworker", "variant", 12);
        variant("minecraft:villager_v2", "librarian", "variant", 5);
        variant("minecraft:villager_v2", "mason", "variant", 13);
        variant("minecraft:villager_v2", "nitwit", "variant", 14);
        variant("minecraft:villager_v2", "savanna_villager", "mark_variant", 3);
        variant("minecraft:villager_v2", "shepherd", "variant", 3);
        variant("minecraft:villager_v2", "snow_villager", "mark_variant", 4);
        variant("minecraft:villager_v2", "swamp_villager", "mark_variant", 5);
        variant("minecraft:villager_v2", "taiga_villager", "mark_variant", 6);
        variant("minecraft:villager_v2", "toolsmith", "variant", 10);
        variant("minecraft:villager_v2", "unskilled", "variant", 0);
        variant("minecraft:villager_v2", "weaponsmith", "variant", 9);
        variant("minecraft:vindicator", "illager_squad_captain", "variant", 1);
        variant("minecraft:vindicator", "patrol_captain", "variant", 1);
        variant("minecraft:wolf", "on_tame_collar_color", "color", 14);
        variant("minecraft:wolf", "wolf_ashen", "variant", 1);
        variant("minecraft:wolf", "wolf_black", "variant", 2);
        variant("minecraft:wolf", "wolf_chestnut", "variant", 3);
        variant("minecraft:wolf", "wolf_pale", "variant", 0);
        variant("minecraft:wolf", "wolf_rusty", "variant", 4);
        variant("minecraft:wolf", "wolf_snowy", "variant", 5);
        variant("minecraft:wolf", "wolf_spotted", "variant", 6);
        variant("minecraft:wolf", "wolf_striped", "variant", 7);
        variant("minecraft:wolf", "wolf_woods", "variant", 8);
        variant("minecraft:zombie_villager", "armorer", "variant", 3);
        variant("minecraft:zombie_villager", "butcher", "variant", 4);
        variant("minecraft:zombie_villager", "cartographer", "variant", 1);
        variant("minecraft:zombie_villager", "cleric", "variant", 2);
        variant("minecraft:zombie_villager", "farmer", "variant", 0);
        variant("minecraft:zombie_villager", "fisherman", "variant", 0);
        variant("minecraft:zombie_villager", "fletcher", "variant", 0);
        variant("minecraft:zombie_villager", "leatherworker", "variant", 4);
        variant("minecraft:zombie_villager", "librarian", "variant", 1);
        variant("minecraft:zombie_villager", "shepherd", "variant", 0);
        variant("minecraft:zombie_villager", "toolsmith", "variant", 3);
        variant("minecraft:zombie_villager", "weaponsmith", "variant", 3);
        variant("minecraft:zombie_villager_v2", "armorer", "variant", 8);
        variant("minecraft:zombie_villager_v2", "butcher", "variant", 11);
        variant("minecraft:zombie_villager_v2", "cartographer", "variant", 6);
        variant("minecraft:zombie_villager_v2", "cleric", "variant", 7);
        variant("minecraft:zombie_villager_v2", "desert_villager", "mark_variant", 1);
        variant("minecraft:zombie_villager_v2", "farmer", "variant", 1);
        variant("minecraft:zombie_villager_v2", "fisherman", "variant", 2);
        variant("minecraft:zombie_villager_v2", "fletcher", "variant", 4);
        variant("minecraft:zombie_villager_v2", "jungle_villager", "mark_variant", 2);
        variant("minecraft:zombie_villager_v2", "leatherworker", "variant", 12);
        variant("minecraft:zombie_villager_v2", "librarian", "variant", 5);
        variant("minecraft:zombie_villager_v2", "mason", "variant", 13);
        variant("minecraft:zombie_villager_v2", "nitwit", "variant", 14);
        variant("minecraft:zombie_villager_v2", "savanna_villager", "mark_variant", 3);
        variant("minecraft:zombie_villager_v2", "shepherd", "variant", 3);
        variant("minecraft:zombie_villager_v2", "snow_villager", "mark_variant", 4);
        variant("minecraft:zombie_villager_v2", "swamp_villager", "mark_variant", 5);
        variant("minecraft:zombie_villager_v2", "taiga_villager", "mark_variant", 6);
        variant("minecraft:zombie_villager_v2", "toolsmith", "variant", 10);
        variant("minecraft:zombie_villager_v2", "unskilled", "variant", 0);
        variant("minecraft:zombie_villager_v2", "weaponsmith", "variant", 9);
    }

    static {
        health("minecraft:allay", 20);
        health("minecraft:armadillo", 12);
        health("minecraft:armor_stand", 6);
        health("minecraft:axolotl", 14);
        health("minecraft:bat", 6);
        health("minecraft:bee", 10);
        health("minecraft:blaze", 20);
        health("minecraft:bogged", 16);
        health("minecraft:breeze", 30);
        health("minecraft:camel", 32);
        health("minecraft:camel_husk", 32);
        health("minecraft:cave_spider", 12);
        health("minecraft:chicken", 4);
        health("minecraft:cod", 3);
        health("minecraft:copper_golem", 12);
        health("minecraft:cow", 10);
        health("minecraft:creaking", 1);
        health("minecraft:creeper", 20);
        health("minecraft:dolphin", 10);
        health("minecraft:drowned", 20);
        health("minecraft:elder_guardian", 80);
        health("minecraft:ender_crystal", 1);
        health("minecraft:ender_dragon", 200);
        health("minecraft:enderman", 40);
        health("minecraft:endermite", 8);
        health("minecraft:evocation_illager", 24);
        health("minecraft:fox", 10);
        health("minecraft:frog", 10);
        health("minecraft:ghast", 10);
        health("minecraft:glow_squid", 10);
        health("minecraft:goat", 10);
        health("minecraft:guardian", 30);
        health("minecraft:hoglin", 40);
        health("minecraft:husk", 20);
        health("minecraft:iron_golem", 100);
        health("minecraft:mooshroom", 10);
        health("minecraft:nautilus", 15);
        health("minecraft:ocelot", 10);
        health("minecraft:panda", 20);
        health("minecraft:parched", 16);
        health("minecraft:parrot", 6);
        health("minecraft:phantom", 20);
        health("minecraft:pig", 10);
        health("minecraft:piglin", 16);
        health("minecraft:piglin_brute", 50);
        health("minecraft:pillager", 24);
        health("minecraft:polar_bear", 30);
        health("minecraft:pufferfish", 3);
        health("minecraft:rabbit", 3);
        health("minecraft:ravager", 100);
        health("minecraft:salmon", 3);
        health("minecraft:sheep", 8);
        health("minecraft:shulker", 30);
        health("minecraft:silverfish", 8);
        health("minecraft:skeleton", 20);
        health("minecraft:skeleton_horse", 15);
        health("minecraft:sniffer", 14);
        health("minecraft:snow_golem", 4);
        health("minecraft:spider", 16);
        health("minecraft:squid", 10);
        health("minecraft:stray", 20);
        health("minecraft:strider", 20);
        health("minecraft:tadpole", 6);
        health("minecraft:tripod_camera", 4);
        health("minecraft:tropicalfish", 3);
        health("minecraft:turtle", 30);
        health("minecraft:vex", 14);
        health("minecraft:villager", 20);
        health("minecraft:villager_v2", 20);
        health("minecraft:vindicator", 24);
        health("minecraft:wandering_trader", 20);
        health("minecraft:warden", 500);
        health("minecraft:witch", 26);
        health("minecraft:wither", 600);
        health("minecraft:wither_skeleton", 20);
        health("minecraft:wolf", 8);
        health("minecraft:xp_orb", 5);
        health("minecraft:zoglin", 40);
        health("minecraft:zombie", 20);
        health("minecraft:zombie_horse", 25);
        health("minecraft:zombie_nautilus", 15);
        health("minecraft:zombie_pigman", 20);
        health("minecraft:zombie_villager", 20);
        health("minecraft:zombie_villager_v2", 20);
    }
}
