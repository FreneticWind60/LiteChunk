package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

import java.util.List;

/**
 * Writes the variant of an entity: the colour of a sheep, the coat of a horse, the breed of a cat.
 * <p>
 * Bedrock carries variants in <em>two</em> channels and they are not interchangeable:
 * <ul>
 *     <li>an integer ({@code Variant}, {@code MarkVariant}, {@code Color}), which is what the client renders with;</li>
 *     <li>a component group ({@code +minecraft:sheep_brown}), which is the data driven side of the same state.</li>
 * </ul>
 * A real save writes both and they agree. The value is never assumed here: each variant group in the behaviour pack
 * declares the integer it sets ({@code cat_tabby} says {@code minecraft:variant: 8}), so the table generated from the
 * pack is the single source of truth. Where the pack has no group for a value (sheep has no group for magenta, lime,
 * purple or green) only the integer is written, and it is only written when the Java and Bedrock orderings are known
 * to match - which for sheep and horses is confirmed by real saves.
 * <p>
 * The Java side is read by field name, verified with {@code gradlew :engine:javaActorDump} against a real 1.21.8
 * world: {@code Color} (sheep), {@code Sheared}, {@code Variant} (horse, colour in the low byte and markings in the
 * high byte), {@code variant} (cat, wolf - a registry name), {@code RabbitType}, and {@code Variant} again for the
 * parrots. Entity kinds whose field name has not been observed yet simply fall through: their actor keeps the
 * identifier, the age groups and the position, which is exactly what it had before this class existed.
 */
final class EntityVariants {

    private static final String NAMESPACE = "minecraft:";

    /** Java {@code Color} order, which is the dye order and identical on both sides. */
    private static final String[] DYE_COLOURS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    /** Horse coat, Java {@code Variant} low byte. */
    private static final String[] HORSE_COLOURS = {
            "white", "creamy", "chestnut", "brown", "black", "gray", "darkbrown"
    };

    /**
     * Horse markings, Java {@code Variant} high byte. The pack spells 2 as {@code white_fields} (plural) but 1 as
     * {@code white_details}, so this cannot be derived from the Java enum names.
     */
    private static final String[] HORSE_MARKINGS = {
            "none", "white_details", "white_fields", "white_dots", "black_dots"
    };

    /** Java {@code RabbitType} 0..5; 99 is the killer rabbit, which has no coat group. */
    private static final String[] RABBIT_COATS = {
            "brown", "white", "black", "splotched", "desert", "salt"
    };

    /** Java parrot {@code Variant} 0..4. */
    private static final String[] PARROT_COLOURS = {
            "red", "blue", "green", "cyan", "silver"
    };

    /** Java llama {@code Variant} 0..3. */
    private static final String[] LLAMA_COLOURS = {
            "creamy", "white", "brown", "gray"
    };

    /** Java axolotl {@code Variant} 0..4, which is <em>not</em> the Bedrock order - see {@link #axolotl}. */
    private static final String[] AXOLOTL_COLOURS = {
            "lucy", "wild", "gold", "cyan", "blue"
    };

    private EntityVariants() {
    }

    /**
     * Apply the variant of the entity, if this class handles it.
     *
     * @param actor       the actor tag being built, modified in place.
     * @param identifier  the Bedrock identifier.
     * @param javaEntity  the Java entity NBT.
     * @param definitions the definition list being built, modified in place.
     */
    static void apply(CompoundTag actor, String identifier, CompoundTag javaEntity, List<String> definitions) {
        switch (identifier) {
            case "minecraft:sheep" -> sheep(actor, identifier, javaEntity, definitions);
            case "minecraft:horse" -> horse(actor, identifier, javaEntity, definitions);
            case "minecraft:mooshroom" -> mooshroom(actor, identifier, javaEntity, definitions);
            case "minecraft:cat" -> named(actor, identifier, javaEntity, definitions,
                    name(javaEntity, "variant"), EntityVariants::catGroup);
            case "minecraft:wolf" -> named(actor, identifier, javaEntity, definitions,
                    name(javaEntity, "variant"), suffix -> "wolf_" + suffix);
            case "minecraft:fox" -> fox(actor, identifier, javaEntity, definitions);
            case "minecraft:rabbit" -> rabbit(actor, identifier, javaEntity, definitions);
            case "minecraft:parrot" -> indexed(actor, identifier, javaEntity, definitions,
                    "Variant", PARROT_COLOURS, "parrot_");
            case "minecraft:llama", "minecraft:trader_llama" -> indexed(actor, identifier, javaEntity, definitions,
                    "Variant", LLAMA_COLOURS, "llama_");
            case "minecraft:axolotl" -> indexed(actor, identifier, javaEntity, definitions,
                    "Variant", AXOLOTL_COLOURS, "axolotl_");
            case "minecraft:panda" -> panda(actor, identifier, javaEntity, definitions);
            case "minecraft:shulker" -> shulker(actor, identifier, javaEntity, definitions);
            case "minecraft:frog" -> frog(actor, identifier, javaEntity, definitions);
            case "minecraft:slime", "minecraft:magma_cube" -> slime(actor, identifier, javaEntity, definitions);
            case "minecraft:boat", "minecraft:chest_boat" -> boat(actor, javaEntity);
            case "minecraft:tropicalfish" -> tropicalFish(actor, javaEntity);
            default -> {
                // Not covered yet: villagers (their job is a group of its own, see EntityStates), slimes and the like.
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Rabbit
    // ---------------------------------------------------------------------------------------------

    /**
     * Rabbit coat and age.
     * <p>
     * The rabbit is the one entity whose component groups are spelled without a namespace in the behaviour pack
     * ({@code coat_brown}, {@code adult}, {@code baby}), so both entries have to be written raw. A namespaced entry
     * does not resolve, which left every converted baby rabbit full sized and made the coat depend on the integer
     * alone.
     *
     * @param actor       the actor tag being built, modified in place.
     * @param identifier  the Bedrock identifier.
     * @param entity      the Java entity NBT.
     * @param definitions the definition list being built, modified in place.
     */
    private static void rabbit(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        int type = intValue(entity, "RabbitType", 0);
        // 99 is the killer rabbit, which has no coat of its own.
        if (type >= 0 && type < RABBIT_COATS.length) {
            String group = "coat_" + RABBIT_COATS[type];
            EntityGroupTable.VariantValue value = EntityGroupTable.variant(identifier, group);
            if (value != null) {
                actor.put(value.actorKey(), value.value());
                addRawGroup(definitions, group);
            }
        }
        addRawGroup(definitions, intValue(entity, "IsBaby", 0) != 0 ? "baby" : "adult");
    }

    // ---------------------------------------------------------------------------------------------
    // Slimes
    // ---------------------------------------------------------------------------------------------

    /**
     * Slime and magma cube size.
     * <p>
     * Java stores a diameter in {@code Size} using 1/2/4 for small/medium/large (0 only exists for the potion-made
     * tiny slime). Bedrock keeps the same numbers in {@code Variant} and switches its hitbox and loot groups on them -
     * the pack declares {@code slime_small: 1}, {@code slime_medium: 2}, {@code slime_large: 4}. Without this every
     * converted slime came out the default size.
     *
     * @param actor       the actor tag being built, modified in place.
     * @param identifier  the Bedrock identifier.
     * @param entity      the Java entity NBT.
     * @param definitions the definition list being built, modified in place.
     */
    private static void slime(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        int size = intValue(entity, "Size", 1);
        int variant = size <= 1 ? 1 : size == 2 ? 2 : 4;
        String group = variant == 1 ? "slime_small" : variant == 2 ? "slime_medium" : "slime_large";
        if (!addVariant(actor, identifier, definitions, group)) {
            actor.put("Variant", variant);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Boats
    // ---------------------------------------------------------------------------------------------

    /**
     * Boat wood types, in the order Bedrock numbers them in {@code Variant}.
     * <p>
     * Java has one entity id per wood ({@code minecraft:spruce_boat}, {@code minecraft:bamboo_raft}, ...) while
     * Bedrock has a single {@code minecraft:boat} with an integer. The bamboo slot is not a guess: the behaviour pack
     * branches its rideable component on {@code is_variant != 7} to decide whether a passenger may stand on it, and
     * only the raft rejects that. The three after it follow the order the woods were added to the game.
     */
    private static final String[] BOAT_WOODS = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "bamboo", "cherry", "pale_oak",
            "poplar"
    };

    /**
     * Translate the wood type of Java's per-wood boat into Bedrock's {@code Variant}.
     *
     * @param actor  the actor tag being built, modified in place.
     * @param entity the Java entity NBT, whose id carries the wood.
     */
    private static void boat(CompoundTag actor, CompoundTag entity) {
        String id = entity.getString("id", "");
        if (id == null) {
            return;
        }
        String name = id.startsWith(NAMESPACE) ? id.substring(NAMESPACE.length()) : id;

        // Both spellings exist in Java: `<wood>_boat` / `<wood>_chest_boat`, and the two rafts.
        String wood = name.replace("_chest_boat", "").replace("_boat", "").replace("bamboo_raft", "bamboo")
                .replace("bamboo_chest_raft", "bamboo");
        for (int index = 0; index < BOAT_WOODS.length; index++) {
            if (BOAT_WOODS[index].equals(wood)) {
                actor.put("Variant", index);
                return;
            }
        }
        // Unknown wood (a modded boat): leave the variant alone and let the game pick the default.
    }

    // ---------------------------------------------------------------------------------------------
    // Entities with a Java peculiarity
    // ---------------------------------------------------------------------------------------------

    private static void sheep(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        int colour = clamp(intValue(entity, "Color", 0), DYE_COLOURS.length);
        // The wool colour is the `minecraft:color` component, and that component is an int in the game's own schema
        // (JsonSchemaTypedNode<int, ..., ColorDefinition>). addVariant writes it; the fallback below covers the four
        // dyes the pack has no group for.
        if (!addVariant(actor, identifier, definitions, "sheep_" + DYE_COLOURS[colour])) {
            // No group for this colour: the pack only carries 12 of the 16. The integer alone is enough - both sides
            // use the dye order.
            actor.put("Color", colour);
        }

        if (intValue(entity, "Sheared", 0) != 0) {
            // A sheared sheep keeps its dye but loses the wool: the pack swaps the loot and rideable groups rather
            // than adding a separate one.
            actor.put("Sheared", (byte) 1);
            replace(definitions, "loot_wooly", "loot_sheared");
            replace(definitions, "rideable_wooly", "rideable_sheared");
            addGroup(definitions, identifier, "sheep_sheared");
        }
    }

    private static void horse(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        int packed = intValue(entity, "Variant", 0);
        int colour = packed & 0xFF;
        int markings = (packed >>> 8) & 0xFF;

        boolean colourWritten = colour < HORSE_COLOURS.length
                && addVariant(actor, identifier, definitions, "base_" + HORSE_COLOURS[colour]);
        if (!colourWritten) {
            actor.put("Variant", colour);
        }

        boolean markingsWritten = markings < HORSE_MARKINGS.length
                && addVariant(actor, identifier, definitions, "markings_" + HORSE_MARKINGS[markings]);
        if (!markingsWritten) {
            actor.put("MarkVariant", markings);
        }

        if (intValue(entity, "Tame", 0) != 0) {
            // Tamed horses trade `horse_wild` for `horse_tamed`.
            replace(definitions, "horse_wild", "horse_tamed");
            addGroup(definitions, identifier, "horse_tamed");
        }
    }

    private static void mooshroom(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        // Java has carried this as `Type` with the values red/brown; the lower case spelling is read as well in case
        // it was renamed together with the other 1.20.5 fields.
        String type = entity.getString("Type", entity.getString("type", "red"));
        if (!addVariant(actor, identifier, definitions, "brown".equalsIgnoreCase(type) ? "mooshroom_brown" : "mooshroom_red")) {
            // Nothing to write: the pack always has both groups, so this only happens for an unknown identifier.
        }
    }

    private static void fox(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        String type = entity.getString("Type", entity.getString("type", "red"));
        addVariant(actor, identifier, definitions, "snow".equalsIgnoreCase(type) ? "fox_arctic" : "fox_red");
    }

    private static void panda(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        String gene = name(entity, "MainGene");
        if (gene == null || "normal".equals(gene)) {
            return; // The pack has no group for a normal panda: it is variant 0, the default.
        }
        addVariant(actor, identifier, definitions, "panda_" + gene);
    }

    private static void shulker(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        int colour = intValue(entity, "Color", 16);
        if (colour < 0 || colour >= DYE_COLOURS.length) {
            addVariant(actor, identifier, definitions, "shulker_undyed");
            return;
        }
        addVariant(actor, identifier, definitions, "shulker_" + DYE_COLOURS[colour]);
    }

    private static void frog(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions) {
        String variant = name(entity, "variant");
        if (variant == null) {
            return;
        }
        // The pack names these `cold_frog` / `temperate_frog` / `warm_frog`.
        addVariant(actor, identifier, definitions, variant + "_frog");
    }

    /**
     * Tropical fish pack three values into one Java integer: the pattern in bits 16-23, the base colour in bits 8-15
     * and the pattern colour in the low byte, each a dye index.
     * <p>
     * Bedrock splits the same three across two integer channels and two byte ones: the pattern is a
     * {@code MarkVariant} (the pack's {@code tropicalfish_variant_pattern_1..6} set it to 0..5, so the Java pattern
     * index carries over unchanged) while the two colours are the generic {@code Color} / {@code Color2} bytes, which
     * the pack's {@code tropicalfish_base_*} and {@code tropicalfish_pattern_*} groups write with the dye index.
     * <p>
     * <strong>The body shape is left alone.</strong> Bedrock also keeps a {@code Variant} of 0 or 1 selecting one of
     * two fish shapes, and the pack only exposes it through the twenty named pattern groups ({@code clownfish},
     * {@code blue_dory} ...), whose Java counterparts are the pattern ids this class cannot tell apart from the
     * Bedrock numbering. Guessing it would flip half the fish to the wrong model, so the default is kept.
     * <p>
     * Confirmed against a 1.21.8 world only as far as the Java side goes: summoning fish with 0, 65536 and 262144
     * gives exactly those integers back, and 16711680 (pattern 255) is reset to 0, which is what pins the pattern to
     * the third byte.
     */
    private static void tropicalFish(CompoundTag actor, CompoundTag entity) {
        int packed = intValue(entity, "Variant", 0);
        int pattern = (packed >>> 16) & 0xFF;
        int baseColour = (packed >>> 8) & 0xFF;
        int patternColour = packed & 0xFF;

        actor.put("Color", (byte) clamp(baseColour, DYE_COLOURS.length));
        actor.put("Color2", (byte) clamp(patternColour, DYE_COLOURS.length));
        // The pack carries six patterns; anything above that is not a fish the game knows how to draw.
        actor.put("MarkVariant", clamp(pattern, 6));
    }

    // ---------------------------------------------------------------------------------------------
    // Generic helpers
    // ---------------------------------------------------------------------------------------------

    private static void named(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions,
                              String name, java.util.function.UnaryOperator<String> groupFor) {
        if (name == null) {
            return;
        }
        addVariant(actor, identifier, definitions, groupFor.apply(name));
    }

    private static void indexed(CompoundTag actor, String identifier, CompoundTag entity, List<String> definitions,
                                String field, String[] names, String prefix) {
        int index = intValue(entity, field, -1);
        if (index < 0 || index >= names.length) {
            return; // Out of range, e.g. the killer rabbit's 99: no group, and no integer worth guessing.
        }
        addVariant(actor, identifier, definitions, prefix + names[index]);
    }

    /**
     * Cat registry names do not all match their group: a solid black cat is Bedrock's variant 9 and the tuxedo is 1,
     * so `all_black` is the tuxedo there, while `british_shorthair` is shortened to `british`.
     */
    private static String catGroup(String name) {
        return "cat_" + switch (name) {
            case "all_black" -> "tuxedo";
            case "british_shorthair" -> "british";
            default -> name;
        };
    }

    /**
     * Write the integer and the group for a variant group, when the pack defines that group.
     *
     * @return true when the pack knew the group and the integer was written.
     */
    private static boolean addVariant(CompoundTag actor, String identifier, List<String> definitions, String group) {
        EntityGroupTable.VariantValue value = EntityGroupTable.variant(identifier, group);
        if (value == null) {
            return false;
        }
        // Every component the table records is an `int` in the game's own schema (the colour one included:
        // JsonSchemaTypedNode<int, ..., ColorDefinition>), so the value goes in untouched. Narrowing the colour to a
        // byte was a guess, and a copy of our own output mistaken for a save - it left every sheep white.
        actor.put(value.actorKey(), value.value());
        addGroup(definitions, identifier, group);
        return true;
    }

    /**
     * Add a group that the behaviour pack does not namespace.
     *
     * @param definitions the definition list being built, modified in place.
     * @param group       the group name as the pack spells it, e.g. {@code baby}.
     */
    static void addRawGroup(List<String> definitions, String group) {
        String entry = "+" + group;
        if (!definitions.contains(entry)) {
            definitions.add(entry);
        }
    }

    /**
     * Add {@code +minecraft:<group>} unless the pack does not define it or it is already there.
     */
    static void addGroup(List<String> definitions, String identifier, String group) {
        String entry = "+" + NAMESPACE + group;
        if (definitions.contains(entry)) {
            return;
        }
        EntityGroupTable.Row row = EntityGroupTable.of(identifier);
        if (row != null && row.hasGroup(group)) {
            definitions.add(entry);
        }
    }

    /**
     * Mark a mount as ridden: it has to be saddled and, for the animals that have a wild form, tamed.
     * <p>
     * <strong>This is the only way a rider survives on an animal that was not already set up for it.</strong>
     * Bedrock gives a wild or unsaddled mount no seat at all, so a link written to it is dropped on load and the
     * rider falls off - Java has no such rule (any entity can be given a passenger), so a Java world can easily hold
     * a pig riding a horse nobody ever tamed. Turning the mount into a rideable one loses the "was wild" fact, which
     * is not visible anywhere else, and keeps the thing the player built.
     *
     * @param definitions the definition list, modified in place.
     * @param identifier  the Bedrock identifier, e.g. {@code minecraft:horse}.
     */
    static void saddled(List<String> definitions, String identifier) {
        String group = identifier.startsWith(NAMESPACE) ? identifier.substring(NAMESPACE.length()) : identifier;
        // The table enables `horse_wild` / `pig_unsaddled` by default, so the off state has to be swapped out rather
        // than left alongside the on state.
        replace(definitions, group + "_wild", group + "_tamed");
        replace(definitions, group + "_unsaddled", group + "_saddled");
        addGroup(definitions, identifier, group + "_saddled");
    }

    /**
     * Take a group back off the list.
     * <p>
     * Only the enabled form is removed; no {@code -minecraft:<group>} entry is written. Those appear in real saves
     * when the game switched a group off at runtime, but this list is built from nothing, so simply not enabling the
     * group leaves it off - which is the state we want.
     *
     * @param definitions the list to edit.
     * @param identifier  the entity the group belongs to.
     * @param group       the bare group name.
     */
    static void removeGroup(List<String> definitions, String identifier, String group) {
        definitions.remove("+" + NAMESPACE + group);
    }

    /**
     * Swap one group for another in place, keeping the original position so the list stays stable between runs.
     */
    private static void replace(List<String> definitions, String from, String to) {
        int index = definitions.indexOf("+" + NAMESPACE + from);
        if (index < 0) {
            return;
        }
        String target = "+" + NAMESPACE + to;
        if (definitions.contains(target)) {
            definitions.remove(index);
        } else {
            definitions.set(index, target);
        }
    }

    /**
     * Read a registry-style name field and strip the namespace.
     *
     * @param entity the entity tag.
     * @param field  the field name.
     * @return the bare name, or null when the field is missing or empty.
     */
    private static String name(CompoundTag entity, String field) {
        String value = entity.getString(field, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.startsWith(NAMESPACE) ? value.substring(NAMESPACE.length()) : value;
    }

    /**
     * Read a field that Java stores as a byte, short or int depending on the entity.
     */
    private static int intValue(CompoundTag entity, String name, int defaultValue) {
        Object value = entity.contains(name) ? entity.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : Math.min(value, size - 1);
    }
}
