package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.TagWithName;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.DoubleTag;
import com.hivemc.chunker.nbt.tags.primitive.FloatTag;
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
        trades();
        links();

        System.out.println("entity table: OK");
    }

    /**
     * Trades: Bedrock replaces whatever it would have generated with the written list, which is why a villager the
     * player traded with carries every recipe (not just the used ones) and an untouched one carries none.
     */
    private static void trades() throws Exception {
        EntityTrades.Items items = item -> {
            if (item == null) {
                return null;
            }
            String id = item.getString("id", null);
            if (id == null || id.contains("barrier")) {
                return null;
            }
            CompoundTag stack = new CompoundTag(3);
            stack.put("Name", id);
            stack.put("Count", (byte) 1);
            stack.put("Damage", (short) 0);
            return stack;
        };

        CompoundTag villager = entity("minecraft:villager");
        villager.put("Offers", offers(
                recipe("minecraft:bread", 0, true),
                recipe("minecraft:book", 3, false),
                recipe("minecraft:barrier", 0, true),
                recipe("minecraft:cake", 0, true)));

        CompoundTag actor = actor(villager);
        EntityTrades.apply(actor, "minecraft:villager_v2", villager, items);
        ListTag<CompoundTag, Map<String, Tag<?>>> written = recipes(decode(actor));

        // Four recipes in, one of them impossible to represent: three out, and the tier still follows the position in
        // the original list rather than the position among the survivors.
        require("trades kept", written.size() == 3, "expected 3 written recipes, got " + written.size());
        requireValue("first tier", written.get(0), "tier", 0);
        requireValue("second tier", written.get(1), "tier", 0);
        requireValue("third tier", written.get(2), "tier", 1);

        // A used recipe keeps its counters, and its experience is renamed on the way over.
        CompoundTag second = written.get(1);
        requireValue("uses", second, "uses", 3);
        requireValue("maxUses", second, "maxUses", 16);
        requireValue("traderExp", second, "traderExp", 2);
        require("priceMultiplierA", Math.abs(second.getFloat("priceMultiplierA", -1F) - 0.05F) < 1e-4,
                "price multiplier was not carried over: " + second.get("priceMultiplierA"));
        if (!"minecraft:book".equals(second.getCompound("sell", new CompoundTag(0)).getString("Name", null))) {
            throw new IllegalStateException("sell side is wrong: " + second);
        }

        // The second input is optional, so a recipe without one must not grow an empty slot.
        if (second.contains("buyB")) {
            throw new IllegalStateException("a recipe without buyB grew one: " + second);
        }
        if (!written.get(0).contains("buyB")) {
            throw new IllegalStateException("buyB was dropped from a recipe that had one: " + written.get(0));
        }

        // Nothing was ever traded: leave the tag out and let the game generate the profession's own list.
        CompoundTag untouched = entity("minecraft:villager");
        untouched.put("Offers", offers(recipe("minecraft:bread", 0, false)));
        CompoundTag untouchedActor = actor(untouched);
        EntityTrades.apply(untouchedActor, "minecraft:villager_v2", untouched, items);
        if (untouchedActor.contains("Offers")) {
            throw new IllegalStateException("an untouched villager should not carry an offers list");
        }

        // A wandering trader has a flat list - every recipe is tier zero even four entries in.
        CompoundTag trader = entity("minecraft:wandering_trader");
        trader.put("Offers", offers(
                recipe("minecraft:bread", 1, false),
                recipe("minecraft:book", 0, false),
                recipe("minecraft:cake", 0, false),
                recipe("minecraft:emerald", 0, false)));
        CompoundTag traderActor = actor(trader);
        EntityTrades.apply(traderActor, "minecraft:wandering_trader", trader, items);
        ListTag<CompoundTag, Map<String, Tag<?>>> traderRecipes = recipes(decode(traderActor));
        require("wandering trader", traderRecipes.size() == 4, "expected 4 recipes, got " + traderRecipes.size());
        for (CompoundTag recipe : traderRecipes) {
            requireValue("wandering trader tier", recipe, "tier", 0);
        }
    }

    /**
     * Links: a passenger that also carries passengers has to keep both in one tag, and a lead tied to a fence becomes
     * coordinates rather than a reference to an entity Bedrock does not have.
     */
    private static void links() throws Exception {
        CompoundTag boat = entity("minecraft:pig");
        EntityLinks.link(boat, 42L, List.of(7L, 9L));

        ListTag<CompoundTag, Map<String, Tag<?>>> links = boat.getList("LinksTag", CompoundTag.class, null);
        require("links", links != null && links.size() == 3, "expected one entry per side, got " + links);
        requireLong("own ride", links.get(0), "RideID", 42L);
        requireLong("first rider", links.get(1), "VehicleID", 7L);
        requireLong("second rider", links.get(2), "VehicleID", 9L);

        // One side only: the tag still has to exist, but with a single entry.
        CompoundTag rider = entity("minecraft:pig");
        EntityLinks.link(rider, 42L, null);
        ListTag<CompoundTag, Map<String, Tag<?>>> single = rider.getList("LinksTag", CompoundTag.class, null);
        require("single link", single != null && single.size() == 1, "expected one entry, got " + single);

        // Neither side: nothing is written at all.
        CompoundTag free = entity("minecraft:pig");
        EntityLinks.link(free, 0L, List.of());
        if (free.contains("LinksTag")) {
            throw new IllegalStateException("an unlinked actor should not carry a links tag");
        }

        // The knot a lead is tied to is an entity on the Java side only.
        CompoundTag knot = entity("minecraft:leash_knot");
        ListTag<DoubleTag, Double> pos = new ListTag<>(TagType.DOUBLE, 3);
        pos.add(new DoubleTag(10D));
        pos.add(new DoubleTag(64D));
        pos.add(new DoubleTag(-3D));
        knot.put("Pos", pos);
        if (!EntityLinks.isLeashKnot(knot)) {
            throw new IllegalStateException("a leash knot was not recognised");
        }

        // A knot stands on a block, so the position it is indexed by is spelled TileX/TileY/TileZ, not Pos.
        CompoundTag blockKnot = entity("minecraft:leash_knot");
        blockKnot.put("TileX", 11);
        blockKnot.put("TileY", 65);
        blockKnot.put("TileZ", -4);
        long[] position = EntityLinks.position(blockKnot);
        require("knot position", position != null && position[0] == 11 && position[1] == 65 && position[2] == -4,
                "expected the tile position, got " + (position == null ? "null" : java.util.Arrays.toString(position)));

        // 1.21 writes the post straight onto the animal as an int array, and there is no knot entity at all.
        CompoundTag tiedToFence = entity("minecraft:sheep");
        tiedToFence.put("leash", new IntArrayTag(new int[]{-90, -60, 100}));
        EntityLinks.Leash block = EntityLinks.leash(tiedToFence, uuid -> 0L);
        require("block leash", block != null && block.isBlock() && block.x() == -90 && block.z() == 100,
                "expected the block position, got " + block);

        // A leash compound without a UUID used to throw ClassCastException on the int array read.
        CompoundTag emptyLeash = entity("minecraft:sheep");
        emptyLeash.put("leash", new CompoundTag(0));
        if (EntityLinks.leash(emptyLeash, uuid -> 0L) != null) {
            throw new IllegalStateException("a leash with neither block nor uuid should resolve to nothing");
        }

        // The knot Bedrock wants: an actor of its own, one per fence, pointed at through LeasherID.
        long knotId = EntityLinks.knotId(-90, -60, 100);
        require("knot id", knotId == EntityLinks.knotId(-90, -60, 100) && knotId != 0L, "the id has to be stable");
        require("knot id differs", knotId != EntityLinks.knotId(-89, -60, 100), "different blocks need different ids");
        CompoundTag knotActor = EntityLinks.knot(knotId, -90, -60, 100);
        Object storedId = knotActor.contains("UniqueID") ? knotActor.get("UniqueID").getBoxedValue() : null;
        require("knot id in tag", storedId instanceof Number number && number.longValue() == knotId,
                "wrong id in the tag: " + storedId);
        require("knot identifier", "minecraft:leash_knot".equals(knotActor.getString("identifier", null)),
                "wrong identifier: " + knotActor.getString("identifier", null));
        List<Float> knotPos = knotActor.getListValues("Pos", FloatTag.class, null);
        require("knot pos", knotPos != null && knotPos.size() == 3
                        && knotPos.get(0) == -89.5F && knotPos.get(1) == -59.75F && knotPos.get(2) == 100.5F,
                "expected the block centre a quarter block up, got " + knotPos);
        require("knot definitions", knotActor.contains("definitions"),
                "the game writes an empty definitions list for a knot");
    }

    private static CompoundTag offers(CompoundTag... entries) {
        ListTag<CompoundTag, Map<String, Tag<?>>> recipes = new ListTag<>(TagType.COMPOUND, entries.length);
        for (CompoundTag entry : entries) {
            recipes.add(entry);
        }
        CompoundTag offers = new CompoundTag(1);
        offers.put("Recipes", recipes);
        return offers;
    }

    private static CompoundTag recipe(String sellId, int uses, boolean secondInput) {
        CompoundTag recipe = new CompoundTag(8);
        recipe.put("buy", itemStack("minecraft:emerald", 3));
        if (secondInput) {
            recipe.put("buyB", itemStack("minecraft:book", 1));
        }
        recipe.put("sell", itemStack(sellId, 2));
        recipe.put("maxUses", 16);
        recipe.put("uses", uses);
        recipe.put("xp", 2);
        recipe.put("priceMultiplier", 0.05F);
        return recipe;
    }

    private static CompoundTag itemStack(String id, int count) {
        CompoundTag stack = new CompoundTag(2);
        stack.put("id", id);
        stack.put("count", count);
        return stack;
    }

    private static ListTag<CompoundTag, Map<String, Tag<?>>> recipes(CompoundTag actor) {
        CompoundTag offers = actor.getCompound("Offers", null);
        if (offers == null) {
            throw new IllegalStateException("actor has no offers: " + actor);
        }
        ListTag<CompoundTag, Map<String, Tag<?>>> recipes = offers.getList("Recipes", CompoundTag.class, null);
        if (recipes == null) {
            throw new IllegalStateException("offers have no recipes: " + offers);
        }
        return recipes;
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

    private static void require(String label, boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(label + ": " + message);
        }
    }

    private static void requireLong(String label, CompoundTag actor, String key, long expected) {
        Object value = actor.contains(key) ? actor.get(key).getBoxedValue() : null;
        if (!(value instanceof Number number) || number.longValue() != expected) {
            throw new IllegalStateException(label + ": expected " + key + "=" + expected + " but was " + value);
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
