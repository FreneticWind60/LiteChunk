package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;

/**
 * Reads a Bedrock leash back into the Java form.
 * <p>
 * Bedrock keeps a plain {@code LeasherID} - the unique id of whoever holds the other end - on the leashed actor. Java
 * keeps a {@code leash} compound holding that entity's UUID; {@code Leash} was the spelling before 1.20.5.
 * <p>
 * The UUID is derived with the same hash {@link BedrockToJava} uses for every other actor, so a leash pointing at
 * another converted actor lands on exactly the UUID that actor was written with, and the lead attaches. A leash held
 * by a player is the one case that cannot work: the player's UUID is theirs, not something that can be derived from a
 * 64 bit id, so the lead simply does not appear.
 */
final class JavaEntityLinks {

    private JavaEntityLinks() {
    }

    /**
     * Apply the leash of the actor.
     *
     * @param javaEntity the Java entity tag being built, modified in place.
     * @param actor      the Bedrock actor tag that was read.
     * @param version    the Java version being written.
     */
    static void apply(CompoundTag javaEntity, CompoundTag actor, Version version) {
        long holder = longValue(actor, "LeasherID");
        // A Bedrock save always carries the key and writes -1 for "nothing on the other end", which is not a unique id
        // and must not be turned into a UUID - that would point the lead at an entity that does not exist.
        if (holder == 0L || holder == -1L) {
            return;
        }
        CompoundTag leash = new CompoundTag(1);
        leash.put("UUID", BedrockToJava.uuid(holder));
        javaEntity.put(version.isGreaterThanOrEqual(1, 20, 5) ? "leash" : "Leash", leash);
    }

    private static long longValue(CompoundTag tag, String name) {
        Object value = tag.contains(name) ? tag.get(name).getBoxedValue() : null;
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
