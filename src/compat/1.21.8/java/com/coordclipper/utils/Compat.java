package com.coordclipper.utils;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import meteordevelopment.meteorclient.commands.arguments.BlockPosArgumentType;
import meteordevelopment.meteorclient.mixin.ClientPlayerEntityAccessor;
import meteordevelopment.meteorclient.mixin.PlayerMoveC2SPacketAccessor;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Minecraft and Meteor APIs that changed between versions. Each compat folder has its own copy, this one is for
 * Minecraft 1.21.8.
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
        return BlockPosArgumentType.blockPos();
    }

    public static BlockPos getBlockPos(CommandContext<?> context, String name) {
        return BlockPosArgumentType.getBlockPos(context, name);
    }

    public static void setOnGround(PlayerMoveC2SPacket packet, boolean onGround) {
        ((PlayerMoveC2SPacketAccessor) packet).meteor$setOnGround(onGround);
    }

    public static void setY(PlayerMoveC2SPacket packet, double y) {
        ((PlayerMoveC2SPacketAccessor) packet).meteor$setY(y);
    }

    /**
     * Makes the game send the player's position with the next movement packet even if it hasn't changed.
     */
    public static void forcePositionPacket(ClientPlayerEntity player) {
        ((ClientPlayerEntityAccessor) player).meteor$setTicksSinceLastPositionPacketSent(20);
    }
}
