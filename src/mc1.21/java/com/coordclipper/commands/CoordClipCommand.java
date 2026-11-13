package com.coordclipper.commands;

import com.coordclipper.CoordClipper;
import com.coordclipper.modules.CoordClipSettings;
import com.coordclipper.utils.Compat;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Clips to a target position through a chain of moves, ending on the nearest solid ground to the target.
 * <p>
 * Moves work like Meteor's .hclip: the player is only moved client side, and the game's own movement packet for that
 * tick carries the move, so the server never sees more than one movement packet per tick. Server side, a movement
 * packet may move at most 10 blocks, and the server replays it with collisions: vertically first (a vclip), then
 * horizontally (an hclip). The vertical difference is ignored, so vclips go through floors and ceilings, but the
 * horizontal part must not run into anything or the server puts the player back. With unlimited vertical clips on,
 * moves straight up or down can be any length, so it clips up or down to the right height in one move and then across.
 * <p>
 * Routes are found with A* over spots to stand on (including partial blocks like mud, slabs and soul sand) in loaded
 * chunks, plus short chains of stops in mid-air to get over trees, walls and canopies. Only the chunk the next move
 * lands in needs to be loaded: while the target is unloaded it heads along the straight line to it as far as the loaded
 * chunks reach. The next part of the route is planned while the current one is walked.
 * <p>
 * Setbacks are caught as they arrive and matched to the move the server rejected; it then holds still where the
 * server put it and plans again from there. On the way it stops to breathe when it's about to drown and to eat when
 * it's hungry. Setbacks and other server problems are explained in detail in the game log.
 */
public class CoordClipCommand extends Command {
    // Vanilla rejects moves over 10 blocks per movement packet
    private static final double SERVER_MAX_MOVE = 10;
    // Vanilla puts you back if the horizontal part of a move ends more than 0.25 blocks from where you said
    private static final double MAX_HORIZONTAL_ERROR_SQ = 0.03;
    private static final double MIN_PROGRESS = 1;

    // A* limits. Far away it's weighted towards the target and gives up early, close to the landing spot it's exhaustive
    private static final double HEURISTIC_WEIGHT = 2;
    private static final int MAX_EXPANSIONS = 3000;
    private static final int MAX_STALE_EXPANSIONS = 50;
    private static final double EXHAUSTIVE_RANGE = 64;
    private static final int MAX_EXHAUSTIVE_EXPANSIONS = 20000;
    private static final long IDLE_SEARCH_NANOS = 15_000_000;
    private static final long MOVING_SEARCH_NANOS = 10_000_000;
    // Stops in mid-air cost a bit more than stops on the ground, so it only flies when that's actually shorter
    private static final double AIR_STOP_COST = 0.5;

    private static final int RETRY_DELAY = 10;
    private static final int SEARCH_RETRY = 5;
    // Columns within this many blocks are all checked for spots to stand on, beyond that (with long moves) only a grid
    // of them is, and only at heights within this many blocks and the top of the column. Checking every block within
    // 100 blocks for every step would take forever
    private static final int NEAR = 10;
    private static final int FAR_COLUMNS = 24;
    // Longest it holds still in mid-air before dropping to the ground, well under the server's 80 tick floating kick
    private static final int MAX_AIR_HOLD_TICKS = 40;
    // Drops the server can take without fall damage (it's safe up to 3 blocks), and how far up the move after a longer
    // drop is lifted to clear the fall
    private static final double SAFE_DROP = 2.5;
    private static final double FALL_RESET_LIFT = 0.01;
    // Planned moves thrown away in a row that get logged, after that only the total is
    private static final int LOGGED_DISCARDS = 5;

    // While the target is unloaded it aims for the farthest loaded point on the straight line to it, and every block
    // off that line counts as this many blocks further away, so it doesn't zigzag towards whichever chunks the server
    // happened to send first
    private static final double LINE_DEVIATION_WEIGHT = 2;
    private static final double LINE_SCAN_STEP = 4;
    // Don't bother planning until at least this much more of the line is loaded
    private static final double MIN_SEGMENT = 16;
    // Standable spots are remembered between searches for this long
    private static final int SPOT_CACHE_TICKS = 100;

    // Breathing and eating
    private static final int BREATH_SEARCH_UP = 64;
    private static final int MAX_BREATHING_TICKS = 20 * 30;
    private static final int MAX_EATING_TICKS = 20 * 60;

    private enum Pause { NONE, BREATHING, PLACING_DOOR, EATING }

    private record Move(int number, Vec3d from, Vec3d to, long time) {}

    // Planned moves not sent yet. Searches extend it from its end while it's being walked
    private final Deque<Vec3d> hops = new ArrayDeque<>();
    // Sent moves recent enough that the server could still put us back for them
    private final Deque<Move> pending = new ArrayDeque<>();
    private final List<String> setbackCauses = new ArrayList<>();
    private Search search;
    // Feet height of the spot to stand on at each block position (NaN for none), shared between searches
    private final Long2DoubleOpenHashMap standCache = new Long2DoubleOpenHashMap();
    // Whether the player can actually stand at each spot, shared between searches. Keyed by the exact position, since
    // the player's hitbox can fit at one spot in a block and not at another
    private final Object2BooleanOpenHashMap<Vec3d> spotCache = new Object2BooleanOpenHashMap<>();
    // Stops that a search planned but turned out to be impossible right before the move, never planned again
    private final Set<Vec3d> rejectedStops = new ObjectOpenHashSet<>();
    private int spotCacheAge;
    private BlockPos goal;
    private Vec3d landing, last;
    // Where the clip started, the straight line from here to the target is the line it tries to stay on
    private Vec3d origin;
    private boolean arriving;
    // Bumped whenever the planned route is thrown away, so searches planned from its old end get discarded
    private int planVersion;
    private int timer, searchCooldown, stallTicks, setbacks, moves, totalSetbacks;
    // Watchdog: closest it has been to where it's going, and ticks since it got closer or last moved
    private double bestRemaining;
    private int noProgressTicks, ticksSinceMove, discards;
    private long startTime;
    // Fall damage: the height of the last position sent, and whether the server is counting a fall that the next
    // movement packet has to clear
    private double sentY;
    private boolean clearFall;
    // Set when giving up in mid-air: it drops to the ground first and stops with this message once the fall is cleared
    private String stopMessage;
    // Where the server teleported us, set from the network thread
    private volatile Vec3d serverTeleport;

    private Pause pause = Pause.NONE;
    private int pauseTicks, eatSlot, prevSlot, breathRetry;
    private BlockPos doorPos;
    private boolean warnedNoAir, warnedNoFood;

    // Settings, read when a clip starts
    private CoordClipSettings settings;
    private double maxHop, maxHopSq, maxHopSent;
    // Column offsets checked for spots to stand on within one move: all of them nearby, a grid further out
    private int[][] columns;
    private int reach, moveDelay, maxSetbacks, stuckTimeout, maxAirStops, landingRadius;
    private boolean unlimitedVertical;

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

        builder.then(argument("pos", Compat.blockPosArgument()).executes(context -> {
            if (mc.player.hasVehicle()) {
                error("Dismount first.");
                return SINGLE_SUCCESS;
            }

            stop();
            readSettings();
            goal = Compat.getBlockPos(context, "pos");
            landing = last = null;
            serverTeleport = null;
            arriving = false;
            timer = searchCooldown = stallTicks = setbacks = moves = totalSetbacks = 0;
            noProgressTicks = ticksSinceMove = discards = 0;
            bestRemaining = Double.MAX_VALUE;
            rejectedStops.clear();
            sentY = mc.player.getY();
            clearFall = false;
            stopMessage = null;
            pause = Pause.NONE;
            warnedNoAir = warnedNoFood = false;
            pending.clear();
            setbackCauses.clear();
            clearCaches();
            startTime = System.currentTimeMillis();
            origin = Compat.pos(mc.player);
            MeteorClient.EVENT_BUS.subscribe(this);

            double distance = Math.sqrt(goal.getSquaredDistance(Compat.pos(mc.player)));
            info("Clipping to (highlight)%d %d %d(default), (highlight)%.0f(default) blocks away.", goal.getX(), goal.getY(), goal.getZ(), distance);
            return SINGLE_SUCCESS;
        }));
    }

    private void readSettings() {
        settings = CoordClipSettings.get();
        maxHop = settings.maxHop.get();
        maxHopSq = maxHop * maxHop;
        // A little slack for spots off the block grid, but never past the vanilla limit when the setting is under it
        maxHopSent = maxHop < 10 ? Math.min(maxHop + 0.4, 9.9) : maxHop + 0.4;
        reach = (int) maxHop;

        int step = Math.max(1, (int) Math.ceil(2.0 * reach / FAR_COLUMNS));
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz > maxHopSq) continue;
                boolean near = Math.abs(dx) <= NEAR && Math.abs(dz) <= NEAR;
                if (near || (dx % step == 0 && dz % step == 0)) offsets.add(new int[] {dx, dz});
            }
        }
        columns = offsets.toArray(new int[0][]);
        unlimitedVertical = settings.unlimitedVertical.get();
        moveDelay = settings.moveDelay.get();
        maxSetbacks = settings.maxSetbacks.get();
        stuckTimeout = settings.stuckTimeout.get() * 20;
        maxAirStops = settings.maxAirStops.get();
        landingRadius = settings.landingRadius.get();
    }

    private void clearCaches() {
        standCache.clear();
        spotCache.clear();
        spotCacheAge = 0;
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

        if (clearFall) Compat.forcePositionPacket(mc.player);
        if (stopMessage != null) {
            if (last != null) {
                mc.player.setPosition(last);
                mc.player.setVelocity(Vec3d.ZERO);
            }
            if (!clearFall && ++ticksSinceMove > 1) {
                String message = stopMessage;
                stop();
                error(message.replace("%", "%%"));
            }
            return;
        }

        if (++spotCacheAge > SPOT_CACHE_TICKS) clearCaches();

        // Hold still at the last stop, so we don't fall out of the air or sink in water between moves
        if (last != null) {
            mc.player.setPosition(last);
            mc.player.setVelocity(Vec3d.ZERO);
        }

        if (tickSurvival()) return;

        ticksSinceMove++;
        if (checkStuck()) return;

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
        Vec3d pos = Compat.pos(mc.player);
        if (checkMove(pos, next)) {
            discards = 0;
            move(pos, next);
        }
        else {
            // Don't plan the same impossible stop again, or it would plan it, reject it and plan it again forever
            rejectedStops.add(next);
            if (++discards <= LOGGED_DISCARDS) log("Planned move %s -> %s isn't possible (%s), planning again without it.", fmt(pos), fmt(next), whyNot(pos, next));
            else if (discards == LOGGED_DISCARDS + 1) log("More planned moves aren't possible, not logging them.");
            discardRoute();
        }
    }

    /**
     * Catches every way of getting stuck: drops to the ground if it's been hanging in mid-air too long, and gives up if
     * it hasn't got any closer for the stuck timeout. Returns true if it did either.
     */
    private boolean checkStuck() {
        Vec3d pos = Compat.pos(mc.player);

        double remaining = remaining(pos);
        if (remaining < bestRemaining - MIN_PROGRESS) {
            bestRemaining = remaining;
            noProgressTicks = 0;
        }
        // Exhaustive searches can take a while and give up on their own if there's no way
        else if (!arriving && !(search != null && search.exhaustive)) noProgressTicks++;

        if (noProgressTicks > stuckTimeout) {
            CoordClipper.LOG.warn("[CoordClip] No progress for {} ticks at {}, {} blocks to go. Planned moves: {}, waiting for confirmation: {}, searching: {}, rejected stops: {}, setbacks: {}.",
                noProgressTicks, fmt(pos), fmt(remaining), hops.size(), pending.size(), search != null, rejectedStops.size(), totalSetbacks);
            fail(String.format(Locale.ROOT, "Didn't get any closer for %d seconds, stopped (highlight)%.0f(default) blocks away.", stuckTimeout / 20, remaining));
            return true;
        }

        if (ticksSinceMove > MAX_AIR_HOLD_TICKS && pending.isEmpty() && !canWaitAt(pos)) {
            Vec3d ground = findGroundBelow(pos);
            if (ground != null) {
                log("Held in mid-air at %s for %d ticks, dropping to the ground at %s.", fmt(pos), ticksSinceMove, fmt(ground));
                discardRoute();
                sendMove(pos, ground);
                timer = moveDelay - 1;
                return true;
            }
        }

        return false;
    }

    /**
     * How far it still has to go: to the landing spot once it's known, otherwise horizontally to the target.
     */
    private double remaining(Vec3d pos) {
        if (landing != null) return pos.distanceTo(landing);
        return horizontalDistance(pos, Vec3d.ofBottomCenter(goal));
    }

    private String whyNot(Vec3d from, Vec3d to) {
        boolean vertical = unlimitedVertical && horizontalDistance(from, to) < 1.0E-3;
        if (!vertical && from.distanceTo(to) > maxHopSent) return String.format(Locale.ROOT, "%.2f blocks, too far", from.distanceTo(to));
        if (!canStop(to)) return "blocked by " + describeObstruction(to);
        if (!canMove(from, to)) return "path blocked by " + describeBlocker(from, to);
        return "unknown";
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
        return last != null ? last : Compat.pos(mc.player);
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

        // The quick search found nothing, try every route (including through the air) before giving up
        if (!done.exhaustive) {
            search = new Search(done.start.vec, done.goalVec, done.target, true);
            return;
        }

        // Only give up once the rest of the route has been walked and it still can't get closer
        if (!hops.isEmpty() || !pending.isEmpty()) {
            searchCooldown = SEARCH_RETRY;
            return;
        }

        // Don't hang in mid-air while stuck, the server kicks you for floating
        Vec3d pos = Compat.pos(mc.player);
        if (!canWaitAt(pos)) {
            Vec3d ground = findGroundBelow(pos);
            if (ground != null) {
                log("No route from mid-air at %s, dropping to the ground at %s.", fmt(pos), fmt(ground));
                move(pos, ground);
                searchCooldown = SEARCH_RETRY;
                return;
            }
        }

        stallTicks += SEARCH_RETRY + 1;
        if (stallTicks > stuckTimeout) {
            if (done.target != null) {
                fail(String.format(Locale.ROOT, "Can't get any closer, stopped (highlight)%.0f(default) blocks from the landing spot.", Compat.pos(mc.player).distanceTo(landing)));
            }
            else fail(String.format(Locale.ROOT, "Couldn't get closer for %d seconds, giving up.", stuckTimeout / 20));
            return;
        }

        // The world or the loaded chunks may change, look again in a moment
        clearCaches();
        searchCooldown = SEARCH_RETRY;
    }

    /**
     * The first spot to stand on straight below, reachable in one vertical clip.
     */
    private Vec3d findGroundBelow(Vec3d pos) {
        int x = MathHelper.floor(pos.x), z = MathHelper.floor(pos.z);
        int minY = unlimitedVertical ? mc.world.getBottomY() : MathHelper.floor(pos.y - maxHop);

        for (int y = MathHelper.floor(pos.y); y >= minY; y--) {
            double standY = standHeight(x, y, z);
            if (Double.isNaN(standY) || standY > pos.y) continue;

            Vec3d spot = new Vec3d(pos.x, standY, pos.z);
            if (standable(spot, true)) return spot;
        }
        return null;
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
     * Sorts out what a server teleport means: a resync at a move's destination is harmless, anything else is treated
     * as a setback and retried from wherever the server put us.
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
            countSetback(pos, "delayed setback or plugin teleport");
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

        onSetBack(rejected, pending.peekLast().number - rejected.number, pos);
    }

    private void onSetBack(Move move, int ignored, Vec3d pos) {
        List<String> causes = diagnose(move, pos);
        String summary = String.join("; ", causes);
        setbackCauses.add("#" + move.number + ": " + summary);

        CoordClipper.LOG.warn("[CoordClip] Server put you back after move #{}{}.\n  Move: {} -> {} ({} blocks, {} horizontal, {} vertical)\n  Put back to: {} ({} blocks from the start of the move)\n  {} ms after the move, ping {} ms, server {} TPS, last server tick {}s ago\n  Likely cause: {}",
            move.number, ignored > 0 ? " (the server ignored the " + ignored + " moves sent after it)" : "",
            fmt(move.from), fmt(move.to), fmt(move.from.distanceTo(move.to)), fmt(horizontalDistance(move.from, move.to)), fmt(move.to.y - move.from.y),
            fmt(pos), fmt(pos.distanceTo(move.from)),
            System.currentTimeMillis() - move.time, ping(), fmt(TickRate.INSTANCE.getTickRate()), fmt(TickRate.INSTANCE.getTimeSinceLastTick()),
            summary);

        countSetback(pos, causes.getFirst());
    }

    private void countSetback(Vec3d pos, String cause) {
        totalSetbacks++;
        holdAndRetry(pos);

        if (++setbacks >= maxSetbacks) {
            failLogged(String.format(Locale.ROOT, "Server put you back %d times in a row, giving up. Last cause: %s", maxSetbacks, cause),
                "Giving up after %d setbacks in a row. Causes:\n  %s", maxSetbacks, String.join("\n  ", setbackCauses.subList(Math.max(0, setbackCauses.size() - maxSetbacks), setbackCauses.size())));
            return;
        }

        warning("Server put you back (%d/%d): %s", setbacks, maxSetbacks, cause);
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

        // Whatever we were waiting for, check again from the new spot
        if (pause == Pause.EATING) stopEating();
        pause = Pause.NONE;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket packet) {
            Vec3d target = Compat.teleportTarget(packet, mc.player);
            serverTeleport = target;
            // The game confirms the teleport with a movement packet right away, from the new height
            sentY = target.y;
        }
    }

    /**
     * Keeps clips down from doing fall damage. The server adds every move's drop to the fall distance and deals the
     * damage as soon as a packet says the player is on the ground, including the drop of that same packet. It only
     * forgets the fall when the player moves up. So moves dropping more than a few blocks are sent as in the air, and
     * the next movement packet is lifted a hair above the last one to clear the fall. Every other packet says on the
     * ground, so being held in mid-air (gravity pulls the player down a little every tick) never adds up.
     */
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!settings.noFallDamage.get() || !(event.packet instanceof PlayerMoveC2SPacket packet)) return;

        if (!packet.changesPosition()) {
            Compat.setOnGround(packet, !clearFall);
            return;
        }

        double y = packet.getY(sentY);
        double dy = y - sentY;
        if (dy < -SAFE_DROP) {
            Compat.setOnGround(packet, false);
            clearFall = true;
        }
        else if (clearFall) {
            Compat.setOnGround(packet, false);
            if (dy > 0) clearFall = false;
            else if (dy > -0.5) {
                y = sentY + FALL_RESET_LIFT;
                Compat.setY(packet, y);
                clearFall = false;
            }
        }
        else Compat.setOnGround(packet, true);

        sentY = y;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        log("Disconnected after %d moves, stopped.", moves);
        stop();
    }

    private void stop() {
        if (pause == Pause.EATING) stopEating();
        pause = Pause.NONE;
        hops.clear();
        pending.clear();
        search = null;
        goal = null;
        stopMessage = null;
        MeteorClient.EVENT_BUS.unsubscribe(this);
    }

    private void fail(String chat) {
        // Don't leave the player hanging in the air to fall once it lets go
        if (!mc.player.isDead() && stopMessage == null) {
            Vec3d pos = Compat.pos(mc.player);
            if (!canWaitAt(pos)) {
                Vec3d ground = findGroundBelow(pos);
                if (ground != null) {
                    log("Stopping in mid-air at %s, dropping to the ground at %s first.", fmt(pos), fmt(ground));
                    discardRoute();
                    sendMove(pos, ground);

                    // Stay a moment longer, so the fall from the drop is cleared before letting go
                    if (settings.noFallDamage.get()) {
                        stopMessage = chat;
                        return;
                    }
                }
            }
        }

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
     * Moves go out every {@code move-delay} ticks without waiting; setbacks are matched to them as they arrive.
     */
    private void move(Vec3d from, Vec3d to) {
        sendMove(from, to);

        // Give the server a round trip to object to the final move before calling it done
        if (to.equals(landing) && hops.isEmpty()) {
            arriving = true;
            timer = checkTicks();
        }
        else timer = moveDelay - 1;
    }

    private void sendMove(Vec3d from, Vec3d to) {
        mc.player.setPosition(to);
        mc.player.setVelocity(Vec3d.ZERO);

        last = to;
        ticksSinceMove = 0;
        pending.addLast(new Move(++moves, from, to, System.currentTimeMillis()));
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
        boolean vertical = unlimitedVertical && horizontalDistance(from, to) < 1.0E-3;
        return (vertical || from.distanceTo(to) <= maxHopSent) && canStop(to) && canMove(from, to);
    }

    // Breathing and eating

    /**
     * Runs the breathing and eating pauses. Returns true while paused, so no moves are made.
     */
    private boolean tickSurvival() {
        switch (pause) {
            case BREATHING -> {
                pauseTicks++;
                if (mc.player.getAir() >= mc.player.getMaxAir()) {
                    info("Air refilled, continuing.");
                    endPause();
                    return false;
                }

                // Still running out, the spot didn't work: look again
                if (pauseTicks > 40 && pauseTicks % 40 == 0 && isLowOnAir()) startBreathing();
                if (pauseTicks > MAX_BREATHING_TICKS) {
                    warning("Couldn't refill your air, continuing.");
                    endPause();
                    return false;
                }
                return true;
            }
            case PLACING_DOOR -> {
                // The move down to the floor went out last tick, now the server knows we're in reach
                pause = placeDoor(doorPos) ? Pause.BREATHING : Pause.NONE;
                pauseTicks = 0;
                if (pause == Pause.NONE) warning("Couldn't place the door.");
                return pause != Pause.NONE;
            }
            case EATING -> {
                return tickEating();
            }
            default -> {}
        }

        if (settings.drownProtection.get() && isLowOnAir()) {
            if (breathRetry > 0) breathRetry--;
            else if (startBreathing()) return true;
            else breathRetry = 20;
        }
        else {
            warnedNoAir = false;
            breathRetry = 0;
        }

        // Only stop to eat somewhere we can stay for a while, not in the middle of a climb through the air
        if (settings.autoEat.get() && isHungry() && canWaitAt(Compat.pos(mc.player))) return startEating();

        return false;
    }

    private void endPause() {
        pause = Pause.NONE;
        discardRoute();
    }

    private boolean isLowOnAir() {
        int threshold = mc.player.getMaxAir() * settings.airBubbles.get() / 10;
        return mc.player.isSubmergedInWater() && mc.player.getAir() <= threshold;
    }

    private boolean isHungry() {
        return !mc.player.getAbilities().creativeMode && mc.player.getHungerManager().getFoodLevel() <= settings.hungerBars.get() * 2;
    }

    /**
     * Somewhere the player can be held for a while without the server kicking it for flying: on the ground or in water.
     */
    private boolean canWaitAt(Vec3d feet) {
        return standable(feet, true) || mc.world.getFluidState(BlockPos.ofFloored(feet)).isIn(FluidTags.WATER);
    }

    private boolean startBreathing() {
        Vec3d from = Compat.pos(mc.player);

        Vec3d spot = findBreathingSpot(from);
        if (spot != null) {
            discardRoute();
            if (spot.distanceTo(from) > 1.0E-3) sendMove(from, spot);
            pause = Pause.BREATHING;
            pauseTicks = 0;
            warnedNoAir = false;
            info("Low on air, clipped to open air to breathe.");
            return true;
        }

        if (settings.useDoors.get() && hasDoor()) {
            Vec3d floor = findDoorSpot(from);
            if (floor != null) {
                discardRoute();
                pauseTicks = 0;
                warnedNoAir = false;
                doorPos = BlockPos.ofFloored(floor);

                if (floor.distanceTo(from) > 1.0E-3) {
                    // Get to the floor first, the door goes in next tick once the server has us there
                    sendMove(from, floor);
                    pause = Pause.PLACING_DOOR;
                }
                else {
                    pause = placeDoor(doorPos) ? Pause.BREATHING : Pause.NONE;
                    if (pause == Pause.NONE) return false;
                }

                info("Low on air, placing a door to breathe in.");
                return true;
            }
        }

        if (!warnedNoAir) {
            warning("Low on air and there's no open air%s in reach, continuing.", settings.useDoors.get() ? " or room for a door" : "");
            CoordClipper.LOG.warn("[CoordClip] Low on air ({}/{}) at {}, no breathing spot within reach{}.",
                mc.player.getAir(), mc.player.getMaxAir(), fmt(from), settings.useDoors.get() ? (hasDoor() ? " and no room for a door" : " and no door in the inventory") : "");
            warnedNoAir = true;
        }
        return false;
    }

    /**
     * The nearest spot within one move where the player's head is out of water and it can wait: floating at the water
     * surface or standing on dry ground. With unlimited vertical clips it also looks far up its own column.
     */
    private Vec3d findBreathingSpot(Vec3d from) {
        double eye = mc.player.getStandingEyeHeight();
        int fx = MathHelper.floor(from.x), fy = MathHelper.floor(from.y), fz = MathHelper.floor(from.z);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;

        // Air is usually close by, so only look nearby even with long moves
        int near = Math.min(reach, NEAR);
        for (int dx = -near; dx <= near; dx++) {
            for (int dz = -near; dz <= near; dz++) {
                int x = fx + dx, z = fz + dz;
                boolean ownColumn = dx == 0 && dz == 0;
                if (dx * dx + dz * dz > maxHopSq || !isLoaded(x, z)) continue;

                int up = ownColumn && unlimitedVertical ? BREATH_SEARCH_UP : near;
                for (int y = fy - near; y <= fy + up; y++) {
                    Vec3d spot = ownColumn ? new Vec3d(from.x, y, from.z) : new Vec3d(x + 0.5, y, z + 0.5);
                    double dist = spot.distanceTo(from);
                    if (dist >= bestDist || (!(ownColumn && unlimitedVertical) && dist > maxHop)) continue;

                    // Cheap checks first: head out of water, feet not inside a block
                    pos.set(x, MathHelper.floor(y + eye), z);
                    BlockState head = mc.world.getBlockState(pos);
                    if (!head.getFluidState().isEmpty() || !head.getCollisionShape(mc.world, pos).isEmpty()) continue;
                    pos.set(x, y, z);
                    if (!mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) continue;

                    if (!canWaitAt(spot) || !canStop(spot) || !canMove(from, spot)) continue;
                    best = spot;
                    bestDist = dist;
                }
            }
        }

        return best;
    }

    private static final Predicate<ItemStack> DOOR = stack -> stack.getItem() instanceof BlockItem item && item.getBlock() instanceof DoorBlock;

    private boolean hasDoor() {
        return InvUtils.find(DOOR).found();
    }

    /**
     * Where to put a door: the player's own column, on a floor with two blocks of water above it.
     */
    private Vec3d findDoorSpot(Vec3d from) {
        int down = unlimitedVertical ? BREATH_SEARCH_UP : reach;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int y = MathHelper.floor(from.y); y >= MathHelper.floor(from.y) - down; y--) {
            pos.set(MathHelper.floor(from.x), y, MathHelper.floor(from.z));
            if (mc.world.isOutOfHeightLimit(pos.down())) break;

            BlockState below = mc.world.getBlockState(pos.down());
            if (!below.isSideSolidFullSquare(mc.world, pos.down(), Direction.UP)) {
                // Can't see through a floor, stop at the first solid one
                if (!below.getCollisionShape(mc.world, pos.down()).isEmpty()) break;
                continue;
            }

            if (!mc.world.getBlockState(pos).isReplaceable() || !mc.world.getBlockState(pos.up()).isReplaceable()) return null;
            Vec3d spot = new Vec3d(from.x, y, from.z);
            return canMove(from, spot) ? spot : null;
        }

        return null;
    }

    /**
     * Places a door in the block the player is standing in. Doors can't be waterlogged, so the water in both of its
     * blocks goes away and the player can breathe. The door sits on one edge of the block, so it doesn't hit the
     * player standing in the middle.
     */
    private boolean placeDoor(BlockPos pos) {
        FindItemResult door = InvUtils.find(DOOR);
        if (!door.found()) return false;

        if (!door.isHotbar() && !door.isOffhand()) {
            InvUtils.move().from(door.slot()).toHotbar(Compat.selectedSlot(mc.player));
            door = InvUtils.findInHotbar(DOOR);
            if (!door.found()) return false;
        }

        return BlockUtils.place(pos, door, false, 50, true, false, false);
    }

    private boolean startEating() {
        int slot = findFood();
        if (slot == -1) {
            if (settings.stopWithoutFood.get()) {
                fail("You're hungry and have nothing to eat, stopped.");
                return true;
            }

            if (!warnedNoFood) {
                warning("You're hungry and have nothing to eat, continuing.");
                warnedNoFood = true;
            }
            return false;
        }

        prevSlot = Compat.selectedSlot(mc.player);
        if (!selectFood(slot)) return false;

        discardRoute();
        pause = Pause.EATING;
        pauseTicks = 0;
        info("Hungry, stopping to eat.");
        return true;
    }

    private boolean tickEating() {
        pauseTicks++;

        int food = mc.player.getHungerManager().getFoodLevel();
        if (food >= settings.eatUntil.get() * 2 || pauseTicks > MAX_EATING_TICKS) {
            stopEating();
            info("Done eating, continuing.");
            endPause();
            return false;
        }

        // Ran out of this food, switch to the next one
        if (!isFood(mc.player.getInventory().getStack(eatSlot))) {
            int slot = findFood();
            if (slot == -1 || !selectFood(slot)) {
                stopEating();
                if (food <= settings.hungerBars.get() * 2 && settings.stopWithoutFood.get()) {
                    fail("Ran out of food, stopped.");
                    return true;
                }

                info("Out of food, continuing.");
                endPause();
                return false;
            }
        }

        mc.options.useKey.setPressed(true);
        if (!mc.player.isUsingItem()) Utils.rightClick();
        return true;
    }

    private void stopEating() {
        mc.options.useKey.setPressed(false);
        if (prevSlot >= SlotUtils.HOTBAR_START && prevSlot <= SlotUtils.HOTBAR_END) InvUtils.swap(prevSlot, false);
    }

    private boolean isFood(ItemStack stack) {
        return stack.get(DataComponentTypes.FOOD) != null && !settings.foodBlacklist.get().contains(stack.getItem());
    }

    /**
     * Inventory slot of something to eat, hotbar first, or -1. The offhand isn't used, since whatever is in the main
     * hand would be used first.
     */
    private int findFood() {
        FindItemResult result = InvUtils.findInHotbar(this::isFood);
        if (result.found()) return result.slot();

        return InvUtils.find(this::isFood).slot();
    }

    private boolean selectFood(int slot) {
        if (!SlotUtils.isHotbar(slot)) {
            int empty = InvUtils.find(ItemStack::isEmpty, SlotUtils.HOTBAR_START, SlotUtils.HOTBAR_END).slot();
            int target = empty != -1 ? empty : Compat.selectedSlot(mc.player);
            InvUtils.move().from(slot).toHotbar(target);
            slot = target;
        }

        InvUtils.swap(slot, false);
        eatSlot = slot;
        return true;
    }

    // Diagnostics

    /**
     * Best guesses for why the server put us back, most likely first. Mirrors the server's movement checks.
     */
    private List<String> diagnose(Move move, Vec3d serverPos) {
        List<String> causes = new ArrayList<>();
        double distance = move.from.distanceTo(move.to);
        boolean vertical = horizontalDistance(move.from, move.to) < 1.0E-3;

        if (distance > SERVER_MAX_MOVE) {
            if (vertical) causes.add(String.format(Locale.ROOT, "the vertical clip was %.2f blocks; this server limits vertical moves too, turn off unlimited-vertical", distance));
            else causes.add(String.format(Locale.ROOT, "the move was %.2f blocks, over the server's %.0f block limit per movement packet", distance, SERVER_MAX_MOVE));
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
        if (serverPos.distanceTo(move.from) > 16 && serverPos.distanceTo(move.to) > 16) {
            causes.add(String.format(Locale.ROOT, "the server put you %.1f blocks from both ends of the move, so it was probably a plugin, /tp, a portal or a respawn", serverPos.distanceTo(move.to)));
        }
        else if (serverPos.distanceTo(move.from) > 0.5) {
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
            if (Compat.collides(entity, mc.player)) return entity.getName().getString() + " at " + fmt(Compat.pos(entity));
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
     * A search from {@code pos} towards the landing spot if the target is loaded, otherwise towards the farthest
     * loaded point on the line to the target. It's exhaustive when close to the landing spot or when
     * {@code exhaustive} is set. Returns null if there's nothing to search for yet.
     */
    private Search newSearch(Vec3d pos, boolean exhaustive) {
        if (!isLoaded(goal.getX(), goal.getZ())) {
            Vec3d waypoint = farthestLoadedOnLine(pos);
            if (horizontalDistance(pos, waypoint) >= MIN_SEGMENT) return new Search(pos, waypoint, null, exhaustive);

            // Not enough of the line is loaded yet, wait for the server to send more chunks
            if (hops.isEmpty() && pending.isEmpty()) {
                stallTicks += SEARCH_RETRY + 1;
                if (stallTicks > stuckTimeout) {
                    fail(String.format(Locale.ROOT, "No new chunks towards the target for %d seconds, giving up.", stuckTimeout / 20));
                    return null;
                }
            }
            searchCooldown = SEARCH_RETRY;
            return null;
        }

        if (landing == null || !standable(landing, false)) {
            landing = findLanding(goal);
            if (landing == null) {
                fail(String.format(Locale.ROOT, "No solid ground within (highlight)%d(default) blocks of the target.", landingRadius));
                return null;
            }

            info("Landing at (highlight)%.0f %.1f %.0f(default).", Math.floor(landing.x), landing.y, Math.floor(landing.z));
            // Progress is measured to the landing spot from now on
            bestRemaining = Double.MAX_VALUE;
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
        final double g;
        // Number of stops in a row in the air, 0 when standing on something
        final int airChain;
        // Whether the expensive checks already passed, otherwise they run when the node is picked
        final boolean verified;
        final double h, f;

        Node(BlockPos pos, Vec3d vec, Node parent, double g, int airChain, boolean verified, double h, double weight) {
            this.pos = pos;
            this.vec = vec;
            this.parent = parent;
            this.g = g;
            this.airChain = airChain;
            this.verified = verified;
            this.h = h;
            this.f = g + weight * h;
        }
    }

    // Directions for moves through the air: the 8 compass directions
    private static final double[][] AIR_DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {Math.sqrt(0.5), Math.sqrt(0.5)}, {Math.sqrt(0.5), -Math.sqrt(0.5)}, {-Math.sqrt(0.5), Math.sqrt(0.5)}, {-Math.sqrt(0.5), -Math.sqrt(0.5)}
    };

    /**
     * A* over spots to stand on, minimising the number of moves. Spread over several ticks so it doesn't freeze the
     * game. With no {@code target} (target unloaded) {@code goalVec} is a point on the straight line to the target and
     * it returns the route to the spot closest to it, staying near the line; with a target it returns the route to the
     * target, or to the closest ground spot if the target can't be reached.
     * <p>
     * Besides moves of up to one hop to ground spots, it uses vertical clips to every spot in the same column (with
     * unlimited vertical clips) and short chains of stops in mid-air: up to above everything nearby, across, and back
     * down. When exhaustive it isn't weighted (so the route found is the shortest) and doesn't give up early.
     */
    private class Search {
        final Vec3d goalVec;
        final BlockPos target;
        final boolean exhaustive;
        // Check every spot as it's found instead of when it's picked. Only with short moves: with long ones there are
        // thousands of spots per step and checking the way to each one at once freezes the game
        final boolean eager;
        final double weight;
        final int maxExpansions;
        final int bottomY, topY;
        final int version = planVersion;

        final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.<Node>comparingDouble(n -> n.f).thenComparingDouble(n -> n.h));
        final Long2DoubleOpenHashMap bestG = new Long2DoubleOpenHashMap();
        final LongOpenHashSet closed = new LongOpenHashSet();
        // Columns whose spots have already been added as vertical clips
        final LongOpenHashSet scannedColumns = new LongOpenHashSet();
        final BlockPos.Mutable mutable = new BlockPos.Mutable();

        final Node start;
        Node closest;
        int expansions, staleExpansions;
        List<Vec3d> result;

        Search(Vec3d from, Vec3d goalVec, BlockPos target, boolean exhaustive) {
            this.goalVec = goalVec;
            this.target = target;
            this.exhaustive = exhaustive;
            this.eager = exhaustive && maxHop <= NEAR;
            this.weight = exhaustive ? 1 : HEURISTIC_WEIGHT;
            this.maxExpansions = exhaustive ? MAX_EXHAUSTIVE_EXPANSIONS : MAX_EXPANSIONS;
            this.bottomY = mc.world.getBottomY();
            this.topY = bottomY + mc.world.getHeight();
            bestG.defaultReturnValue(Double.MAX_VALUE);

            start = new Node(BlockPos.ofFloored(from), from, null, 0, 0, true, heuristic(from), weight);
            closest = start;
            open.add(start);
        }

        double heuristic(Vec3d vec) {
            if (target == null) {
                // Until we know where we're landing only the horizontal distance matters, plus how far off the line we are
                return (horizontalDistance(vec, goalVec) + LINE_DEVIATION_WEIGHT * distanceFromLine(vec)) / maxHop;
            }

            if (!unlimitedVertical) return vec.distanceTo(goalVec) / maxHop;

            // A vertical clip of any length is a single move
            double dy = Math.abs(vec.y - goalVec.y);
            return horizontalDistance(vec, goalVec) / maxHop + (dy > 0.5 ? Math.min(1, dy / maxHop) : 0);
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

                // The expensive checks (can the player stand there, is the way clear) only run on spots that are
                // actually picked, instead of on every spot within reach of every step
                if (!node.verified && (!isSpot(node.vec) || !canMove(node.parent.vec, node.vec))) {
                    bestG.remove(key);
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

        void add(Vec3d vec, Node parent, int airChain, boolean verified) {
            if (rejectedStops.contains(vec)) return;
            BlockPos pos = BlockPos.ofFloored(vec);
            long key = pos.asLong();
            double g = parent.g + 1 + (airChain > 0 ? AIR_STOP_COST : 0);
            if (bestG.get(key) <= g || closed.contains(key)) return;

            bestG.put(key, g);
            open.add(new Node(pos, vec, parent, g, airChain, verified, heuristic(vec), weight));
        }

        void expand(Node node) {
            double vx = node.vec.x, vy = node.vec.y, vz = node.vec.z;
            int nx = MathHelper.floor(vx), nz = MathHelper.floor(vz);

            // Ground spots within one move: every column nearby and a grid of them further out
            for (int[] column : columns) {
                int x = nx + column[0], z = nz + column[1];
                double hx = x + 0.5 - vx, hz = z + 0.5 - vz;
                double horizontalSq = hx * hx + hz * hz;
                if (horizontalSq > maxHopSq || horizontalSq < 1.0E-6 || !isLoaded(x, z)) continue;

                // With long moves only heights near the current one and the top of the column, vertical clips get the rest
                double dyMax = Math.sqrt(maxHopSq - horizontalSq);
                boolean clipped = dyMax > NEAR;
                if (clipped) dyMax = NEAR;
                int minY = Math.max(bottomY, MathHelper.floor(vy - dyMax) - 1);
                int maxY = Math.min(topY - 1, MathHelper.floor(vy + dyMax));

                for (int y = minY; y <= maxY; y++) addGround(node, x, y, z);
                if (clipped) {
                    int surface = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
                    if (surface < minY || surface > maxY) addGround(node, x, surface, z);
                }
            }

            verticalClips(node);
            airStops(node);
        }

        void addGround(Node node, int x, int y, int z) {
            double standY = standHeight(x, y, z);
            if (Double.isNaN(standY)) return;

            Vec3d vec = new Vec3d(x + 0.5, standY, z + 0.5);
            if (node.vec.squaredDistanceTo(vec) > maxHopSq) return;
            if (eager && (!isSpot(vec) || !canMove(node.vec, vec))) return;
            add(vec, node, 0, eager);
        }

        /**
         * Spots straight above and below. With unlimited vertical clips that's every spot in the column, which only
         * has to be scanned once per search.
         */
        void verticalClips(Node node) {
            double vy = node.vec.y;
            int x = MathHelper.floor(node.vec.x), z = MathHelper.floor(node.vec.z);

            int minY, maxY;
            // Whole columns near the target, when stuck, and to get down from the air; long trips stay fast
            boolean wholeColumn = unlimitedVertical && (exhaustive || target != null || node.airChain > 0);
            if (wholeColumn) {
                if (!scannedColumns.add(31 * Double.doubleToLongBits(node.vec.x) ^ Double.doubleToLongBits(node.vec.z))) return;
                minY = bottomY;
                maxY = topY - 1;
            }
            else {
                minY = Math.max(bottomY, MathHelper.floor(vy - maxHop) - 1);
                maxY = Math.min(topY - 1, MathHelper.floor(vy + maxHop));
            }

            for (int y = minY; y <= maxY; y++) {
                double standY = standHeight(x, y, z);
                if (Double.isNaN(standY) || Math.abs(standY - vy) < 0.5) continue;
                if (!wholeColumn && Math.abs(standY - vy) > maxHop) continue;

                // Exactly above or below, so the move is a pure vclip
                Vec3d vec = new Vec3d(node.vec.x, standY, node.vec.z);
                add(vec, node, 0, false);
            }
        }

        /**
         * Short chains of stops in mid-air: straight up to above everything nearby (or to the target's height), across
         * at that height, and back down onto ground via {@link #verticalClips} or a regular move.
         */
        void airStops(Node node) {
            if (node.airChain >= maxAirStops) return;
            int chain = node.airChain + 1;

            // Up or down to a useful height in the same column
            List<Double> heights = new ArrayList<>();
            double cruise = clearHeight(node.vec);
            if (cruise > node.vec.y + 0.5 && (unlimitedVertical || cruise - node.vec.y <= maxHop)) heights.add(cruise);
            if (target != null && (unlimitedVertical || Math.abs(goalVec.y - node.vec.y) <= maxHop)) heights.add(goalVec.y);
            if (!unlimitedVertical) {
                heights.add(node.vec.y + maxHop);
                heights.add(node.vec.y - maxHop);
            }

            for (double y : heights) {
                if (y < bottomY || y > topY + 2 || Math.abs(y - node.vec.y) < 0.5) continue;
                Vec3d vec = new Vec3d(node.vec.x, y, node.vec.z);
                if (canStop(vec)) add(vec, node, isSpot(vec) ? 0 : chain, true);
            }

            // Across at the same height, but only from a stop that's already in the air
            if (node.airChain == 0) return;

            List<Vec3d> moves = new ArrayList<>();
            for (double[] dir : AIR_DIRECTIONS) moves.add(new Vec3d(dir[0] * maxHop, 0, dir[1] * maxHop));

            // Straight towards the goal, stopping right above it if it's in reach
            double gx = goalVec.x - node.vec.x, gz = goalVec.z - node.vec.z;
            double gl = Math.sqrt(gx * gx + gz * gz);
            if (gl > 1.0E-3) {
                double length = Math.min(maxHop, gl);
                moves.add(new Vec3d(gx / gl * length, 0, gz / gl * length));
            }

            for (Vec3d offset : moves) {
                Vec3d vec = node.vec.add(offset);
                if (!isLoaded(MathHelper.floor(vec.x), MathHelper.floor(vec.z)) || !canStop(vec) || !canMove(node.vec, vec)) continue;
                add(vec, node, isSpot(vec) ? 0 : chain, true);
            }
        }

        /**
         * A height in the node's column above every block within one move, so moves across at that height are clear.
         */
        double clearHeight(Vec3d vec) {
            int x0 = MathHelper.floor(vec.x), z0 = MathHelper.floor(vec.z);
            int top = MathHelper.floor(vec.y);

            for (int[] column : columns) {
                int x = x0 + column[0], z = z0 + column[1];
                if (!isLoaded(x, z)) continue;
                top = Math.max(top, mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z));
            }

            return top + 0.1;
        }

        boolean finish(Node end) {
            result = new ArrayList<>();
            if (end.h * maxHop > start.h * maxHop - MIN_PROGRESS && !end.pos.equals(target)) return true;

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
        if (delta.x * delta.x + delta.z * delta.z < 1.0E-6) return true;

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
     * Whether the player can stand at this spot on the way (water is fine), cached for a few seconds. The landing spot
     * has to be dry.
     */
    private boolean isSpot(Vec3d feet) {
        if (feet.equals(landing)) return standable(feet, false);

        if (spotCache.containsKey(feet)) return spotCache.getBoolean(feet);

        boolean spot = standable(feet, true);
        spotCache.put(feet, spot);
        return spot;
    }

    /**
     * Feet height of the spot to stand on in block {@code x y z}, or NaN if there's none: on top of a full block below
     * (or a fence or wall, 1.5 tall), or on top of a partial block in this position, like mud, soul sand, farmland,
     * slabs and snow layers. Only looks at the blocks, the player's hitbox is checked separately. Cached.
     */
    private double standHeight(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        if (standCache.containsKey(key)) return standCache.get(key);

        double height = computeStandHeight(x, y, z);
        standCache.put(key, height);
        return height;
    }

    private double computeStandHeight(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (mc.world.isOutOfHeightLimit(pos.down())) return Double.NaN;

        BlockState feet = mc.world.getBlockState(pos);
        if (feet.getFluidState().isIn(FluidTags.LAVA)) return Double.NaN;

        VoxelShape feetShape = feet.getCollisionShape(mc.world, pos);
        if (!feetShape.isEmpty()) {
            double top = feetShape.getMax(Direction.Axis.Y);
            return top > 0 && top < 1 ? y + top : Double.NaN;
        }

        BlockPos below = pos.down();
        VoxelShape belowShape = mc.world.getBlockState(below).getCollisionShape(mc.world, below);
        if (belowShape.isEmpty()) return Double.NaN;

        // Partial blocks below are handled as their own position
        double top = belowShape.getMax(Direction.Axis.Y);
        return top >= 1 ? below.getY() + top : Double.NaN;
    }

    /**
     * Nearest spot to {@code center} where the player fits and stands on solid ground, out of water, searching
     * outwards in cubic shells until no closer spot is possible.
     */
    private Vec3d findLanding(BlockPos center) {
        Vec3d wanted = Vec3d.ofBottomCenter(center);
        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;

        for (int r = 0; r <= landingRadius; r++) {
            // Every spot in this shell is at least r - 1 blocks away
            if ((double) (r - 1) * (r - 1) > bestDist) break;

            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    boolean onFace = Math.abs(x) == r || Math.abs(y) == r;
                    for (int z = -r; z <= r; z += onFace ? 1 : Math.max(1, 2 * r)) {
                        int px = center.getX() + x, py = center.getY() + y, pz = center.getZ() + z;
                        if (!isLoaded(px, pz)) continue;

                        double standY = standHeight(px, py, pz);
                        if (Double.isNaN(standY)) continue;

                        Vec3d spot = new Vec3d(px + 0.5, standY, pz + 0.5);
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
        return mc.player.getBoundingBox().offset(feet.subtract(Compat.pos(mc.player)));
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        return Math.sqrt(MathHelper.square(a.x - b.x) + MathHelper.square(a.z - b.z));
    }

    private boolean isLoaded(int x, int z) {
        return mc.world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }
}
