package com.frenetic.litechunk.entities;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import com.hivemc.chunker.util.JsonTextUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The parts of an actor that are not about its kind: its name, whether it may despawn, and its health.
 * <p>
 * A real save carries about sixty keys on every actor, and roughly forty of them are a fixed template of zeros
 * ({@code IsAngry}, {@code IsGliding}, {@code OwnerID}, {@code SkinID} and so on). Those are deliberately <em>not</em>
 * written:
 * <ul>
 *     <li>they carry no state - the value is the same for a freshly spawned sheep and a two-year-old one;</li>
 *     <li>the game fills them from the entity components when the actor first ticks, which is why our earlier output
 *     with six keys still produced working mobs;</li>
 *     <li>writing them means forty chances to get a type wrong (byte vs int) for no gain.</li>
 * </ul>
 * What is written here is the part that <em>is</em> state and cannot be reconstructed: the name, the persistence
 * flag, and the current/max health.
 */
final class EntityTemplate {

    private EntityTemplate() {
    }

    /**
     * Apply the template fields of an entity.
     *
     * @param actor      the actor tag being built, modified in place.
     * @param javaEntity the Java entity NBT.
     */
    static void apply(CompoundTag actor, String identifier, CompoundTag javaEntity) {
        name(actor, javaEntity);
        persistence(actor, javaEntity);
        health(actor, identifier, javaEntity);
    }

    // ---------------------------------------------------------------------------------------------
    // Name
    // ---------------------------------------------------------------------------------------------

    /**
     * Java keeps the name as a JSON text component under {@code CustomName}, Bedrock as a legacy formatted string.
     * The colour codes survive: {@code toLegacy(..., true)} writes the section sign form Bedrock renders.
     */
    private static void name(CompoundTag actor, CompoundTag entity) {
        Tag<?> raw = entity.get("CustomName");
        if (raw == null) {
            return;
        }

        // 1.21.5 stores the name as a JSON string; older worlds (and anything written by a plugin) use a compound
        // text component. Reading only the string variant silently dropped those names.
        String legacy;
        if (raw instanceof StringTag stringTag) {
            String value = stringTag.getValue();
            if (value == null || value.isEmpty()) {
                return;
            }
            legacy = toLegacy(value);
        } else if (raw instanceof CompoundTag || raw instanceof ListTag<?, ?>) {
            try {
                legacy = JsonTextUtil.toLegacy(JsonTextUtil.fromNBT(raw), true);
            } catch (RuntimeException failure) {
                return;
            }
        } else {
            return;
        }

        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        actor.put("CustomName", legacy);
        // A named mob in Java only shows the plate when CustomNameVisible is set; Bedrock shows CustomName always, so
        // the flag is carried across rather than assumed.
        actor.put("CustomNameVisible", (byte) (intValue(entity, "CustomNameVisible", 0) != 0 ? 1 : 0));
    }

    /**
     * @param raw the Java name, either a JSON component or a plain string.
     * @return a Bedrock legacy string, or null when it cannot be parsed.
     */
    @Nullable
    private static String toLegacy(String raw) {
        try {
            JsonElement json = JsonParser.parseString(raw);
            return JsonTextUtil.toLegacy(json, true);
        } catch (Exception failure) {
            // Older worlds stored the name as plain text; anything unparseable is used as is rather than dropped.
            return raw;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------

    /**
     * Java {@code PersistenceRequired} stops a mob from despawning; Bedrock spells it {@code Persistent}. The key name
     * is confirmed by the game binary (the literal {@code Persistent} exists, {@code PersistenceRequired} does not).
     */
    private static void persistence(CompoundTag actor, CompoundTag entity) {
        if (!entity.contains("PersistenceRequired")) {
            return;
        }
        actor.put("Persistent", (byte) (intValue(entity, "PersistenceRequired", 0) != 0 ? 1 : 0));
    }

    // ---------------------------------------------------------------------------------------------
    // Health
    // ---------------------------------------------------------------------------------------------

    /**
     * Bedrock keeps health in the {@code Attributes} list, one entry per attribute, each with its own bounds:
     * <pre>
     * {Base:8.0f, Current:8.0f, DefaultMax:8.0f, DefaultMin:0.0f, Max:8.0f, Min:0.0f, Name:"minecraft:health"}
     * </pre>
     * Java splits the same information in two: the maximum comes from the {@code attributes} list
     * ({@code minecraft:max_health}) and the current value from the separate {@code Health} float.
     * <p>
     * Only the health entry is written. The other attributes a real save carries (movement, follow_range and the
     * rest) are recomputed from the entity components, and their bounds differ per attribute in ways this pass has no
     * data for - inventing them would be worse than leaving them to the game.
     */
    private static void health(CompoundTag actor, String identifier, CompoundTag entity) {
        Float current = floatValue(entity, "Health");
        Float maximum = attributeBase(entity, "minecraft:max_health");
        if (maximum == null) {
            // Java only stores max_health when it differs from the entity's default, so most mobs have no entry here.
            // The pack's own health component supplies the default; using the current health instead would cap a
            // damaged animal at whatever it happened to have when the world was saved.
            int fromPack = EntityGroupTable.health(identifier);
            if (fromPack > 0) {
                maximum = (float) fromPack;
            }
        }
        if (maximum == null) {
            // Unknown entity, or a rolled range the world did not record: leave the attributes to the game.
            return;
        }
        float max = maximum;
        float now = current != null ? Math.min(current, max) : max;

        ListTag<CompoundTag, Map<String, Tag<?>>> attributes = new ListTag<>(TagType.COMPOUND, 3);
        attributes.add(attribute("minecraft:health", max, now));
        // Speed and jump height are the two a player notices on a horse: they are rolled per animal in Java and the
        // game has no way to recover them. Java stores them in its own attribute list, Bedrock under different names
        // - `minecraft:horse.jump_strength` is a literal in the game binary, `minecraft:jump_strength` is not.
        addAttribute(attributes, entity, "minecraft:movement_speed", "minecraft:movement");
        addAttribute(attributes, entity, "minecraft:jump_strength", "minecraft:horse.jump_strength");
        actor.put("Attributes", attributes);
    }

    /**
     * Copy one Java attribute across under its Bedrock name.
     *
     * @param attributes the list being built.
     * @param entity     the Java entity tag.
     * @param javaId     the Java attribute id.
     * @param bedrockId  the Bedrock attribute id.
     */
    private static void addAttribute(ListTag<CompoundTag, Map<String, Tag<?>>> attributes, CompoundTag entity,
                                     String javaId, String bedrockId) {
        Float base = attributeBase(entity, javaId);
        if (base == null || base <= 0F) {
            return;
        }
        // A real save writes {Base, DefaultMax, DefaultMin, Name} for these two, without Current - the current value
        // follows Base unless something modifies it.
        CompoundTag entry = new CompoundTag(4);
        entry.put("Name", bedrockId);
        entry.put("Base", base);
        entry.put("DefaultMax", Float.MAX_VALUE);
        entry.put("DefaultMin", 0F);
        attributes.add(entry);
    }

    /**
     * Build one health style attribute entry.
     *
     * @param name    the Bedrock attribute id.
     * @param base    the maximum.
     * @param current the current value.
     * @return the entry.
     */
    private static CompoundTag attribute(String name, float base, float current) {
        CompoundTag entry = new CompoundTag(7);
        entry.put("Name", name);
        entry.put("Base", base);
        entry.put("Current", current);
        entry.put("DefaultMax", base);
        entry.put("DefaultMin", 0F);
        entry.put("Max", base);
        entry.put("Min", 0F);
        return entry;
    }

    /**
     * Java stores the maximum as one entry of its own attribute list.
     *
     * @param entity the entity tag.
     * @param id     the attribute id, e.g. {@code minecraft:max_health}.
     * @return the base value, or null when the entity does not carry that attribute.
     */
    @Nullable
    private static Float attributeBase(CompoundTag entity, String id) {
        ListTag<CompoundTag, Map<String, Tag<?>>> attributes =
                entity.getList("attributes", CompoundTag.class, null);
        if (attributes == null) {
            return null;
        }
        for (CompoundTag attribute : attributes) {
            if (id.equals(attribute.getString("id", null)) && attribute.contains("base")) {
                Object base = attribute.get("base").getBoxedValue();
                if (base instanceof Number number) {
                    return number.floatValue();
                }
            }
        }
        return null;
    }

    @Nullable
    private static Float floatValue(CompoundTag entity, String name) {
        Object value = entity.contains(name) ? entity.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.floatValue() : null;
    }

    private static int intValue(CompoundTag entity, String name, int defaultValue) {
        Object value = entity.contains(name) ? entity.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
