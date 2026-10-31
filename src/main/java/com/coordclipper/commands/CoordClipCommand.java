package com.coordclipper.commands;

import com.coordclipper.CoordClipper;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.arguments.BlockPosArgumentType;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.fluid.FluidState;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

/**
 * Clips to a target position through a chain of moves from solid ground to solid ground, ending on the nearest solid
 * ground to the target.
 * <p>
 * Moves work like Meteor's .hclip: the player is only moved client side, and the game's own movement packet for that
 * tick carries the move, so the server never sees more than one movement packet per tick. Server side, a movement
 * packet may move at most 10 blocks, and the server replays it with collisions: vertically first (a vclip), then
 * horizontally (an hclip). The vertical difference is ignored, so vclips go through floors and ceilings, but the
 * horizontal part must not run into anything or the server puts the player back.
 * <p>
 * Routes are found with A* over ground spots in loaded chunks. Only the chunk the next move lands in needs to be
 * loaded: while the target is unloaded it heads towards it as far as the loaded chunks reach, then searches again.
 * Close to the landing spot it searches every route, including short climbs through the air (straight up or down
 * moves are always accepted), so walls and buildings taller than one move can be climbed.
 * <p>
 * Moves go out one every other tick without stopping: the next part of the route is planned from the end of the current
 * one while it's walked, and grows as more chunks load. Setbacks are caught as they arrive and matched to the move the
 * server rejected; it then holds still where the server put it and plans again from there. Setbacks and other server
 * problems are explained in detail in the game log.
 */
public class CoordClipCommand extends Command {
    // Vanilla rejects moves over 10 blocks per movement packet
    private static final double SERVER_MAX_MOVE = 10;
    private static final double MAX_HOP = 9.5;
    private static final double MAX_HOP_SQ = MAX_HOP * MAX_HOP;
    // Moves are checked again right before they're sent, from wherever the player actually is
    private static final double MAX_HOP_SENT = 9.9;
    private static final int REACH = (int) MAX_HOP;
    // Vanilla puts you back if the horizontal part of a move ends more than 0.25 blocks from where you said
    private static final double MAX_HORIZONTAL_ERROR_SQ = 0.03;

    private static final int TARGET_SEARCH_RADIUS = 100;
    private static final double MIN_PROGRESS = 1;

    // A* limits. Far away it's weighted towards the target and gives up early, close to the landing spot it's exhaustive
    private static final double HEURISTIC_WEIGHT = 2;
    private static final int MAX_EXPANSIONS = 3000;
    private static final int MAX_STALE_EXPANSIONS = 50;
    private static final double EXHAUSTIVE_RANGE = 64;
    private static final int MAX_EXHAUSTIVE_EXPANSIONS = 20000;
    private static final long IDLE_SEARCH_NANOS = 15_000_000;
    private static final long MOVING_SEARCH_NANOS = 10_000_000;
    // The server kicks you for floating after 80 ticks, keep climbs through the air well under that
    private static final int MAX_AIR_STOPS = 10;

    private static final int RETRY_DELAY = 10;
    private static final int SEARCH_RETRY = 5;
    private static final int MAX_SETBACKS = 5;
    private static final int STALL_TIMEOUT = 20 * 30;
    // A server teleport further than this from both ends of the last move wasn't a setback
    private static final double TELEPORTED_AWAY = 16;

    // One move per tick, like the game's own movement packets
    private static final int MOVE_INTERVAL = 1;
    // While the target is unloaded it aims for the farthest loaded point on the straight line to it, and every block
    // off that line counts as this many blocks further away, so it doesn't zigzag towards whichever chunks the server
    // happened to send first
    private static final double LINE_DEVIATION_WEIGHT = 2;
    private static final double LINE_SCAN_STEP = 4;
    // Don't bother planning until at least this much more of the line is loaded
    private static final double MIN_SEGMENT = 16;
    // Standable spots are remembered between searches for this long
    private static final int SPOT_CACHE_TICKS = 100;

    private record Move(int number, Vec3d from, Vec3d to, long time) {}

    // Planned moves not sent yet. Searches extend it from its end while it's being walked
    private final Deque<Vec3d> hops = new ArrayDeque<>();
    // Sent moves recent enough that the server could still put us back for them
    private final Deque<Move> pending = new ArrayDeque<>();
    private final List<String> setbackCauses = new ArrayList<>();
    private Search search;
    // Whether each block position is a spot to stand on, shared between searches
    private final Long2BooleanOpenHashMap spotCache = new Long2BooleanOpenHashMap();
    private int spotCacheAge;
    private BlockPos goal;
    private Vec3d landing, last;
    // Where the clip started, the straight line from here to the target is the line it tries to stay on
    private Vec3d origin;
    private boolean arriving;
    // Bumped whenever the planned route is thrown away, so searches planned from its old end get discarded
    private int planVersion;
    private int timer, searchCooldown, stallTicks, setbacks, moves, totalSetbacks;
    private long startTime;
    // Where the server teleported us, set from the network thread
    private volatile Vec3d serverTeleport;

    public CoordClipCommand() {
        super("coordclip", "Pathfinds to the given coordinates with hclips and vclips and lands on the nearest solid ground.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("stop").executes(context -> {
            if (goal == null) error("Not currently clipping.");
            else {
                stop();
                info("Stopped.");
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(argument("pos", BlockPosArgumentType.blockPos()).executes(context -> {
            if (mc.player.hasVehicle()) {
                error("Dismount first.");
                return SINGLE_SUCCESS;
            }

            stop();
            goal = BlockPosArgumentType.getBlockPos(context, "pos");
            landing = last = null;
            serverTeleport = null;
            arriving = false;
            timer = searchCooldown = stallTicks = setbacks = moves = totalSetbacks = 0;
            pending.clear();
            setbackCauses.clear();
            spotCache.clear();
            startTime = System.currentTimeMillis();
            origin = mc.player.getEntityPos();
            MeteorClient.EVENT_BUS.subscribe(this);

            double distance = Math.sqrt(goal.getSquaredDistance(mc.player.getEntityPos()));
            info("Clipping to (highlight)%d %d %d(default), (highlight)%.0f(default) blocks away.", goal.getX(), goal.getY(), goal.getZ(), distance);
            return SINGLE_SUCCESS;
        }));
    }

    // Execution

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player.isDead()) {
            failLogged("You died, stopped.", "Player died after %d moves, stopped.", moves);
            return;
        }

        Vec3d teleport = serverTeleport;
        if (teleport != null) {
            serverTeleport = null;
            onServerTeleport(teleport);
            return;
        }

        prunePending();

        if (++spotCacheAge > SPOT_CACHE_TICKS) {
            spotCache.clear();
            spotCacheAge = 0;
        }

        // Hold still at the last stop, so we don't fall out of the air or sink in water between moves
        if (last != null) {
            mc.player.setPosition(last);
            mc.player.setVelocity(Vec3d.ZERO);
        }

        // Plan ahead while moving, so there's no pause to think at the end of each route
        advanceSearch();
        if (goal == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        // The server had a full round trip to put us back for the final move and didn't
        if (arriving) {
            arrive();
            return;
        }

        if (hops.isEmpty()) return;

        // Next move of the route, if it's still valid from where we actually are
        Vec3d next = hops.poll();
        Vec3d pos = mc.player.getEntityPos();
        if (checkMove(pos, next)) move(pos, next);
        else discardRoute();
    }

    private void advanceSearch() {
        // Nothing left to plan once the route reaches the landing spot
        if (arriving || (!hops.isEmpty() && hops.peekLast().equals(landing))) return;

        if (searchCooldown > 0) {
            searchCooldown--;
            return;
        }

        if (search == null) {
            search = newSearch(planEnd(), false);
            if (search == null) return;
        }

        // Smaller budget while moving, it only has to stay ahead of the route
        if (!search.run(hops.isEmpty() ? IDLE_SEARCH_NANOS : MOVING_SEARCH_NANOS)) return;
        onSearchDone(search);
    }

    /**
     * Where the next search starts: the end of the planned route, or where we're standing.
     */
    private Vec3d planEnd() {
        if (!hops.isEmpty()) return hops.peekLast();
        return last != null ? last : mc.player.getEntityPos();
    }

    private void onSearchDone(Search done) {
        search = null;

        // The route changed while searching, start over from its new end
        if (done.version != planVersion) return;

        if (!done.result.isEmpty()) {
            stallTicks = 0;
            hops.addAll(done.result);
            return;
        }

        if (done.target != null) {
            // The quick search found nothing, try every route before giving up
            if (!done.exhaustive) {
                search = newSearch(done.start.vec, true);
                return;
            }

            // Only give up once the rest of the route has been walked and it still can't get closer
            if (!hops.isEmpty() || !pending.isEmpty()) searchCooldown = SEARCH_RETRY;
            else fail(String.format(Locale.ROOT, "Can't get any closer, stopped (highlight)%.0f(default) blocks from the landing spot.", mc.player.getEntityPos().distanceTo(landing)));
            return;
        }

        // Target still unloaded and nothing loaded gets us closer yet, try again as chunks load
        if (hops.isEmpty()) {
            stallTicks += SEARCH_RETRY + 1;
            if (stallTicks > STALL_TIMEOUT) {
                fail(String.format(Locale.ROOT, "Couldn't get closer for %d seconds, giving up.", STALL_TIMEOUT / 20));
                return;
            }
        }
        searchCooldown = SEARCH_RETRY;
    }

    private void discardRoute() {
        hops.clear();
        search = null;
        searchCooldown = 0;
        planVersion++;
    }

    private void arrive() {
        long seconds = (System.currentTimeMillis() - startTime) / 1000;
        stop();
        info("Arrived in (highlight)%d(default) moves, (highlight)%ds(default).", moves, seconds);
    }

    /**
     * Forgets moves the server had a full round trip to object to. Once a move is confirmed, earlier setbacks are no
     * longer in a row.
     */
    private void prunePending() {
        long cutoff = System.currentTimeMillis() - Math.max(1000, 3L * ping() + 250);

        while (!pending.isEmpty() && pending.peekFirst().time < cutoff) {
            pending.pollFirst();
            setbacks = 0;
        }
    }

    /**
     * Sorts out what a server teleport means: a resync at a move's destination is harmless, one back to where a recent
     * move started is a setback, and one somewhere else entirely (a plugin, /tp, a portal) stops the clip.
     */
    private void onServerTeleport(Vec3d pos) {
        if (pending.isEmpty()) {
            // Servers resend the teleport until it's confirmed, so this is usually an already handled setback
            if (last == null || pos.distanceTo(last) < 0.5) {
                log("Server teleported you to %s with no move waiting for confirmation, ignoring.", fmt(pos));
                return;
            }

            CoordClipper.LOG.warn("[CoordClip] Server put you back to {} ({} blocks from where you were) long after your last move; the setback was delayed or came from a plugin.",
                fmt(pos), fmt(pos.distanceTo(last)));
            holdAndRetry(pos);
            warning("Server put you back (delayed), retrying.");
            return;
        }

        for (Move move : pending) {
            if (pos.distanceTo(move.to) < 0.5) {
                log("Server synced your position at the destination of move #%d (%s), continuing.", move.number, fmt(pos));
                return;
            }
        }

        // The rejected move is the one that started where the server put us
        Move rejected = pending.peekFirst();
        for (Move move : pending) {
            if (pos.distanceTo(move.from) < pos.distanceTo(rejected.from)) rejected = move;
        }

        Move latest = pending.peekLast();
        if (pos.distanceTo(rejected.from) > TELEPORTED_AWAY && pos.distanceTo(latest.to) > TELEPORTED_AWAY) {
            failLogged(String.format(Locale.ROOT, "The server teleported you away (%.0f blocks), stopped. Details in the log.", pos.distanceTo(latest.to)),
                "Server teleported you to %s, %.1f blocks from the last move #%d (%s -> %s). That's not a setback; it was probably a plugin, /tp, a portal or a respawn.",
                fmt(pos), pos.distanceTo(latest.to), latest.number, fmt(latest.from), fmt(latest.to));
            return;
        }

        onSetBack(rejected, latest.number - rejected.number, pos);
    }

    private void onSetBack(Move move, int ignored, Vec3d pos) {
        totalSetbacks++;

        List<String> causes = diagnose(move, pos);
        String summary = String.join("; ", causes);
        setbackCauses.add("#" + move.number + ": " + summary);

        CoordClipper.LOG.warn("[CoordClip] Server put you back after move #{}{}.\n  Move: {} -> {} ({} blocks, {} horizontal, {} vertical)\n  Put back to: {} ({} blocks from the start of the move)\n  {} ms after the move, ping {} ms, server {} TPS, last server tick {}s ago\n  Likely cause: {}",
            move.number, ignored > 0 ? " (the server ignored the " + ignored + " moves sent after it)" : "",
            fmt(move.from), fmt(move.to), fmt(move.from.distanceTo(move.to)), fmt(horizontalDistance(move.from, move.to)), fmt(move.to.y - move.from.y),
            fmt(pos), fmt(pos.distanceTo(move.from)),
            System.currentTimeMillis() - move.time, ping(), fmt(TickRate.INSTANCE.getTickRate()), fmt(TickRate.INSTANCE.getTimeSinceLastTick()),
            summary);

        holdAndRetry(pos);

        if (++setbacks >= MAX_SETBACKS) {
            failLogged(String.format(Locale.ROOT, "Server put you back %d times in a row, giving up. Last cause: %s", MAX_SETBACKS, causes.getFirst()),
                "Giving up after %d setbacks in a row. Causes:\n  %s", MAX_SETBACKS, String.join("\n  ", setbackCauses.subList(Math.max(0, setbackCauses.size() - MAX_SETBACKS), setbackCauses.size())));
            return;
        }

        warning("Server put you back (%d/%d): %s", setbacks, MAX_SETBACKS, causes.getFirst());
    }

    /**
     * Throws the route away and holds still where the server has us, then plans again from there after a short delay.
     */
    private void holdAndRetry(Vec3d pos) {
        discardRoute();
        pending.clear();
        arriving = false;
        last = pos;
        timer = searchCooldown = RETRY_DELAY;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket packet) {
            serverTeleport = EntityPosition.apply(EntityPosition.fromEntity(mc.player), packet.change(), packet.relatives()).position();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        log("Disconnected after %d moves, stopped.", moves);
        stop();
    }

    private void stop() {
        hops.clear();
        pending.clear();
        search = null;
        goal = null;
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    private void fail(String chat) {
        stop();
        // Meteor formats chat messages again
        error(chat.replace("%", "%%"));
    }

    private void failLogged(String chat, String details, Object... args) {
        CoordClipper.LOG.warn("[CoordClip] {}", String.format(Locale.ROOT, details, args));
        fail(chat);
    }

    /**
     * Same as Meteor's .hclip: only moves the player client side, the game sends the movement packet this tick.
     * Moves go out one every {@link #MOVE_INTERVAL} ticks without waiting; setbacks are matched to them as they arrive.
     */
    private void move(Vec3d from, Vec3d to) {
        mc.player.setPosition(to);
        mc.player.setVelocity(Vec3d.ZERO);

        last = to;
        pending.addLast(new Move(++moves, from, to, System.currentTimeMillis()));

        // Give the server a round trip to object to the final move before calling it done
        if (to.equals(landing) && hops.isEmpty()) {
            arriving = true;
            timer = checkTicks();
        }
        else timer = MOVE_INTERVAL - 1;
    }

    /**
     * Ticks to wait for a setback after the final move: one round trip plus a bit.
     */
    private int checkTicks() {
        return MathHelper.clamp(MathHelper.ceil(ping() / 50.0) + 2, 3, 30);
    }

    private int ping() {
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 100 : entry.getLatency();
    }

    private boolean checkMove(Vec3d from, Vec3d to) {
        return from.distanceTo(to) <= MAX_HOP_SENT && canStop(to) && canMove(from, to);
    }

    // Diagnostics

    /**
     * Best guesses for why the server put us back, most likely first. Mirrors the server's movement checks.
     */
    private List<String> diagnose(Move move, Vec3d serverPos) {
        List<String> causes = new ArrayList<>();
        double distance = move.from.distanceTo(move.to);

        if (distance > SERVER_MAX_MOVE) {
            causes.add(String.format(Locale.ROOT, "the move was %.2f blocks, over the server's %.0f block limit per movement packet", distance, SERVER_MAX_MOVE));
        }
        if (!canMove(move.from, move.to)) {
            causes.add("the horizontal part of the move is blocked by " + describeBlocker(move.from, move.to) + " (the world changed, or the client's copy of it is out of date)");
        }
        if (!fits(move.to, true)) {
            causes.add("the destination is blocked by " + describeObstruction(move.to));
        }

        float tps = TickRate.INSTANCE.getTickRate();
        if (tps < 18) {
            causes.add(String.format(Locale.ROOT, "the server is lagging (%.1f TPS), so it may have counted several moves in one tick and seen them as too fast", tps));
        }
        float sinceTick = TickRate.INSTANCE.getTimeSinceLastTick();
        if (sinceTick > 1) {
            causes.add(String.format(Locale.ROOT, "the server froze (no tick for %.1fs) and caught up on several moves at once", sinceTick));
        }
        if (serverPos.distanceTo(move.from) > 0.5) {
            causes.add(String.format(Locale.ROOT, "the server put you %.1f blocks from where the move started, so it already disagreed about your position (an earlier move may have been rejected without telling the client)", serverPos.distanceTo(move.from)));
        }

        if (causes.isEmpty()) {
            causes.add("the move passes all vanilla checks client side, so it was most likely rejected by a plugin or anticheat, a network hiccup delivered two moves in the same server tick, or the block layout differs server side");
        }

        return causes;
    }

    /**
     * What's in the way of a move's horizontal part, which the server replays at the height the vertical part ended.
     */
    private String describeBlocker(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        Vec3d moved = Entity.adjustMovementForCollisions(mc.player, delta, hitboxAt(from), mc.world, List.of());
        Box base = hitboxAt(from).offset(0, moved.y, 0);

        int steps = Math.max(1, MathHelper.ceil(horizontalDistance(from, to) / 0.25));
        for (int i = 1; i <= steps; i++) {
            Box box = base.offset(delta.x * i / steps, 0, delta.z * i / steps);
            String block = collidingBlock(box);
            if (block != null) return block;
        }

        return "an entity or the world border";
    }

    private String describeObstruction(Vec3d feet) {
        Box box = hitboxAt(feet);
        if (!isLoaded(MathHelper.floor(feet.x), MathHelper.floor(feet.z))) return "an unloaded chunk";

        String block = collidingBlock(box);
        if (block != null) return block;

        for (BlockPos pos : blocksIn(box)) {
            BlockState state = mc.world.getBlockState(pos);
            if (state.getFluidState().isIn(FluidTags.LAVA) || isDangerous(state)) return describe(state, pos);
        }

        for (Entity entity : mc.world.getOtherEntities(mc.player, box)) {
            if (entity.isCollidable(mc.player)) return entity.getName().getString() + " at " + fmt(entity.getEntityPos());
        }

        return "the world border or an entity";
    }

    private String collidingBlock(Box box) {
        for (BlockPos pos : blocksIn(box)) {
            BlockState state = mc.world.getBlockState(pos);
            for (Box shape : state.getCollisionShape(mc.world, pos).getBoundingBoxes()) {
                if (shape.offset(pos).intersects(box)) return describe(state, pos);
            }
        }
        return null;
    }

    private static Iterable<BlockPos> blocksIn(Box box) {
        return BlockPos.iterate(
            MathHelper.floor(box.minX), MathHelper.floor(box.minY), MathHelper.floor(box.minZ),
            MathHelper.floor(box.maxX), MathHelper.floor(box.maxY), MathHelper.floor(box.maxZ)
        );
    }

    private static String describe(BlockState state, BlockPos pos) {
        return state.getBlock().getName().getString() + " at " + pos.toShortString();
    }

    private static void log(String message, Object... args) {
        CoordClipper.LOG.info("[CoordClip] {}", String.format(Locale.ROOT, message, args));
    }

    private static String fmt(Vec3d vec) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", vec.x, vec.y, vec.z);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // Planning

    /**
     * A search from {@code pos} towards the landing spot if the target is loaded, otherwise towards the target's
     * column. It's exhaustive when close to the landing spot or when {@code exhaustive} is set. Returns null if it had
     * to stop.
     */
    private Search newSearch(Vec3d pos, boolean exhaustive) {
        if (!isLoaded(goal.getX(), goal.getZ())) {
            Vec3d waypoint = farthestLoadedOnLine(pos);
            if (horizontalDistance(pos, waypoint) >= MIN_SEGMENT) return new Search(pos, waypoint, null, false);

            // Not enough of the line is loaded yet, wait for the server to send more chunks
            if (hops.isEmpty()) {
                stallTicks += SEARCH_RETRY + 1;
                if (stallTicks > STALL_TIMEOUT) {
                    fail(String.format(Locale.ROOT, "Couldn't get closer for %d seconds, giving up.", STALL_TIMEOUT / 20));
                    return null;
                }
            }
            searchCooldown = SEARCH_RETRY;
            return null;
        }

        if (landing == null || !standable(landing, false)) {
            landing = findLanding(goal);
            if (landing == null) {
                fail(String.format(Locale.ROOT, "No solid ground within (highlight)%d(default) blocks of the target.", TARGET_SEARCH_RADIUS));
                return null;
            }

            info("Landing at (highlight)%.0f %.0f %.0f(default).", Math.floor(landing.x), landing.y, Math.floor(landing.z));
        }

        exhaustive |= pos.distanceTo(landing) <= EXHAUSTIVE_RANGE;
        return new Search(pos, landing, BlockPos.ofFloored(landing), exhaustive);
    }

    /**
     * The farthest point on the straight line from where the clip started to the target that's loaded all the way
     * from {@code pos}'s spot on the line.
     */
    private Vec3d farthestLoadedOnLine(Vec3d pos) {
        double lx = goal.getX() + 0.5 - origin.x, lz = goal.getZ() + 0.5 - origin.z;
        double length = Math.sqrt(lx * lx + lz * lz);
        if (length < 1.0E-6) return pos;
        double ux = lx / length, uz = lz / length;

        double t = MathHelper.clamp((pos.x - origin.x) * ux + (pos.z - origin.z) * uz, 0, length);
        double loaded = t;
        for (double d = t + LINE_SCAN_STEP; d <= length; d += LINE_SCAN_STEP) {
            if (!isLoaded(MathHelper.floor(origin.x + ux * d), MathHelper.floor(origin.z + uz * d))) break;
            loaded = d;
        }

        return new Vec3d(origin.x + ux * loaded, pos.y, origin.z + uz * loaded);
    }

    /**
     * Horizontal distance from the straight line between where the clip started and the target.
     */
    private double distanceFromLine(Vec3d vec) {
        double lx = goal.getX() + 0.5 - origin.x, lz = goal.getZ() + 0.5 - origin.z;
        double length = Math.sqrt(lx * lx + lz * lz);
        if (length < 1.0E-6) return 0;

        return Math.abs((vec.x - origin.x) * lz - (vec.z - origin.z) * lx) / length;
    }

    private static class Node {
        final BlockPos pos;
        final Vec3d vec;
        final Node parent;
        final int hops;
        // Number of stops in a row in the air, 0 when standing on something
        final int airChain;
        final double h, f;

        Node(BlockPos pos, Vec3d vec, Node parent, int hops, int airChain, double h, double weight) {
            this.pos = pos;
            this.vec = vec;
            this.parent = parent;
            this.hops = hops;
            this.airChain = airChain;
            this.h = h;
            this.f = hops + weight * h;
        }
    }

    /**
     * A* over ground spots, minimising the number of moves. Spread over several ticks so it doesn't freeze the game.
     * With no {@code target} (target unloaded) {@code goalVec} is a point on the straight line to the target and it
     * returns the route to the spot closest to it, staying near the line; with a target it returns the route to the
     * target, or to the closest ground spot if the target can't be reached.
     * <p>
     * When exhaustive it isn't weighted (so the route found is the shortest), doesn't give up early, and also stops in
     * the air straight above or below other stops, which is how it climbs walls taller than one move.
     */
    private class Search {
        final Vec3d goalVec;
        final BlockPos target;
        final boolean exhaustive;
        final double weight;
        final int maxExpansions, maxAirChain;
        final int bottomY, topY;
        final int version = planVersion;

        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.<Node>comparingDouble(n -> n.f).thenComparingDouble(n -> n.h));
        final Long2IntOpenHashMap bestHops = new Long2IntOpenHashMap();
        final LongOpenHashSet closed = new LongOpenHashSet();
        final BlockPos.Mutable mutable = new BlockPos.Mutable();

        final Node start;
        Node closest;
        int expansions, staleExpansions;
        List<Vec3d> result;

        Search(Vec3d from, Vec3d goalVec, BlockPos target, boolean exhaustive) {
            this.goalVec = goalVec;
            this.target = target;
            this.exhaustive = exhaustive;
            this.weight = exhaustive ? 1 : HEURISTIC_WEIGHT;
            this.maxExpansions = exhaustive ? MAX_EXHAUSTIVE_EXPANSIONS : MAX_EXPANSIONS;
            this.maxAirChain = exhaustive ? MAX_AIR_STOPS : 0;
            this.bottomY = mc.world.getBottomY();
            this.topY = bottomY + mc.world.getHeight();
            bestHops.defaultReturnValue(Integer.MAX_VALUE);

            start = new Node(BlockPos.ofFloored(from), from, null, 0, 0, heuristic(from), weight);
            closest = start;
            open.add(start);
        }

        double heuristic(Vec3d vec) {
            if (target != null) return vec.distanceTo(goalVec) / MAX_HOP;

            // Until we know where we're landing only the horizontal distance matters, plus how far off the line we are
            return (horizontalDistance(vec, goalVec) + LINE_DEVIATION_WEIGHT * distanceFromLine(vec)) / MAX_HOP;
        }

        /**
         * Returns true once {@link #result} is ready.
         */
        boolean run(long nanos) {
            long end = System.nanoTime() + nanos;
            if (result != null) return true;

            while (System.nanoTime() < end) {
                Node node = open.poll();
                if (node == null || expansions >= maxExpansions || (!exhaustive && staleExpansions >= MAX_STALE_EXPANSIONS)) {
                    return finish(closest);
                }

                long key = node.pos.asLong();
                if (closed.contains(key)) continue;

                // The quick search only does the expensive checks (can the player stand there, is the way clear) on
                // spots it actually picks, instead of on every spot within reach of every step
                if (!exhaustive && node.parent != null && (!isSpot(key, node.pos.getX(), node.pos.getY(), node.pos.getZ()) || !canMove(node.parent.vec, node.vec))) {
                    bestHops.remove(key);
                    continue;
                }

                closed.add(key);
                if (node.pos.equals(target)) return finish(node);

                // Never end a route in the air
                if (node.airChain == 0 && node.h < closest.h) {
                    closest = node;
                    staleExpansions = 0;
                }
                else staleExpansions++;

                expansions++;
                expand(node);
            }

            return false;
        }

        void expand(Node node) {
            double vx = node.vec.x, vy = node.vec.y, vz = node.vec.z;
            int hops = node.hops + 1;

            // Ground spots within one move. Only the column's y range that's in reach is checked
            for (int dx = -REACH; dx <= REACH; dx++) {
                int x = node.pos.getX() + dx;
                double hx = x + 0.5 - vx;

                for (int dz = -REACH; dz <= REACH; dz++) {
                    int z = node.pos.getZ() + dz;
                    double hz = z + 0.5 - vz;
                    double horizontalSq = hx * hx + hz * hz;
                    if (horizontalSq > MAX_HOP_SQ || !isLoaded(x, z)) continue;

                    double dyMax = Math.sqrt(MAX_HOP_SQ - horizontalSq);
                    int minY = Math.max(bottomY + 1, MathHelper.ceil(vy - dyMax));
                    int maxY = Math.min(topY - 1, MathHelper.floor(vy + dyMax));

                    for (int y = minY; y <= maxY; y++) {
                        long key = BlockPos.asLong(x, y, z);
                        if (bestHops.get(key) <= hops || closed.contains(key)) continue;

                        // Quick search: only the cheap block check here, the rest once the spot is picked
                        if (exhaustive ? !isSpot(key, x, y, z) : !isGroundCached(key, x, y, z)) continue;

                        Vec3d vec = new Vec3d(x + 0.5, y, z + 0.5);
                        if (exhaustive && !canMove(node.vec, vec)) continue;

                        bestHops.put(key, hops);
                        open.add(new Node(new BlockPos(x, y, z), vec, node, hops, 0, heuristic(vec), weight));
                    }
                }
            }

            // Air stops straight above or below, the server always accepts purely vertical moves
            if (node.airChain >= maxAirChain) return;

            for (int dy = -REACH; dy <= REACH; dy++) {
                if (dy == 0) continue;

                Vec3d vec = node.vec.add(0, dy, 0);
                BlockPos pos = BlockPos.ofFloored(vec);
                long key = pos.asLong();
                if (bestHops.get(key) <= hops || closed.contains(key) || !canStop(vec)) continue;

                boolean onGround = isSpot(key, pos.getX(), pos.getY(), pos.getZ());
                bestHops.put(key, hops);
                open.add(new Node(pos, vec, node, hops, onGround ? 0 : node.airChain + 1, heuristic(vec), weight));
            }
        }

        /**
         * The cheap part of {@link #isSpot}: passable block above a block with a hitbox. Known spots skip it.
         */
        boolean isGroundCached(long key, int x, int y, int z) {
            mutable.set(x, y, z);
            if (mutable.equals(target)) return true;
            if (spotCache.containsKey(key)) return spotCache.get(key);
            return isGround(mutable);
        }

        boolean isSpot(long key, int x, int y, int z) {
            mutable.set(x, y, z);
            if (mutable.equals(target)) return standable(landing, false);

            if (spotCache.containsKey(key)) return spotCache.get(key);
            boolean spot = isGround(mutable) && standable(Vec3d.ofBottomCenter(mutable), true);
            spotCache.put(key, spot);
            return spot;
        }

        boolean finish(Node end) {
            result = new ArrayList<>();
            if (end.h * MAX_HOP > start.h * MAX_HOP - MIN_PROGRESS && !end.pos.equals(target)) return true;

            for (Node node = end; node != start; node = node.parent) result.add(node.vec);
            Collections.reverse(result);
            return true;
        }
    }

    /**
     * Whether the server would accept a single movement packet from {@code from} to {@code to}. It replays the move
     * with collisions, vertically first, and puts you back if the horizontal part didn't get where you said. The
     * vertical part is never checked, so only the horizontal part has to be clear. {@code to} itself has to be free.
     */
    private boolean canMove(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        Vec3d moved = Entity.adjustMovementForCollisions(mc.player, delta, hitboxAt(from), mc.world, List.of());

        double ex = delta.x - moved.x, ez = delta.z - moved.z;
        return ex * ex + ez * ez <= MAX_HORIZONTAL_ERROR_SQ;
    }

    /**
     * Whether the player can be held at this position for a moment: the landing spot, or anywhere the player fits
     * (water is fine) without touching anything that hurts. Used to recheck a move right before it's sent.
     */
    private boolean canStop(Vec3d feet) {
        if (feet.equals(landing)) return standable(feet, false);
        return fits(feet, true) && mc.world.getStatesInBox(hitboxAt(feet)).noneMatch(CoordClipCommand::isDangerous);
    }

    /**
     * Nearest spot to {@code center} where the player fits and stands on solid ground, out of water, searching
     * outwards in cubic shells until no closer spot is possible.
     */
    private Vec3d findLanding(BlockPos center) {
        Vec3d wanted = Vec3d.ofBottomCenter(center);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;

        for (int r = 0; r <= TARGET_SEARCH_RADIUS; r++) {
            // Every spot in this shell is at least r blocks away
            if ((double) r * r > bestDist) break;

            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    boolean onFace = Math.abs(x) == r || Math.abs(y) == r;
                    for (int z = -r; z <= r; z += onFace ? 1 : Math.max(1, 2 * r)) {
                        pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                        if (!isLoaded(pos.getX(), pos.getZ()) || !isGround(pos)) continue;

                        Vec3d spot = Vec3d.ofBottomCenter(pos);
                        double dist = spot.squaredDistanceTo(wanted);
                        if (dist < bestDist && standable(spot, false)) {
                            best = spot;
                            bestDist = dist;
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * Cheap block check before the full hitbox check: passable feet block, not lava, above a block with a hitbox.
     */
    private boolean isGround(BlockPos pos) {
        BlockPos below = pos.down();
        if (mc.world.isOutOfHeightLimit(below)) return false;

        BlockState feet = mc.world.getBlockState(pos);
        if (!feet.getCollisionShape(mc.world, pos).isEmpty() || feet.getFluidState().isIn(FluidTags.LAVA)) return false;
        return !mc.world.getBlockState(below).getCollisionShape(mc.world, below).isEmpty();
    }

    /**
     * Whether the player fits at the given feet position, has solid ground directly underneath, and isn't standing in
     * or on anything that hurts.
     */
    private boolean standable(Vec3d feet, boolean allowWater) {
        if (!fits(feet, allowWater)) return false;

        Box box = hitboxAt(feet);
        Box ground = new Box(box.minX, box.minY - 0.05, box.minZ, box.maxX, box.minY, box.maxZ);
        if (mc.world.isSpaceEmpty(mc.player, ground)) return false;

        return mc.world.getStatesInBox(box.union(ground)).noneMatch(CoordClipCommand::isDangerous);
    }

    private static boolean isDangerous(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.MAGMA_BLOCK)
            || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.CACTUS)
            || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.WITHER_ROSE) || state.isOf(Blocks.COBWEB)
            || state.isOf(Blocks.POWDER_SNOW) || state.getFluidState().isIn(FluidTags.LAVA);
    }

    /**
     * Whether the player's hitbox fits at the given feet position without touching blocks, entities or fluids
     * (other than water, if allowed). Unloaded chunks never fit, since the client doesn't know what's there.
     */
    private boolean fits(Vec3d feet, boolean allowWater) {
        Box box = hitboxAt(feet);

        int minX = MathHelper.floor(box.minX) >> 4, maxX = MathHelper.floor(box.maxX) >> 4;
        int minZ = MathHelper.floor(box.minZ) >> 4, maxZ = MathHelper.floor(box.maxZ) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return false;
            }
        }

        return mc.world.isSpaceEmpty(mc.player, box) && mc.world.getStatesInBox(box).allMatch(state -> {
            FluidState fluid = state.getFluidState();
            return fluid.isEmpty() || (allowWater && fluid.isIn(FluidTags.WATER));
        });
    }

    private Box hitboxAt(Vec3d feet) {
        return mc.player.getBoundingBox().offset(feet.subtract(mc.player.getEntityPos()));
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        return Math.sqrt(MathHelper.square(a.x - b.x) + MathHelper.square(a.z - b.z));
    }

    private boolean isLoaded(int x, int z) {
        return mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }
}
