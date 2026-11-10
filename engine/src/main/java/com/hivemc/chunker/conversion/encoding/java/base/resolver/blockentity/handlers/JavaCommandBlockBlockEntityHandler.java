package com.hivemc.chunker.conversion.encoding.java.base.resolver.blockentity.handlers;

import com.frenetic.litechunk.commands.ParticleCommand;
import com.hivemc.chunker.conversion.encoding.base.resolver.blockentity.BlockEntityHandler;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.JavaResolvers;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.CommandBlockEntity;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.util.JsonTextUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Handler for Command Block Entities.
 */
public class JavaCommandBlockBlockEntityHandler extends BlockEntityHandler<JavaResolvers, CompoundTag, CommandBlockEntity> {
    public JavaCommandBlockBlockEntityHandler() {
        super("minecraft:command_block", CommandBlockEntity.class, CommandBlockEntity::new);
    }

    @Override
    public void read(@NotNull JavaResolvers resolvers, @NotNull CompoundTag input, @NotNull CommandBlockEntity value) {
        value.setCommand(input.getString("Command", ""));
        value.setAuto(input.getByte("auto", (byte) 0) == (byte) 1);
        value.setTrackOutput(input.getByte("TrackOutput", (byte) 1) == (byte) 1);

        value.setCustomName(input.getOptional("CustomName", Tag.class)
                .map(JsonTextUtil::fromNBT)
                .orElse(null));
    }

    @Override
    public void write(@NotNull JavaResolvers resolvers, @NotNull CompoundTag output, @NotNull CommandBlockEntity value) {
        // Bedrock's /particle takes a name and a position; Java expects a delta, a speed and a count as well, so a
        // command copied across verbatim is reported as malformed and never spawns anything.
        String translated = ParticleCommand.toJava(value.getCommand());
        output.put("Command", translated != null ? translated : value.getCommand());
        output.put("auto", value.isAuto() ? (byte) 1 : (byte) 0);
        output.put("TrackOutput", value.isTrackOutput() ? (byte) 1 : (byte) 0);

        if (value.getCustomName() != null) {
            output.put("CustomName", JsonTextUtil.toNBT(value.getCustomName(), resolvers.dataVersion()));
        }
    }
}
