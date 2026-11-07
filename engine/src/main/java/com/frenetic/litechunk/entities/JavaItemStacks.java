package com.frenetic.litechunk.entities;

import com.hivemc.chunker.conversion.encoding.bedrock.base.resolver.BedrockResolvers;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.itemstack.ChunkerItemStack;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
 * Converts one item stack from a Bedrock actor to the Java form.
 * <p>
 * This is {@link ItemStacks} in the other direction: both go through the engine's own item resolvers rather than a
 * hand written table, so a saddle, a horse armour or a piglin's crossbow comes out with the same name the block
 * entity inventories used. The two resolvers come from the reader and writer of the conversion that is running.
 */
final class JavaItemStacks {

    private JavaItemStacks() {
    }

    /**
     * Convert a Bedrock item stack tag to the Java form.
     *
     * @param bedrockItem the Bedrock item tag ({@code {Name, Count, Damage, tag}}).
     * @param source      resolvers of the Bedrock version that was read, may be null when unknown.
     * @param target      resolvers of the Java version being written, may be null when unknown.
     * @return the Java item tag, or null when the item cannot be represented (unknown item, empty slot).
     */
    @Nullable
    static CompoundTag convert(@Nullable CompoundTag bedrockItem,
                               @Nullable BedrockResolvers source,
                               @Nullable JavaResolvers target) {
        if (bedrockItem == null || source == null || target == null) {
            return null;
        }

        if (bedrockItem.size() == 0) {
            return null;
        }

        // A real Bedrock save pads empty equipment slots with {Name:"",Count:0b,Damage:0s}, while container entries
        // use {Block:{name,...}} instead of a Name at all. Both are left to the item resolver below, which reads a
        // nameless stack as air - checking for Name here would throw every container item away.
        String name = bedrockItem.getString("Name", null);
        if (name != null && name.isEmpty()) {
            return null;
        }

        ChunkerItemStack stack;
        try {
            stack = source.readItem(bedrockItem);
        } catch (Exception failure) {
            return null;
        }
        if (stack == null) {
            return null;
        }

        if (stack.getIdentifier() instanceof ChunkerBlockIdentifier block && block.isAir()) {
            return null;
        }

        try {
            return target.writeItem(stack).orElse(null);
        } catch (Exception failure) {
            return null;
        }
    }

    /**
     * @param identifier a Bedrock item identifier.
     * @return the Bedrock tag for a single plain item, which is what the equipment lists hold.
     */
    static CompoundTag plain(String identifier) {
        CompoundTag item = new CompoundTag(3);
        item.put("Name", identifier);
        item.put("Count", (byte) 1);
        item.put("Damage", (short) 0);
        return item;
    }
}
