package com.coordclipper.utils;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import meteordevelopment.meteorclient.commands.arguments.BlockPosArgumentType;
import meteordevelopment.meteorclient.mixin.ServerboundMovePlayerPacketAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft and Meteor APIs that changed between versions. Each compat folder has its own copy, this one is for
 * Minecraft 26.x.
 */
public final class Compat {
    private Compat() {}

    public static Vec3 pos(Entity entity) {
        return entity.position();
    }

    public static int selectedSlot(Player player) {
        return player.getInventory().getSelectedSlot();
    }

    /**
     * Where a server teleport puts the player, with relative coordinates resolved.
     */
    public static Vec3 teleportTarget(ClientboundPlayerPositionPacket packet, Entity player) {
        return PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(player), packet.change(), packet.relatives()).position();
    }

    /**
     * Whether {@code entity} blocks {@code other} from moving into it.
     */
    public static boolean collides(Entity entity, Entity other) {
        return entity.canBeCollidedWith(other);
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

    /**
     * Marks an outgoing movement packet as on the ground.
     */
    public static void setOnGround(ServerboundMovePlayerPacket packet) {
        ((ServerboundMovePlayerPacketAccessor) packet).meteor$setOnGround(true);
    }
}
