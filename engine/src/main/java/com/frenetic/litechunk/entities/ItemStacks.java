package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Converts one item stack between the two formats by going through the engine's own item resolvers.
 * <p>
 * Why not write the item tag by hand: a Bedrock item is not just a name. The identifier differs from Java for a
 * number of items ({@code minecraft:cod} vs {@code minecraft:fish}), damage is folded into different places, block
 * items carry their block states, and enchantments/attributes move between {@code tag} and the components system
 * depending on the version. The engine already has that mapping for block entity inventories, and the optional entity
 * pass should use the same tables rather than a second, drifting copy.
 * <p>
 * The two resolvers come from the readers and writers of the conversion that just ran, so the item is translated by
 * exactly the version pair that produced the world.
 */
final class ItemStacks {

    private ItemStacks() {
    }

    /**
     * Convert a Java item stack tag to the Bedrock form.
     *
     * @param javaItem the Java item tag ({@code {id, Count, tag}} or {@code {id, count, components}}).
     * @param source   resolvers of the Java version that was read, may be null when unknown.
     * @param target   resolvers of the Bedrock version being written, may be null when unknown.
     * @return the Bedrock item tag, or null when the item cannot be represented (unknown item, empty stack).
     */
    @Nullable
    static CompoundTag convert(@Nullable CompoundTag javaItem,
                               @Nullable JavaResolvers source,
                               @Nullable BedrockResolvers target) {
        if (javaItem == null || source == null || target == null) {
            return null;
        }

        ChunkerItemStack stack;
        try {
            stack = source.readItem(javaItem);
        } catch (Exception failure) {
            return null;
        }
        if (stack == null) {
            return null;
        }

        // Read failures come back as air rather than an exception, and air is not something an item actor can hold.
        if (stack.getIdentifier() instanceof ChunkerBlockIdentifier block && block.isAir()) {
            return null;
        }

        try {
            return target.writeItem(stack).orElse(null);
        } catch (Exception failure) {
            return null;
        }
    }
}
