package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.DoubleTag;
import com.hivemc.chunker.nbt.tags.primitive.FloatTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import com.hivemc.chunker.util.JsonTextUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a Bedrock actor record into a Java entity tag.
 * <p>
 * Why this exists: on Bedrock the engine only registers entity handlers for paintings, item frames and cushions, so
 * every other mob arrives as an {@code UnknownEntity} carrying the raw actor tag. The generic Java writer would then
 * copy that tag through untouched - a Bedrock tag with an {@code identifier} field, float coordinates and no UUID,
 * most of which Java either ignores or cannot read. The result is an entity that either disappears on load or shows up
 * as a blank mob with nothing it was carrying.
 * <p>
 * What is translated: the entity id (through the engine's own type resolver, so {@code zombie_pigman},
 * {@code tropicalfish} and {@code ender_crystal} get their Java names back), position/motion/rotation in the Java
 * numeric types, a UUID derived from the Bedrock unique id, health, the custom name, the payload entities (dropped
 * items and experience orbs), and then whatever {@link JavaEntityStates} and {@link JavaEntityEquipment} know about.
 * <p>
 * What is not: variants (wool colour, horse markings, cat and wolf coats, the tropical fish pattern), containers,
 * leashes, passengers and the bed/block fields of the payload entities. Those need their own tables, the same way the
 * forward pass needed {@link EntityVariants}, and until then the mob arrives with the default appearance rather than
 * not arriving at all.
 */
public final class BedrockToJava {

    private static final String NAMESPACE = "minecraft:";

    /** How deep a stack of riders is followed before giving up, which only matters if the links form a cycle. */
    private static final int MAX_PASSENGER_DEPTH = 8;

    private BedrockToJava() {
    }

    /**
     * Translate one entity, if the raw Bedrock tag is available.
     *
     * @param entity  the entity that was read from the Bedrock world.
     * @param target  resolvers of the Java version being written.
     * @param version the Java version being written.
     * @return the Java entity tag, or null when this entity has to go through the generic path instead.
     */
    @Nullable
    public static CompoundTag translate(Entity entity, JavaResolvers target, Version version) {
        return translate(entity, target, version, 0);
    }

    /**
     * @param depth how deep this actor is inside another one's {@code Passengers}; a stack of mobs is legal but a
     *              cycle is not, and Bedrock's links could describe one.
     */
    @Nullable
    private static CompoundTag translate(Entity entity, JavaResolvers target, Version version, int depth) {
        if (depth > MAX_PASSENGER_DEPTH) {
            return null;
        }
        CompoundTag actor = entity.getOriginalNBT();
        if (actor == null) {
            // Nothing was kept when reading: the caller falls back to the engine's own behaviour, which is a bare
            // entity with a correct position and nothing else.
            return null;
        }

        Optional<String> id = target.entityTypeResolver().from(entity.getEntityType());
        if (id.isEmpty()) {
            // A Bedrock type with no Java counterpart (an education edition mob, a custom identifier).
            return null;
        }

        BedrockResolvers source = entity.getSourceResolvers() instanceof BedrockResolvers bedrock ? bedrock : null;

        CompoundTag javaEntity = new CompoundTag(16);
        javaEntity.put("id", id.get());
        javaEntity.put("UUID", uuid(uniqueId(entity, actor)));

        writePosition(javaEntity, entity);
        health(javaEntity, actor);
        customName(javaEntity, actor);
        persistent(javaEntity, actor);
        commonState(javaEntity, actor);
        payload(javaEntity, actor, source, target, id.get());

        String identifier = actor.getString("identifier", null);
        if (identifier == null || identifier.isEmpty()) {
            identifier = id.get();
        }
        List<String> definitions = actor.getListValues("definitions", StringTag.class, null);

        JavaEntityStates.apply(javaEntity, actor, identifier, version, source, target);
        JavaEntityEquipment.apply(javaEntity, actor, source, target, version.isGreaterThanOrEqual(1, 21, 5));
        JavaEntityVariants.apply(javaEntity, actor, identifier, definitions);
        JavaEntityContainers.apply(javaEntity, actor, identifier, source, target);
        JavaEntityLinks.apply(javaEntity, actor, version);
        passengers(javaEntity, entity, target, version, depth);

        return javaEntity;
    }

    /**
     * Nest the riders inside the vehicle, which is the only way Java expresses "this pig is sitting in that boat".
     * <p>
     * The reader already paired them up within a column and took the riders out of the column, so what is left here is
     * just the recursion - and the possibility that one of them is carrying someone too.
     */
    private static void passengers(CompoundTag javaEntity, Entity entity, JavaResolvers target, Version version,
                                   int depth) {
        List<Entity> riders = entity.getPassengers();
        if (riders == null || riders.isEmpty()) {
            return;
        }
        ListTag<CompoundTag, Map<String, Tag<?>>> list = new ListTag<>(TagType.COMPOUND, riders.size());
        for (Entity rider : riders) {
            CompoundTag translated = translate(rider, target, version, depth + 1);
            if (translated != null) {
                list.add(translated);
            }
        }
        if (list.size() > 0) {
            javaEntity.put("Passengers", list);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Core fields
    // ---------------------------------------------------------------------------------------------

    /**
     * Health is a float on both sides, but Bedrock does not always write it as one - some actors carry an int or a
     * short - and Java casts the tag it finds. Writing the number rather than copying the tag keeps the type right.
     */
    private static void health(CompoundTag javaEntity, CompoundTag actor) {
        Object value = actor.contains("Health") ? actor.get("Health").getBoxedValue() : null;
        if (value instanceof Number number && number.floatValue() > 0F) {
            javaEntity.put("Health", number.floatValue());
        }
    }

    /**
     * Java wants doubles where Bedrock uses floats, and the engine's reader has already parsed the Bedrock values
     * into the intermediate entity, so they are read back from there rather than from the tag.
     */
    private static void writePosition(CompoundTag javaEntity, Entity entity) {
        ListTag<DoubleTag, Double> pos = new ListTag<>(TagType.DOUBLE, 3);
        pos.add(new DoubleTag(entity.getPositionX()));
        pos.add(new DoubleTag(entity.getPositionY()));
        pos.add(new DoubleTag(entity.getPositionZ()));
        javaEntity.put("Pos", pos);

        ListTag<DoubleTag, Double> motion = new ListTag<>(TagType.DOUBLE, 3);
        motion.add(new DoubleTag(entity.getMotionX()));
        motion.add(new DoubleTag(entity.getMotionY()));
        motion.add(new DoubleTag(entity.getMotionZ()));
        javaEntity.put("Motion", motion);

        ListTag<FloatTag, Float> rotation = new ListTag<>(TagType.FLOAT, 2);
        rotation.add(new FloatTag(entity.getYaw()));
        rotation.add(new FloatTag(entity.getPitch()));
        javaEntity.put("Rotation", rotation);
    }

    /**
     * Every Java entity carries a UUID; Bedrock only has the numeric {@code UniqueID}. It is hashed into a version 4
     * UUID rather than generated randomly so that two runs over the same world produce the same identifiers, which
     * is what makes an output diffable.
     */
    static int[] uuid(long uniqueId) {
        long high = mix(uniqueId + 0x9E3779B97F4A7C15L);
        long low = mix(high ^ 0xDEADBEEFCAFEBABEL);
        // Version 4 and the RFC 4122 variant, which is what every other entity in a Java world has.
        high = (high & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        low = (low & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new int[]{(int) (high >>> 32), (int) high, (int) (low >>> 32), (int) low};
    }

    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long uniqueId(Entity entity, CompoundTag actor) {
        if (actor.contains("UniqueID")) {
            Object value = actor.get("UniqueID").getBoxedValue();
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        // No id in the record: derive something stable from the position so the entity still gets a usable UUID.
        long x = Double.doubleToLongBits(entity.getPositionX());
        long y = Double.doubleToLongBits(entity.getPositionY());
        long z = Double.doubleToLongBits(entity.getPositionZ());
        return mix(x) ^ (mix(y) << 1) ^ (mix(z) << 2);
    }

    /**
     * A named mob in Bedrock keeps a legacy formatted string, Java a JSON text component. Colour codes are dropped
     * rather than translated: keeping them would make them show up as literal characters in the Java name plate.
     */
    private static void customName(CompoundTag javaEntity, CompoundTag actor) {
        String name = actor.getString("CustomName", null);
        if (name == null || name.isEmpty()) {
            return;
        }
        String text = JsonTextUtil.LEGACY_FORMAT_PATTERN.matcher(name).replaceAll("");
        if (text.isEmpty()) {
            return;
        }
        javaEntity.put("CustomName", JsonTextUtil.toJSON(JsonTextUtil.fromText(text)));
        if (intValue(actor, "CustomNameVisible", 0) != 0) {
            javaEntity.put("CustomNameVisible", (byte) 1);
        }
    }

    /**
     * Fields the two editions happen to spell the same way, which the generic path used to copy by accident and a
     * whitelist would otherwise lose.
     * <p>
     * {@code Age} is the important one: a baby mob keeps a negative age and without it every converted calf, foal and
     * kitten comes back as an adult. Java reads it as an int where Bedrock writes a short, so the number is rewritten
     * rather than copied.
     */
    private static void commonState(CompoundTag javaEntity, CompoundTag actor) {
        int age = intValue(actor, "Age", 0);
        if (age != 0) {
            javaEntity.put("Age", age);
        }
        copyFlag(javaEntity, actor, "Sheared");
        copyFlag(javaEntity, actor, "Angry");
    }

    private static void copyFlag(CompoundTag javaEntity, CompoundTag actor, String name) {
        if (intValue(actor, name, 0) != 0) {
            javaEntity.put(name, (byte) 1);
        }
    }

    /**
     * Bedrock's {@code Persistent} is Java's {@code PersistenceRequired}; without it a converted mob despawns the
     * first time the player walks away, which is exactly what happened to the named mobs of every converted world.
     */
    private static void persistent(CompoundTag javaEntity, CompoundTag actor) {
        if (intValue(actor, "Persistent", 0) != 0) {
            javaEntity.put("PersistenceRequired", (byte) 1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Payload entities
    // ---------------------------------------------------------------------------------------------

    /**
     * Dropped items and experience orbs are entities whose whole content is one field, and that field has a different
     * name and shape on the two sides. Without this an item entity arrives as a blank entity that the game throws
     * away on the first tick.
     */
    private static void payload(CompoundTag javaEntity, CompoundTag actor,
                                @Nullable BedrockResolvers source, @Nullable JavaResolvers target, String javaId) {
        switch (javaId) {
            case NAMESPACE + "item" -> {
                CompoundTag item = JavaItemStacks.convert(actor.getCompound("Item", null), source, target);
                if (item != null) {
                    javaEntity.put("Item", item);
                }
                // Without a stack the game drops the entity on its first tick, which is better than writing an empty
                // one it would log an error about. Age is written by commonState.
            }
            case NAMESPACE + "experience_orb" -> {
                // Bedrock spells the key with a space in it, and keeps a full int where Java has a short.
                int value = intValue(actor, "experience value", 1);
                javaEntity.put("Value", (short) Math.max(1, Math.min(Short.MAX_VALUE, value)));
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static int intValue(CompoundTag tag, String name, int defaultValue) {
        Object value = tag.contains(name) ? tag.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.intValue() : defaultValue;
    }
}
