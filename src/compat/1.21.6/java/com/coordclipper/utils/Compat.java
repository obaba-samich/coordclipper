package com.coordclipper.utils;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Minecraft and Meteor APIs that changed between versions. Each compat folder has its own copy, this one is for
 * Minecraft 1.21.6 and 1.21.7.
 */
public final class Compat {
    private Compat() {}

    public static Vec3d pos(Entity entity) {
        return entity.getPos();
    }

    public static int selectedSlot(PlayerEntity player) {
        return player.getInventory().getSelectedSlot();
    }

    /**
     * Where a server teleport puts the player, with relative coordinates resolved.
     */
    public static Vec3d teleportTarget(PlayerPositionLookS2CPacket packet, Entity player) {
        return PlayerPosition.apply(PlayerPosition.fromEntity(player), packet.change(), packet.relatives()).position();
    }

    /**
     * Whether {@code entity} blocks {@code other} from moving into it.
     */
    public static boolean collides(Entity entity, Entity other) {
        return entity.isCollidable(other);
    }

    /**
     * Argument for a block position, absolute or relative to the player with ~.
     */
    public static ArgumentType<?> blockPosArgument() {
        return new CoordsArgument();
    }

    public static BlockPos getBlockPos(CommandContext<?> context, String name) {
        return context.getArgument(name, CoordsArgument.Coords.class).resolve();
    }
}
