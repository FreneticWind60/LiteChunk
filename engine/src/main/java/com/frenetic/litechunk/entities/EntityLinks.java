package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.array.IntArrayTag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.DoubleTag;
import com.hivemc.chunker.nbt.tags.primitive.FloatTag;
import com.hivemc.chunker.nbt.tags.primitive.StringTag;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The connections between two actors: who rides what, and who is holding whose leash.
 * <p>
 * Java nests the rider inside the vehicle's {@code Passengers} list, so a pig sitting in a boat is not a separate
 * entity at all - it exists only as a compound inside the boat. Bedrock does the opposite: every actor stands on its
 * own and the relationship is recorded in {@code LinksTag}, one entry per side.
 * <p>
 * <strong>Both halves of a link belong in one tag.</strong> An actor can be a passenger and carry passengers at the
 * same time, so the two directions have to be written together; writing them one after the other replaced the first
 * list with the second and silently dropped the {@code RideID} of anything that was doing both.
 * <p>
 * <strong>A lead has three shapes, and getting it wrong is silent.</strong> Java 1.21 stores a fence tie as an int
 * array of the block coordinates on the animal itself and spawns no knot entity at all; an older world, or a lead held
 * by a mob, stores a compound with the holder's UUID instead. Bedrock always keeps a knot as an actor of its own, with
 * the animal pointing at it through {@code LeasherID} - a lead written any other way simply does not appear in game.
 * <p>
 * Before 1.21 a lead to a fence also cost a world wide lookup: the animal held a UUID pointing at a knot entity
 * standing in the chunk the fence was in, which is rarely the animal's own chunk, so those knot positions are
 * collected for the whole world first. That version is gone; 1.21 puts the coordinates on the animal.
 */
final class EntityLinks {

    /** The field Java uses for a leash since 1.20.5; the capitalised form is the older one. */
    private static final String[] LEASH_FIELDS = {"leash", "Leash"};

    /** Java's entity id for the knot a lead is tied to. Bedrock has one too, see {@link #knot}. */
    private static final String LEASH_KNOT = "minecraft:leash_knot";

    private EntityLinks() {
    }

    /**
     * Record both directions of a mounting relationship on this actor.
     *
     * @param actor     the actor tag, modified in place.
     * @param vehicleId the unique id of what this actor rides, or 0 when it is not a passenger.
     * @param riderIds  the unique ids of the actors on top of it, may be null or empty.
     */
    static void link(CompoundTag actor, long vehicleId, @Nullable List<Long> riderIds) {
        int riders = riderIds == null ? 0 : riderIds.size();
        if (vehicleId == 0L && riders == 0) {
            return;
        }

        ListTag<CompoundTag, Map<String, Tag<?>>> links = new ListTag<>(TagType.COMPOUND,
                riders + (vehicleId != 0L ? 1 : 0));
        if (vehicleId != 0L) {
            links.add(entry(vehicleId, 0L));
        }
        if (riderIds != null) {
            for (long riderId : riderIds) {
                links.add(entry(0L, riderId));
            }
        }
        actor.put("LinksTag", links);
    }

    /**
     * Mark this actor as leashed to another entity.
     *
     * @param actor    the actor tag, modified in place.
     * @param holderId the unique id of the leash holder.
     */
    static void leashed(CompoundTag actor, long holderId) {
        actor.put("LeasherID", holderId);
    }

    /**
     * Build the Bedrock actor for a knot a lead is tied to.
     * <p>
     * The game itself was the source for this: a lead tied to a fence in Bedrock produces an actor of its own,
     * <pre>
     * {identifier:"minecraft:leash_knot", definitions:[], Pos:[0.5f,-59.75f,1.5f], Motion:[0f,0f,0f],
     *  Rotation:[0f,0f], OnGround:1b, Sitting:0b, Tags:[], OwnerNew:-1l, UniqueID:-4294967286l}
     * </pre>
     * with the leashed animal pointing at it through {@code LeasherID}. The post the ROPE is tied to is not stored on
     * the animal at all - writing {@code hasBoundOrigin} there (an earlier guess) left the lead dangling and it
     * vanished in game.
     *
     * @param id     the unique id of the knot, see {@link #knotId}.
     * @param blockX the block the lead is tied to.
     * @param blockY the block the lead is tied to.
     * @param blockZ the block the lead is tied to.
     * @return the actor tag.
     */
    static CompoundTag knot(long id, long blockX, long blockY, long blockZ) {
        CompoundTag knot = new CompoundTag(10);
        knot.put("identifier", "minecraft:leash_knot");
        knot.put("definitions", new ListTag<StringTag, String>(TagType.STRING, 0));
        ListTag<FloatTag, Float> pos = new ListTag<>(TagType.FLOAT, 3);
        pos.add(new FloatTag(blockX + 0.5F));
        // The game sits the knot a quarter block above the fence base, not at the block centre.
        pos.add(new FloatTag(blockY + 0.25F));
        pos.add(new FloatTag(blockZ + 0.5F));
        knot.put("Pos", pos);
        ListTag<FloatTag, Float> motion = new ListTag<>(TagType.FLOAT, 3);
        motion.add(new FloatTag(0F));
        motion.add(new FloatTag(0F));
        motion.add(new FloatTag(0F));
        knot.put("Motion", motion);
        ListTag<FloatTag, Float> rotation = new ListTag<>(TagType.FLOAT, 2);
        rotation.add(new FloatTag(0F));
        rotation.add(new FloatTag(0F));
        knot.put("Rotation", rotation);
        knot.put("OnGround", (byte) 1);
        knot.put("Sitting", (byte) 0);
        knot.put("Tags", new ListTag<StringTag, String>(TagType.STRING, 0));
        knot.put("OwnerNew", -1L);
        knot.put("UniqueID", id);
        return knot;
    }

    /**
     * The unique id of the knot standing on a block.
     * <p>
     * Derived from the position rather than handed out by a counter: two animals tied to the same fence have to end up
     * pointing at one knot, and the two of them are converted by different workers.
     *
     * @param x the block the lead is tied to.
     * @param y the block the lead is tied to.
     * @param z the block the lead is tied to.
     * @return a stable id, never 0.
     */
    static long knotId(long x, long y, long z) {
        long id = mix(mix(x) ^ (mix(z) * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL));
        return id == 0L ? 1L : id;
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * @param entity a Java entity tag.
     * @return true when it is the knot of a lead tied to a fence.
     */
    static boolean isLeashKnot(CompoundTag entity) {
        return LEASH_KNOT.equals(entity.getString("id", null));
    }

    /**
     * The block a knot entity stands on.
     * <p>
     * Only worlds before 1.21 have knot entities at all. A knot is a block attached entity there, so the position is
     * spelled {@code TileX/TileY/TileZ}; the plain {@code Pos} is only a fallback.
     *
     * @param knot the Java leash knot entity.
     * @return the block coordinates, or null when the tag carries neither.
     */
    @Nullable
    static long[] position(CompoundTag knot) {
        Object x = number(knot, "TileX");
        Object y = number(knot, "TileY");
        Object z = number(knot, "TileZ");
        if (x != null && y != null && z != null) {
            return new long[]{((Number) x).longValue(), ((Number) y).longValue(), ((Number) z).longValue()};
        }
        List<Double> pos = knot.getListValues("Pos", DoubleTag.class, null);
        if (pos == null || pos.size() < 3) {
            return null;
        }
        return new long[]{
                (long) Math.floor(pos.get(0)),
                (long) Math.floor(pos.get(1)),
                (long) Math.floor(pos.get(2))
        };
    }

    @Nullable
    private static Object number(CompoundTag tag, String name) {
        return tag.contains(name) ? tag.get(name).getBoxedValue() : null;
    }

    /**
     * Read the leash of a Java entity.
     * <p>
     * Two shapes exist, and they mean different things:
     * <ul>
     *   <li>an int array of three numbers - the block the lead is tied to. 1.21 writes this for a fence and no longer
     *       spawns a knot entity at all, so nothing has to be looked up anywhere.</li>
     *   <li>a compound with a {@code UUID} - another entity holds the other end, either a mob, a player, or (before
     *       1.21) a knot entity standing on the fence.</li>
     * </ul>
     *
     * @param entity   the Java entity tag.
     * @param uniqueId the function that turns a Java UUID into the unique id the actor will be written with.
     * @return what the lead is attached to, or null when it is not leashed.
     */
    @Nullable
    static Leash leash(CompoundTag entity, UniqueIds uniqueId) {
        for (String field : LEASH_FIELDS) {
            // The tag has to be inspected, not read as the expected type: reading an int array that is actually a
            // compound throws instead of returning null.
            Tag<?> raw = entity.contains(field) ? entity.get(field) : null;
            if (raw instanceof IntArrayTag array) {
                int[] block = array.getValue();
                if (block != null && block.length >= 3) {
                    return Leash.block(block[0], block[1], block[2]);
                }
            } else if (raw instanceof CompoundTag holder) {
                long id = uniqueId.of(holder.getIntArray("UUID", null));
                if (id != 0L) {
                    return Leash.holder(id);
                }
            }
        }
        return null;
    }

    /**
     * What a lead is attached to: a block position, or another actor.
     */
    static final class Leash {
        private final boolean block;
        private final long id;
        private final long x;
        private final long y;
        private final long z;

        private Leash(boolean block, long id, long x, long y, long z) {
            this.block = block;
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        static Leash block(long x, long y, long z) {
            return new Leash(true, 0L, x, y, z);
        }

        static Leash holder(long id) {
            return new Leash(false, id, 0L, 0L, 0L);
        }

        /** @return true when the lead is tied to a block rather than to an actor. */
        boolean isBlock() {
            return block;
        }

        /** @return the unique id of the actor holding the other end, meaningless when {@link #isBlock()}. */
        long id() {
            return id;
        }

        long x() {
            return x;
        }

        long y() {
            return y;
        }

        long z() {
            return z;
        }
    }

    private static CompoundTag entry(long rideId, long vehicleId) {
        CompoundTag entry = new CompoundTag(2);
        entry.put("RideID", rideId);
        entry.put("VehicleID", vehicleId);
        return entry;
    }

    /**
     * Turns a Java UUID into the unique id an actor is written with, so that a reference can be resolved without a
     * lookup table - the two halves of a link are usually in different chunk files, and those are converted in
     * parallel.
     */
    interface UniqueIds {
        /**
         * @param uuid the four {@code int}s of a Java {@code UUID:[I;..]} tag, may be null.
         * @return a stable 64 bit id, or 0 when the entity has no UUID.
         */
        long of(int[] uuid);
    }
}
