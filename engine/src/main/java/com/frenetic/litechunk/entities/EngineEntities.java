package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.column.entity.UnknownEntity;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Delegates the entity kinds the engine already converts properly to the engine's own resolvers.
 * <p>
 * Chunker has real handlers for paintings, item frames (normal and glowing) and cushions: they need a motive, a
 * hanging direction, the block they are attached to and, for paintings, a half-block offset that depends on the
 * picture's size. None of that is recoverable from the generic actor shape this package writes, so those kinds are
 * handed to the engine rather than being flattened into a shell - a painting with no {@code Motif} renders as the
 * wrong picture, which is worse than the six-key actor we would otherwise write.
 * <p>
 * The check is "did the Java resolver return something other than {@link UnknownEntity}", so if a future engine
 * version adds a handler for another entity type, it is picked up here without a change.
 */
final class EngineEntities {

    private EngineEntities() {
    }

    /**
     * Convert an entity through the engine's handlers.
     *
     * @param javaEntity the Java entity NBT.
     * @param source     resolvers of the Java version that was read, may be null.
     * @param target     resolvers of the Bedrock version being written, may be null.
     * @return the actor tag the engine produced, or null when the engine has no handler for this entity.
     */
    @Nullable
    static CompoundTag convert(CompoundTag javaEntity,
                               @Nullable JavaResolvers source,
                               @Nullable BedrockResolvers target) {
        if (source == null || target == null) {
            return null;
        }

        Optional<Entity> read;
        try {
            read = source.entityResolver().to(javaEntity);
        } catch (Exception failure) {
            return null;
        }
        if (read.isEmpty() || read.get() instanceof UnknownEntity) {
            return null;
        }

        try {
            return target.entityResolver().from(read.get()).orElse(null);
        } catch (Exception failure) {
            // A handler that cannot write this instance (for example a glow item frame into a version without one)
            // falls back to the generic actor.
            return null;
        }
    }
}
