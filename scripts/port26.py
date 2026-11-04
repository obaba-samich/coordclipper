"""
Generates the 26.x sources (Mojang names) from the 1.21.x ones (Yarn names): python3 scripts/port26.py
Make changes in src/mc1.21 and run this, don't edit src/mc26 by hand. src/compat is not touched.
"""
import re, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
FILES = ['CoordClipper.java', 'commands/CoordClipCommand.java', 'modules/CoordClipSettings.java']
imports = {
 'net.minecraft.block.BlockState':'net.minecraft.world.level.block.state.BlockState',
 'net.minecraft.block.Blocks':'net.minecraft.world.level.block.Blocks',
 'net.minecraft.block.DoorBlock':'net.minecraft.world.level.block.DoorBlock',
 'net.minecraft.client.network.PlayerListEntry':'net.minecraft.client.multiplayer.PlayerInfo',
 'net.minecraft.command.CommandSource':'net.minecraft.client.multiplayer.ClientSuggestionProvider',
 'net.minecraft.component.DataComponentTypes':'net.minecraft.core.component.DataComponents',
 'net.minecraft.entity.Entity':'net.minecraft.world.entity.Entity',
 'net.minecraft.entity.EntityPosition':'net.minecraft.world.entity.PositionMoveRotation',
 'net.minecraft.entity.player.PlayerEntity':'net.minecraft.world.entity.player.Player',
 'net.minecraft.fluid.FluidState':'net.minecraft.world.level.material.FluidState',
 'net.minecraft.item.BlockItem':'net.minecraft.world.item.BlockItem',
 'net.minecraft.item.ItemStack':'net.minecraft.world.item.ItemStack',
 'net.minecraft.item.Item':'net.minecraft.world.item.Item',
 'net.minecraft.item.Items':'net.minecraft.world.item.Items',
 'net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket':'net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket',
 'net.minecraft.registry.tag.FluidTags':'net.minecraft.tags.FluidTags',
 'net.minecraft.util.math.BlockPos':'net.minecraft.core.BlockPos',
 'net.minecraft.util.math.Box':'net.minecraft.world.phys.AABB',
 'net.minecraft.util.math.Direction':'net.minecraft.core.Direction',
 'net.minecraft.util.math.MathHelper':'net.minecraft.util.Mth',
 'net.minecraft.util.math.Vec3d':'net.minecraft.world.phys.Vec3',
 'net.minecraft.util.shape.VoxelShape':'net.minecraft.world.phys.shapes.VoxelShape',
 'net.minecraft.world.Heightmap':'net.minecraft.world.level.levelgen.Heightmap',
}
def port(s):
    for a, b in imports.items():
        s = re.sub(r'import ' + re.escape(a) + ';', 'import ' + b + ';', s)
    # Pose for standing eye height
    if 'getStandingEyeHeight' in s:
        s = s.replace('import net.minecraft.world.entity.Entity;', 'import net.minecraft.world.entity.Entity;\nimport net.minecraft.world.entity.Pose;')
    words = [
     ('PlayerListEntry','PlayerInfo'),('CommandSource','ClientSuggestionProvider'),('DataComponentTypes','DataComponents'),
     ('PlayerPositionLookS2CPacket','ClientboundPlayerPositionPacket'),('EntityPosition','PositionMoveRotation'),
     ('PlayerEntity','Player'),('Box','AABB'),('MathHelper','Mth'),('Vec3d','Vec3'),('BlockPos.Mutable','BlockPos.MutableBlockPos'),
     ('Heightmap.Type','Heightmap.Types'),
    ]
    for a, b in words:
        s = re.sub(r'(?<![\w.])' + re.escape(a) + r'(?!\w)', b, s)
    subs = [
     (r'\bmc\.world\b','mc.level'),
     (r'\.getChunkManager\(\)\.isChunkLoaded\(','.getChunkSource().hasChunk('),
     (r'\.getBottomY\(\)','.getMinY()'),
     (r'\.isOutOfHeightLimit\(','.isOutsideBuildHeight('),
     (r'\.getStatesInBox\(','.getBlockStates('),
     (r'\.isSpaceEmpty\(','.noCollision('),
     (r'\.getOtherEntities\(','.getEntities('),
     (r'mc\.level\.getTopY\(','mc.level.getHeight('),
     (r'mc\.getNetworkHandler\(\)\.getPlayerListEntry\(mc\.player\.getUuid\(\)\)','mc.getConnection().getPlayerInfo(mc.player.getUUID())'),
     (r'\.hasVehicle\(\)','.isPassenger()'),
     (r'\.isDead\(\)','.isDeadOrDying()'),
     (r'\.setPosition\(','.setPos('),
     (r'\.setVelocity\(','.setDeltaMovement('),
     (r'\.getMaxAir\(\)','.getMaxAirSupply()'),
     (r'\.getAir\(\)','.getAirSupply()'),
     (r'\.isSubmergedInWater\(\)','.isUnderWater()'),
     (r'\.creativeMode\b','.instabuild'),
     (r'\.getHungerManager\(\)','.getFoodData()'),
     (r'\.getStandingEyeHeight\(\)','.getEyeHeight(Pose.STANDING)'),
     (r'\.getInventory\(\)\.getStack\(','.getInventory().getItem('),
     (r'\.useKey\.setPressed\(','.keyUse.setDown('),
     (r'\.isCollidable\(','.canBeCollidedWith('),
     (r'Entity\.adjustMovementForCollisions\(','Entity.collideBoundingBox('),
     (r'BlockPos\.ofFloored\(','BlockPos.containing('),
     (r'BlockPos\.iterate\(','BlockPos.betweenClosed('),
     (r'\.getSquaredDistance\(','.distToCenterSqr('),
     (r'\.squaredDistanceTo\(','.distanceToSqr('),
     (r'Vec3\.ofBottomCenter\(','Vec3.atBottomCenterOf('),
     (r'\.down\(\)','.below()'),
     (r'\.up\(\)','.above()'),
     (r'\.isOf\(','.is('),
     (r'\.isIn\(FluidTags','.is(FluidTags'),
     (r'\.isReplaceable\(\)','.canBeReplaced()'),
     (r'\.isSideSolidFullSquare\(','.isFaceSturdy('),
     (r'\.getBlock\(\)\.getName\(\)','.getBlock().getName()'),
     (r'\.getMax\(Direction','.max(Direction'),
     (r'\.getBoundingBoxes\(\)','.toAabbs()'),
     (r'\.union\(','.minmax('),
     (r'(\bshape|\bbox|\bbase|getBoundingBox\(\))\.offset\(', r'\1.move('),
 (r'(hitboxAt\([^)]*\))\.offset\(', r'\1.move('),
     (r'\.getComponents\(\)\.get\(','.components().get('),
    ]
    for a, b in subs:
        s = re.sub(a, b, s)
    return s

for name in FILES:
    src = ROOT / 'src/mc1.21/java/com/coordclipper' / name
    dst = ROOT / 'src/mc26/java/com/coordclipper' / name
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(port(src.read_text()))
    print('wrote', dst.relative_to(ROOT))
