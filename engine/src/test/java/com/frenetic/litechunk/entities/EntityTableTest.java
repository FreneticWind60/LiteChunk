package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.DoubleTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import com.hivemc.chunker.nbt.io.Reader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.Map;

/**
 * Checks that the generated group table still produces the actor shape the game expects.
 * <p>
 * The table is generated from an APK, so a regeneration with a new game version could silently drop a group or flip
 * an encoding. These assertions pin the parts that would be invisible until someone opened a world and found silent
 * sheep: adult groups present, babies using the baby set, properties written, unknown ids falling back.
 */
public final class EntityTableTest {

    private EntityTableTest() {
    }

    /**
     * Run the checks.
     *
     * @throws IllegalStateException when an expectation does not hold.
     */
    public static void run() throws Exception {
        // An adult sheep must carry the four groups a real world has, plus its own identifier.
        require("sheep adult", definitions("minecraft:sheep", false),
                "minecraft:sheep", "minecraft:loot_wooly", "minecraft:rideable_wooly",
                "minecraft:sheep_adult", "minecraft:sheep_dyeable");

        // A baby sheep must use the baby set instead of the adult one.
        require("sheep baby", definitions("minecraft:sheep", true),
                "minecraft:sheep", "minecraft:sheep_baby", "minecraft:sheep_dyeable");

        // Cow has no variant groups but does have entity properties.
        require("cow adult", definitions("minecraft:cow", false), "minecraft:cow", "minecraft:cow_adult");
        CompoundTag cow = build("minecraft:cow", false);
        CompoundTag properties = cow.get("properties");
        if (properties == null || !"temperate".equals(properties.getString("minecraft:climate_variant", null))) {
            throw new IllegalStateException("cow properties missing or wrong: " + properties);
        }

        // Rabbit has no age sub event in the behaviour pack, so it must stay a bare shell rather than enabling every
        // coat and both ages.
        require("rabbit", definitions("minecraft:rabbit", false), "minecraft:rabbit");

        // Ids that Bedrock spells differently are mapped on the way out.
        require("zombified piglin", definitions("minecraft:zombified_piglin", false), "minecraft:zombie_pigman");

        // An experience orb only needs its value, and Bedrock stores it under a key with a space in it.
        CompoundTag orb = build("minecraft:experience_orb", false);
        if (!orb.contains("experience value")) {
            throw new IllegalStateException("experience orb is missing its value: " + orb);
        }
        require("experience orb", definitions("minecraft:experience_orb", false), "minecraft:xp_orb");

        // Payload entities need the item/block resolvers. Without them the actor must be dropped rather than written
        // as an empty shell that the game cannot render.
        requireSkipped("item without resolvers", "minecraft:item");
        requireSkipped("falling block without resolvers", "minecraft:falling_block");

        variants();
        equipmentSlots();
        template();

        System.out.println("entity table: OK");
    }

    /**
     * Name, persistence and health: the three pieces of actor state that the entity components cannot rebuild.
     */
    private static void template() throws Exception {
        CompoundTag entity = entity("minecraft:pig");
        entity.put("CustomName", "{\"text\":\"Bob\"}");
        entity.put("CustomNameVisible", (byte) 1);
        entity.put("PersistenceRequired", (byte) 1);
        entity.put("Health", 13.5F);

        ListTag<CompoundTag, Map<String, Tag<?>>> attributes = new ListTag<>(TagType.COMPOUND, 1);
        CompoundTag maxHealth = new CompoundTag(3);
        maxHealth.put("id", "minecraft:max_health");
        maxHealth.put("base", 20.0F);
        attributes.add(maxHealth);
        entity.put("attributes", attributes);

        CompoundTag actor = decode(actor(entity));

        if (!"Bob".equals(actor.getString("CustomName", null))) {
            throw new IllegalStateException("name was not carried over: " + actor.get("CustomName"));
        }
        if (actor.getByte("CustomNameVisible", (byte) 0) != 1) {
            throw new IllegalStateException("CustomNameVisible was not carried over");
        }
        if (actor.getByte("Persistent", (byte) 0) != 1) {
            throw new IllegalStateException("PersistenceRequired was not mapped to Persistent");
        }

        ListTag<CompoundTag, Map<String, Tag<?>>> written = actor.getList("Attributes", CompoundTag.class, null);
        if (written == null || written.size() != 1) {
            throw new IllegalStateException("expected exactly one attribute, got " + written);
        }
        CompoundTag health = written.get(0);
        if (!"minecraft:health".equals(health.getString("Name", null))) {
            throw new IllegalStateException("health attribute has the wrong name: " + health);
        }
        if (Math.abs(health.getFloat("Base", 0F) - 20F) > 1e-4 || Math.abs(health.getFloat("Current", 0F) - 13.5F) > 1e-4) {
            throw new IllegalStateException("health values are wrong: " + health);
        }
        if (Math.abs(health.getFloat("Max", 0F) - 20F) > 1e-4) {
            throw new IllegalStateException("health max should equal the Java max health: " + health);
        }

        // A damaged sheep has no max_health entry in Java (only modified attributes are stored), so the max has to
        // come from the pack - otherwise a sheep saved at 6 HP would come out with a permanent maximum of 6.
        CompoundTag damaged = entity("minecraft:sheep");
        damaged.put("Health", 6.0F);
        ListTag<CompoundTag, Map<String, Tag<?>>> fromPack = decode(actor(damaged))
                .getList("Attributes", CompoundTag.class, null);
        if (fromPack == null || fromPack.size() != 1) {
            throw new IllegalStateException("damaged sheep has no attributes: " + fromPack);
        }
        CompoundTag sheepHealth = fromPack.get(0);
        if (Math.abs(sheepHealth.getFloat("Base", 0F) - 8F) > 1e-4
                || Math.abs(sheepHealth.getFloat("Current", 0F) - 6F) > 1e-4) {
            throw new IllegalStateException("pack default health was not used: " + sheepHealth);
        }
    }

    /**
     * The legacy {@code ArmorItems} order is bottom up and Bedrock's is top down; getting it backwards puts boots on
     * a mob's head, which is invisible until someone opens the world.
     */
    private static void equipmentSlots() {
        String[] legacy = {"feet", "legs", "chest", "head"};
        String[] bedrock = {"head", "chest", "legs", "feet"};
        for (int index = 0; index < legacy.length; index++) {
            int mapped = EntityEquipment.legacySlot(index);
            if (mapped < 0 || mapped >= bedrock.length || !bedrock[mapped].equals(legacy[index])) {
                throw new IllegalStateException("legacy armour slot " + legacy[index] + " mapped to " + mapped);
            }
        }
    }

    /**
     * Variants: the integer and the matching group have to agree, and a group the pack does not define must not be
     * invented. Sheep is the interesting case because the pack only carries 12 of the 16 colours.
     */
    private static void variants() throws Exception {
        // Java Color 12 is brown, and the pack does define sheep_brown.
        CompoundTag brownSheep = withField("minecraft:sheep", "Color", (byte) 12);
        List<String> brownGroups = decode(brownSheep).getListValues("definitions", StringTag.class);
        require("sheep brown", brownGroups, "minecraft:sheep_brown");
        if (brownSheep.getByte("Color", (byte) -1) != 12) {
            throw new IllegalStateException("sheep colour was not written: " + brownSheep.get("Color"));
        }

        // Lime has no group in the pack, so only the integer may be written.
        CompoundTag limeSheep = withField("minecraft:sheep", "Color", (byte) 5);
        List<String> limeGroups = decode(limeSheep).getListValues("definitions", StringTag.class);
        if (limeGroups.contains("+minecraft:sheep_lime")) {
            throw new IllegalStateException("sheep_lime does not exist in the pack but was written: " + limeGroups);
        }
        if (limeSheep.getByte("Color", (byte) -1) != 5) {
            throw new IllegalStateException("lime sheep lost its colour: " + limeSheep.get("Color"));
        }

        // Sheared sheep trade the wool groups for the sheared ones.
        CompoundTag shearedSheep = withField("minecraft:sheep", "Sheared", (byte) 1);
        List<String> shearedGroups = decode(shearedSheep).getListValues("definitions", StringTag.class);
        if (!shearedGroups.contains("+minecraft:loot_sheared") || shearedGroups.contains("+minecraft:loot_wooly")) {
            throw new IllegalStateException("sheared sheep kept the wool groups: " + shearedGroups);
        }

        // Java packs colour in the low byte and markings in the high byte of Variant; Bedrock splits them.
        CompoundTag horse = withField("minecraft:horse", "Variant", 1027); // colour 3, markings 4
        if (horse.getInt("Variant", -1) != 3 || horse.getInt("MarkVariant", -1) != 4) {
            throw new IllegalStateException("horse variant was not split: " + horse.get("Variant")
                    + " / " + horse.get("MarkVariant"));
        }
        List<String> horseGroups = decode(horse).getListValues("definitions", StringTag.class);
        require("horse brown", horseGroups, "minecraft:base_brown", "minecraft:markings_black_dots");

        // Brown mooshrooms use the brown group; anything else falls back to red.
        CompoundTag brownMooshroom = withString("minecraft:mooshroom", "Type", "brown");
        List<String> brownMooshroomGroups = decode(brownMooshroom).getListValues("definitions", StringTag.class);
        require("brown mooshroom", brownMooshroomGroups, "minecraft:mooshroom_brown");

        CompoundTag redMooshroom = withString("minecraft:mooshroom", "Type", "red");
        List<String> redMooshroomGroups = decode(redMooshroom).getListValues("definitions", StringTag.class);
        require("red mooshroom", redMooshroomGroups, "minecraft:mooshroom_red");

        // Cat is where a guessed enum order would go wrong: Bedrock's 9 is the solid black cat and 1 is the tuxedo,
        // which is the opposite of what the registry order suggests.
        CompoundTag blackCat = withString("minecraft:cat", "variant", "minecraft:black");
        List<String> blackCatGroups = decode(blackCat).getListValues("definitions", StringTag.class);
        require("black cat", blackCatGroups, "minecraft:cat_black");
        requireValue("black cat variant", blackCat, "Variant", 9);

        CompoundTag tuxedoCat = withString("minecraft:cat", "variant", "minecraft:all_black");
        require("tuxedo cat", decode(tuxedoCat).getListValues("definitions", StringTag.class),
                "minecraft:cat_tuxedo");
        requireValue("tuxedo cat variant", tuxedoCat, "Variant", 1);

        // Wolf and rabbit take their integer straight from the pack as well.
        CompoundTag wolf = withString("minecraft:wolf", "variant", "minecraft:rusty");
        require("rusty wolf", decode(wolf).getListValues("definitions", StringTag.class), "minecraft:wolf_rusty");
        requireValue("rusty wolf variant", wolf, "Variant", 4);

        CompoundTag rabbit = withField("minecraft:rabbit", "RabbitType", 4);
        require("desert rabbit", decode(rabbit).getListValues("definitions", StringTag.class), "minecraft:coat_desert");
        requireValue("desert rabbit variant", rabbit, "Variant", 4);

        // The axolotl is the case where the two orderings differ: Java 1 is `wild`, Bedrock renders it as 3.
        CompoundTag axolotl = withField("minecraft:axolotl", "Variant", 1);
        require("wild axolotl", decode(axolotl).getListValues("definitions", StringTag.class),
                "minecraft:axolotl_wild");
        requireValue("wild axolotl variant", axolotl, "Variant", 3);

        // Shulker colours are not the dye order on the Bedrock side: black is 0.
        CompoundTag shulker = withField("minecraft:shulker", "Color", (byte) 15);
        require("black shulker", decode(shulker).getListValues("definitions", StringTag.class),
                "minecraft:shulker_black");
        requireValue("black shulker variant", shulker, "Variant", 0);
    }

    /**
     * @param label   what is being checked.
     * @param javaId  the Java entity id.
     */
    private static void requireSkipped(String label, String javaId) {
        CompoundTag entity = new CompoundTag(4);
        entity.put("id", javaId);
        if (BedrockActorBuilder.build(entity, 1L) != null) {
            throw new IllegalStateException(label + " should not produce an actor");
        }
    }

    private static List<String> definitions(String javaId, boolean baby) throws Exception {
        CompoundTag decoded = decode(build(javaId, baby));
        return decoded.getListValues("definitions", StringTag.class);
    }

    private static CompoundTag build(String javaId, boolean baby) {
        CompoundTag entity = entity(javaId);
        if (baby) {
            entity.put("IsBaby", (byte) 1);
        }
        return actor(entity);
    }

    private static CompoundTag withField(String javaId, String field, int value) {
        CompoundTag entity = entity(javaId);
        entity.put(field, value);
        return actor(entity);
    }

    private static CompoundTag withString(String javaId, String field, String value) {
        CompoundTag entity = entity(javaId);
        entity.put(field, value);
        return actor(entity);
    }

    private static CompoundTag entity(String javaId) {
        CompoundTag entity = new CompoundTag(4);
        entity.put("id", javaId);

        ListTag<DoubleTag, Double> pos = new ListTag<>(TagType.DOUBLE, 3);
        pos.add(new DoubleTag(0D));
        pos.add(new DoubleTag(64D));
        pos.add(new DoubleTag(0D));
        entity.put("Pos", pos);
        return entity;
    }

    private static CompoundTag actor(CompoundTag entity) {
        CompoundTag actor = BedrockActorBuilder.build(entity, 1L);
        if (actor == null) {
            throw new IllegalStateException("no actor built for " + entity.getString("id", "?"));
        }
        return actor;
    }

    private static CompoundTag decode(CompoundTag actor) throws Exception {
        byte[] bytes = Tag.writeBedrockNBT(actor);
        try (DataInputStream stream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            TagWithName<CompoundTag> decoded = Tag.decodeNamed(Reader.toBedrockReader(stream), CompoundTag.class);
            if (decoded == null) {
                throw new IllegalStateException("actor did not round trip");
            }
            return decoded.tag();
        }
    }

    private static void requireValue(String label, CompoundTag actor, String key, int expected) {
        int actual = actor.getInt(key, Integer.MIN_VALUE);
        if (actual != expected) {
            throw new IllegalStateException(label + ": expected " + key + "=" + expected + " but was " + actual);
        }
    }

    private static void require(String label, List<String> actual, String... expected) {
        for (String group : expected) {
            if (!actual.contains("+" + group)) {
                throw new IllegalStateException(label + " is missing +" + group + " (got " + actual + ")");
            }
        }
        if (label.startsWith("rabbit") && actual.size() != 1) {
            throw new IllegalStateException("rabbit should only have its own identifier, got " + actual);
        }
    }
}
