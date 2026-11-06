package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;

import java.util.List;
import java.util.Map;

/**
 * The connections between two actors: who rides what, and who is holding whose leash.
 * <p>
 * Java nests the rider inside the vehicle's {@code Passengers} list, so a pig sitting in a boat is not a separate
 * entity at all - it exists only as a compound inside the boat. Bedrock does the opposite: every actor stands on its
 * own and the relationship is a pair of {@code LinksTag} entries, one on each side.
 * <p>
 * <strong>Two things about this are inferences, not measurements.</strong> A real Bedrock save with a rider was not
 * available, so the shape of {@code LinksTag} ({@code RideID} on the passenger pointing at the vehicle,
 * {@code VehicleID} on the vehicle pointing at each passenger) is taken from the community documentation of the
 * actor format rather than read back from a save, and the same goes for {@code LeasherID} carrying the holder's unique
 * id. The failure mode if it is wrong is a pig that stands next to the boat instead of in it - the entities are still
 * converted, only the attachment is lost - which is why it ships before it can be confirmed.
 * <p>
 * What <em>is</em> measured: Java 1.21.8 keeps a leash as {@code leash:{UUID:[I;..]}} (lower case, compound) and drops
 * every other spelling, and it keeps {@code Passengers} nested in full. Both were confirmed by summoning into a 1.21.8
 * world and reading back what the game preserved.
 */
final class EntityLinks {

    /** The field Java uses for a leash since 1.20.5; the capitalised form is the older one. */
    private static final String[] LEASH_FIELDS = {"leash", "Leash"};

    private EntityLinks() {
    }

    /**
     * Mark this actor as riding another.
     *
     * @param rider     the actor tag, modified in place.
     * @param vehicleId the unique id of what it rides.
     */
    static void riding(CompoundTag rider, long vehicleId) {
        ListTag<CompoundTag, Map<String, Tag<?>>> links = new ListTag<>(TagType.COMPOUND, 1);
        links.add(entry(vehicleId, 0L));
        rider.put("LinksTag", links);
    }

    /**
     * Mark this actor as carrying the given actors.
     *
     * @param vehicle  the actor tag, modified in place.
     * @param riderIds the unique ids of the actors on top of it.
     */
    static void carried(CompoundTag vehicle, List<Long> riderIds) {
        if (riderIds.isEmpty()) {
            return;
        }
        ListTag<CompoundTag, Map<String, Tag<?>>> links = new ListTag<>(TagType.COMPOUND, riderIds.size());
        for (long riderId : riderIds) {
            links.add(entry(0L, riderId));
        }
        vehicle.put("LinksTag", links);
    }

    /**
     * Mark this actor as leashed to another.
     *
     * @param actor    the actor tag, modified in place.
     * @param holderId the unique id of the leash holder.
     */
    static void leashed(CompoundTag actor, long holderId) {
        actor.put("LeasherID", holderId);
    }

    /**
     * @param entity    the Java entity tag.
     * @param uniqueId  the function that turns a Java UUID into the unique id the actor will be written with.
     * @return the unique id of the entity holding this one's leash, or 0 when it is not leashed.
     */
    static long leashHolder(CompoundTag entity, UniqueIds uniqueId) {
        for (String field : LEASH_FIELDS) {
            CompoundTag leash = entity.getCompound(field, null);
            if (leash == null) {
                continue;
            }
            long holder = uniqueId.of(leash.getIntArray("UUID", null));
            if (holder != 0L) {
                return holder;
            }
        }
        return 0L;
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
