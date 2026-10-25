package com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.resolver.entity.EmptyEntityHandler;
import com.hivemc.chunker.conversion.encoding.base.resolver.entity.EntityResolver;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity.handlers.BedrockCushionEntityHandler;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity.handlers.BedrockEntityHandler;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity.handlers.BedrockHangingEntityHandler;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity.handlers.BedrockItemFrameEntityHandler;
import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.entity.handlers.BedrockPaintingEntityHandler;
import com.hivemc.chunker.conversion.intermediate.column.entity.GlowItemFrameEntity;
import com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerEntityType;
import com.hivemc.chunker.conversion.intermediate.column.entity.type.ChunkerVanillaEntityType;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;

import java.util.List;
import java.util.Optional;

/**
 * Resolver for converting Bedrock entities between Chunker and NBT.
 */
public class BedrockEntityResolver extends EntityResolver<BedrockResolvers, CompoundTag> {
    /**
     * Create a new bedrock entity resolver.
     *
     * @param version   the bedrock version.
     * @param resolvers the resolvers to use.
     */
    public BedrockEntityResolver(Version version, BedrockResolvers resolvers) {
        super(version, resolvers, resolvers.converter().shouldPreserveUnknownEntities());
    }

    @Override
    protected void registerTypeHandlers(Version version) {
        // Handlers which write/read abstract types which others use
        register(new BedrockEntityHandler());
        register(new BedrockHangingEntityHandler());

        // Supported Entities (currently only paintings and item frames)
        register(new BedrockPaintingEntityHandler());
        register(new BedrockItemFrameEntityHandler());
        register(new EmptyEntityHandler<>(ChunkerVanillaEntityType.GLOW_ITEM_FRAME, GlowItemFrameEntity.class, () -> {
            throw new IllegalArgumentException("Glow item frames cannot be read from Bedrock.");
        }));

        if (version.isGreaterThanOrEqual(1, 26, 40)) {
            register(new BedrockCushionEntityHandler());
        }
    }

    @Override
    protected CompoundTag constructDataType(ChunkerEntityType type) {
        Optional<String> key = resolvers.entityTypeResolver().from(type);
        if (key.isEmpty()) return null; // Can't write the type

        // Create a new compoundTag with the ID
        CompoundTag compoundTag = new CompoundTag(4);
        compoundTag.put("identifier", key.get());

        // Bedrock has been data driven since 1.16.100: an actor's components come from the behaviour pack
        // definitions named by this list, not from its identifier. An actor written with only an identifier
        // therefore loads with no components at all (no physics, no collision, nothing to render) and the game
        // silently drops it -- which is exactly what "all mobs and paintings vanished" looked like.
        // "+<own identifier>" is the placeholder Bedrock itself uses for actors without component groups, and
        // it restores the vanilla default component set for that entity.
        compoundTag.put("definitions", ListTag.fromValues(TagType.STRING, List.of("+" + key.get())));
        return compoundTag;
    }

    @Override
    public Optional<ChunkerEntityType> getKey(CompoundTag input) {
        return input.getOptionalValue("identifier", String.class).flatMap(resolvers.entityTypeResolver()::to);
    }
}
