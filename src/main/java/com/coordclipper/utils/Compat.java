package com.coordclipper.utils;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

/**
 * Minecraft APIs that changed names between versions. Each version branch only has to change this file.
 */
public final class Compat {
    private Compat() {}

    public static Vec3d pos(Entity entity) {
        return entity.getEntityPos();
    }

    public static int selectedSlot(PlayerEntity player) {
        return player.getInventory().getSelectedSlot();
    }

    /**
     * Where a server teleport puts the player, with relative coordinates resolved.
     */
    public static Vec3d teleportTarget(PlayerPositionLookS2CPacket packet, Entity player) {
        return EntityPosition.apply(EntityPosition.fromEntity(player), packet.change(), packet.relatives()).position();
    }
}
