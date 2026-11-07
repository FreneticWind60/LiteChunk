package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Reads the variant of a Bedrock actor back into the Java form.
 * <p>
 * This is the mirror of {@link EntityVariants}, and it goes the other way through the same source of truth: the actor
 * carries an integer, the behaviour pack's component group table says which value means what, and that value is turned
 * back into whatever Java uses for that mob.
 * <p>
 * The integer is never assumed to line up with Java's ordering, because for several mobs it does not:
 * <ul>
 *     <li>a shulker's white is variant <b>15</b> on Bedrock and {@code Color} <b>0</b> on Java - the dye order is
 *     reversed;</li>
 *     <li>an axolotl's wild is variant <b>3</b> on Bedrock and <b>1</b> on Java;</li>
 *     <li>cat and wolf variants are a registry name on Java ({@code minecraft:tabby}) rather than a number at all.</li>
 * </ul>
 * So each table here is built by asking the generated group table for the integer of every Java-known name, and then
 * inverted. That keeps one source of truth for both directions.
 * <p>
 * Sheep and horse are the exceptions: their integers are the same on both sides (confirmed against a real 1.21.8
 * world), so they are copied.
 */
final class JavaEntityVariants {

    private static final String NAMESPACE = "minecraft:";

    /** Java {@code Color} order, which is the dye order. */
    private static final String[] DYE_COLOURS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    /** Java cat registry names; the Bedrock group is derived with the same two renames the forward pass uses. */
    private static final String[] CAT_NAMES = {
            "tabby", "black", "red", "siamese", "british_shorthair", "calico", "persian", "ragdoll", "white",
            "jellie", "all_black"
    };

    /** Java wolf registry names, 1.20.5 and later. */
    private static final String[] WOLF_NAMES = {
            "pale", "spotted", "snowy", "black", "ashen", "rusty", "woods", "chestnut", "striped"
    };

    /** Java {@code RabbitType} 0..5. */
    private static final String[] RABBIT_NAMES = {
            "brown", "white", "black", "splotched", "desert", "salt"
    };

    /** Java parrot {@code Variant} 0..4. */
    private static final String[] PARROT_NAMES = {
            "red", "blue", "green", "cyan", "silver"
    };

    /** Java llama {@code Variant} 0..3. */
    private static final String[] LLAMA_NAMES = {
            "creamy", "white", "brown", "gray"
    };

    /** Java axolotl {@code Variant} 0..4, which is not the Bedrock order. */
    private static final String[] AXOLOTL_NAMES = {
            "lucy", "wild", "gold", "cyan", "blue"
    };

    /** Java panda genes, {@code MainGene}. {@code normal} is variant 0 and has no group. */
    private static final String[] PANDA_GENES = {
            "lazy", "worried", "playful", "brown", "weak", "aggressive"
    };

    private static final Map<Integer, String> CAT = invert("minecraft:cat", CAT_NAMES, JavaEntityVariants::catGroup);
    private static final Map<Integer, String> WOLF = invert("minecraft:wolf", WOLF_NAMES, name -> "wolf_" + name);
    private static final Map<Integer, String> RABBIT =
            invert("minecraft:rabbit", RABBIT_NAMES, name -> "coat_" + name);
    private static final Map<Integer, String> PARROT =
            invert("minecraft:parrot", PARROT_NAMES, name -> "parrot_" + name);
    private static final Map<Integer, String> LLAMA =
            invert("minecraft:llama", LLAMA_NAMES, name -> "llama_" + name);
    private static final Map<Integer, String> AXOLOTL =
            invert("minecraft:axolotl", AXOLOTL_NAMES, name -> "axolotl_" + name);
    private static final Map<Integer, String> SHULKER =
            invert("minecraft:shulker", DYE_COLOURS, name -> "shulker_" + name);

    private JavaEntityVariants() {
    }

    /**
     * Apply the variant of the actor, if this class handles that mob.
     *
     * @param javaEntity  the Java entity tag being built, modified in place.
     * @param actor       the Bedrock actor tag that was read.
     * @param identifier  the Bedrock identifier.
     * @param definitions the actor's definition list, used by the mobs whose variant is only a group.
     */
    static void apply(CompoundTag javaEntity, CompoundTag actor, String identifier, List<String> definitions) {
        switch (identifier) {
            case "minecraft:sheep" -> sheep(javaEntity, actor);
            case "minecraft:horse" -> horse(javaEntity, actor);
            case "minecraft:mooshroom" -> mooshroom(javaEntity, actor);
            case "minecraft:cat" -> registryName(javaEntity, actor, CAT);
            case "minecraft:wolf" -> registryName(javaEntity, actor, WOLF);
            case "minecraft:fox" -> fox(javaEntity, definitions);
            case "minecraft:rabbit" -> indexed(javaEntity, actor, RABBIT, RABBIT_NAMES, "RabbitType");
            case "minecraft:parrot" -> indexed(javaEntity, actor, PARROT, PARROT_NAMES, "Variant");
            case "minecraft:llama", "minecraft:trader_llama" ->
                    indexed(javaEntity, actor, LLAMA, LLAMA_NAMES, "Variant");
            case "minecraft:axolotl" -> indexed(javaEntity, actor, AXOLOTL, AXOLOTL_NAMES, "Variant");
            case "minecraft:panda" -> panda(javaEntity, definitions);
            case "minecraft:shulker" -> shulker(javaEntity, actor);
            case "minecraft:frog" -> frog(javaEntity, definitions);
            case "minecraft:tropicalfish" -> tropicalFish(javaEntity, actor);
            default -> {
                // No variant on this mob, or one this class has no table for.
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Entities whose integers match on both sides
    // ---------------------------------------------------------------------------------------------

    /**
     * Java and Bedrock both use the dye index for a sheep, and the forward pass writes the same number back, so this
     * is a straight copy. {@code Sheared} is handled by {@code BedrockToJava.commonState}.
     */
    private static void sheep(CompoundTag javaEntity, CompoundTag actor) {
        if (!actor.contains("Color")) {
            return;
        }
        javaEntity.put("Color", (byte) clamp(intValue(actor, "Color", 0), DYE_COLOURS.length - 1));
    }

    /**
     * Java packs the coat in the low byte and the markings in the high byte of one integer; Bedrock keeps them in two
     * fields. Both numberings are identical, so this is a repack rather than a lookup.
     */
    private static void horse(CompoundTag javaEntity, CompoundTag actor) {
        int colour = intValue(actor, "Variant", 0) & 0xFF;
        int markings = intValue(actor, "MarkVariant", 0) & 0xFF;
        javaEntity.put("Variant", (colour & 0xFF) | ((markings & 0xFF) << 8));
    }

    /**
     * The pack gives the brown mooshroom {@code variant: 1} and the red one no group at all, so the integer is enough.
     */
    private static void mooshroom(CompoundTag javaEntity, CompoundTag actor) {
        javaEntity.put("Type", intValue(actor, "Variant", 0) == 1 ? "brown" : "red");
    }

    private static void shulker(CompoundTag javaEntity, CompoundTag actor) {
        // Bedrock numbers the colours the other way round, so the integer has to go through the table. An undyed
        // shulker has no variant at all and Java spells that by leaving Color out.
        String colour = SHULKER.get(intValue(actor, "Variant", -1));
        if (colour == null) {
            return;
        }
        int index = indexOf(DYE_COLOURS, colour);
        if (index >= 0) {
            javaEntity.put("Color", (byte) index);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Entities whose Java field is a registry name
    // ---------------------------------------------------------------------------------------------

    private static void registryName(CompoundTag javaEntity, CompoundTag actor, Map<Integer, String> table) {
        String name = table.get(intValue(actor, "Variant", -1));
        if (name != null) {
            javaEntity.put("variant", NAMESPACE + name);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Entities whose Java field is an index
    // ---------------------------------------------------------------------------------------------

    private static void indexed(CompoundTag javaEntity, CompoundTag actor, Map<Integer, String> table,
                                String[] names, String field) {
        String name = table.get(intValue(actor, "Variant", -1));
        if (name == null) {
            return;
        }
        int index = indexOf(names, name);
        if (index >= 0) {
            javaEntity.put(field, index);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Entities whose variant is only a component group
    // ---------------------------------------------------------------------------------------------

    private static void fox(CompoundTag javaEntity, List<String> definitions) {
        if (hasGroup(definitions, "fox_arctic")) {
            javaEntity.put("Type", "snow");
        } else if (hasGroup(definitions, "fox_red")) {
            javaEntity.put("Type", "red");
        }
    }

    private static void panda(CompoundTag javaEntity, List<String> definitions) {
        for (String gene : PANDA_GENES) {
            if (!hasGroup(definitions, "panda_" + gene)) {
                continue;
            }
            // The actor only carries the visible gene; Java keeps a hidden one as well and the game is happy with the
            // same value in both slots.
            javaEntity.put("MainGene", gene);
            javaEntity.put("HiddenGene", gene);
            return;
        }
    }

    private static void frog(CompoundTag javaEntity, List<String> definitions) {
        for (String variant : new String[]{"cold", "temperate", "warm"}) {
            if (hasGroup(definitions, variant + "_frog")) {
                javaEntity.put("variant", NAMESPACE + variant);
                return;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tropical fish
    // ---------------------------------------------------------------------------------------------

    /**
     * Java packs the pattern, the base colour and the pattern colour into one integer; Bedrock keeps them in
     * {@code MarkVariant}, {@code Color} and {@code Color2}. The body shape Bedrock also carries is not part of the
     * Java variant, so it is left out - the fish arrives with the right colours and the default shape.
     */
    private static void tropicalFish(CompoundTag javaEntity, CompoundTag actor) {
        int pattern = clamp(intValue(actor, "MarkVariant", 0), 255);
        int baseColour = clamp(intValue(actor, "Color", 0), 255);
        int patternColour = clamp(intValue(actor, "Color2", 0), 255);
        javaEntity.put("Variant", (pattern << 16) | (baseColour << 8) | patternColour);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Build the reverse table: Bedrock integer to the Java name that produces it.
     *
     * @param identifier the Bedrock identifier.
     * @param names      the Java names, in Java order.
     * @param groupOf    how the forward pass turns a Java name into a component group.
     * @return the inverse of that mapping, never null but possibly empty.
     */
    private static Map<Integer, String> invert(String identifier, String[] names, UnaryOperator<String> groupOf) {
        Map<Integer, String> table = new HashMap<>(names.length * 2);
        for (String name : names) {
            EntityGroupTable.VariantValue value = EntityGroupTable.variant(identifier, groupOf.apply(name));
            if (value != null) {
                table.put(value.value(), name);
            }
        }
        return table;
    }

    /**
     * @param definitions the actor's definition list.
     * @param group       a bare component group name.
     * @return true when the group is enabled on the actor.
     */
    private static boolean hasGroup(@Nullable List<String> definitions, String group) {
        return definitions != null && definitions.contains("+" + NAMESPACE + group);
    }

    /**
     * @param names the list to search.
     * @param name  the value to find.
     * @return the index, or -1 when it is not there.
     */
    private static int indexOf(String[] names, String name) {
        for (int index = 0; index < names.length; index++) {
            if (names[index].equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static int intValue(CompoundTag tag, String name, int defaultValue) {
        Object value = tag.contains(name) ? tag.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    /**
     * Read the actor's definition list.
     *
     * @param actor the actor tag.
     * @return the bare group names, without the {@code +minecraft:} prefix.
     */
    static List<String> definitions(CompoundTag actor) {
        List<String> raw = actor.getListValues("definitions", StringTag.class, null);
        if (raw == null) {
            return List.of();
        }
        return raw;
    }

    /**
     * Cat group names do not all match the registry name: a solid black cat is Bedrock's variant 9 while the tuxedo
     * is 1, and {@code british_shorthair} is shortened to {@code british}.
     */
    private static String catGroup(String name) {
        return "cat_" + switch (name) {
            case "all_black" -> "tuxedo";
            case "british_shorthair" -> "british";
            default -> name;
        };
    }
}
