package me.lonaldeu.antifreecam;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides, every few ticks per viewer, which nearby entities of the listed types the viewer may see, and hides the
 * rest with the server's per-player hide/show API (which re-sends everything on show). An entity is hidden when
 * <ul>
 * <li>it is in the viewer's hidden range (at or below their cut), or</li>
 * <li>line-of-sight culling applies and no ray from the viewer's eyes, now or where they will be by the next check,
 * reaches its centre, head or hitbox corners without passing through a full solid block (anti-ESP).</li>
 * </ul>
 * Because it re-checks continuously, entities that cross the cut after spawning are caught too. Spawns the packet
 * filter dropped are reported here, so they get sent once the entity becomes visible. Hidden entities' ids and
 * positions are published for the packet filter, which drops sounds and particles that would give them away.
 */
final class EntityVisibility {

    private static final long TAB_KEEP_MS = 1000;
    private static final double MAX_PREDICTED_STEP = 3; // blocks per check; more is a teleport, not movement
    // Something that was in sight is hidden only after this many checks out of sight, so walking past pillars
    // doesn't despawn and respawn it constantly. New arrivals behind a wall are hidden at once.
    private static final int HIDE_AFTER_CHECKS = 3;
    // A line-of-sight answer is reused while neither side changed block: "in sight" for this many checks (twice as
    // long when far); "out of sight" is re-checked every time up close, so an opened wall shows what's behind at once.
    private static final int REUSE_CHECKS = 4;
    private static final double FAR = 32;
    private static final double GIVEAWAY_RADIUS = 2; // a sound this close to an entity is probably its own

    /** What the visibility pass knows about one viewer. */
    private static final class Viewer {
        final Set<UUID> hidden = ConcurrentHashMap.newKeySet(); // entities we hid via the API
        final Set<UUID> droppedSpawns = ConcurrentHashMap.newKeySet(); // reported from netty threads
        final Map<UUID, Long> keepInTab = new ConcurrentHashMap<>(); // player -> until when to keep in the tab list
        final Map<UUID, Sight> sight = new HashMap<>(); // viewer's own thread only
        volatile int[] hiddenIds = new int[0]; // read by netty threads
        volatile double[] positions = new double[0]; // x, y, z, hidden (1 or 0) per nearby entity; read by netty threads
        Vector lastEye;
        int check, tick;
    }

    /** The last line-of-sight answer for one entity, and what it was computed from. */
    private static final class Sight {
        long viewerBlock, entityBlock;
        int computedAt; // 0 = never
        boolean clear, wasInSight;
        int checksOutOfSight;
    }

    private final AntiFreeCam plugin;
    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();

    EntityVisibility(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    private Viewer viewer(UUID id) {
        return viewers.computeIfAbsent(id, k -> new Viewer());
    }

    /** Runs every tick and checks every check-interval-ticks, so a changed interval applies to everyone at once. */
    void start(Player player) {
        Viewer v = viewer(player.getUniqueId());
        v.tick = ThreadLocalRandom.current().nextInt(1 << 16); // spread viewers over ticks
        player.getScheduler().runAtFixedRate(plugin, t -> {
            if (++v.tick % plugin.losInterval != 0) return;
            try {
                check(player, v);
            } catch (RuntimeException e) { // e.g. an entity owned by another Folia region; try again next time
                if (plugin.debug) plugin.getLogger().warning("Entity visibility check failed for " + player.getName() + ": " + e);
            }
        }, null, 1, 1);
    }

    /** Called from netty threads when the packet filter drops an entity's spawn for {@code viewer}. */
    void spawnDropped(UUID viewer, UUID entity) {
        viewer(viewer).droppedSpawns.add(entity);
    }

    /** Whether a tab-list removal of {@code player} sent to {@code viewer} was caused by our own hide. Netty-thread safe. */
    boolean shouldKeepInTab(UUID viewer, UUID player) {
        Viewer v = viewers.get(viewer);
        Long until = v == null ? null : v.keepInTab.remove(player);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Whether a sound or particle packet would give away an entity hidden from {@code viewer}: an entity sound of a
     * hidden entity, or a positional one whose nearest entity (within a couple of blocks) is hidden. Netty-thread safe.
     */
    boolean givesAwayHidden(UUID viewer, Object packet) {
        Viewer v = viewers.get(viewer);
        if (v == null || v.hiddenIds.length == 0) return false;
        Integer id = plugin.nms.soundEntityId(packet);
        if (id != null) {
            for (int hidden : v.hiddenIds) if (hidden == id) return true;
            return false;
        }
        double[] at = plugin.nms.soundOrParticlePosition(packet);
        if (at == null) return false;
        double[] p = v.positions;
        double nearest = GIVEAWAY_RADIUS * GIVEAWAY_RADIUS;
        boolean hidden = false;
        for (int i = 0; i < p.length; i += 4) {
            double dx = p[i] - at[0], dy = p[i + 1] - at[1], dz = p[i + 2] - at[2], d = dx * dx + dy * dy + dz * dz;
            if (d <= nearest) {
                nearest = d;
                hidden = p[i + 3] != 0;
            }
        }
        return hidden;
    }

    /**
     * A player left: forget them as a viewer, and take them off the tab list of everyone they were hidden from. The
     * server only tells players who can see them that they left, and hidden ones were kept in the tab list.
     */
    void quit(UUID player) {
        viewers.remove(player);
        Nms nms = plugin.nms;
        viewers.forEach((viewerId, v) -> {
            v.droppedSpawns.remove(player);
            if (!v.hidden.remove(player)) return;
            v.keepInTab.remove(player);
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null && nms.playerInfoRemovePacket != null) nms.send(viewer, nms.playerInfoRemove(List.of(player)));
        });
    }

    /**
     * Shows everything this plugin hid, so nothing stays hidden once it is disabled. (Paper allows showing, not
     * hiding, from onDisable, so spawns dropped in the last couple of ticks stay unsent until they come back in range.)
     */
    void showAll() {
        viewers.forEach((viewerId, v) -> {
            Player p = Bukkit.getPlayer(viewerId);
            if (p == null) return;
            for (UUID id : v.hidden) {
                try {
                    Entity e = Bukkit.getEntity(id);
                    if (e != null) p.showEntity(plugin, e);
                } catch (RuntimeException ignored) { // e.g. an entity of another Folia region at shutdown
                }
            }
        });
        viewers.clear();
    }

    private void check(Player player, Viewer v) {
        int check = ++v.check;
        World world = player.getWorld();
        View view = plugin.views.get(player.getUniqueId());
        boolean depth = plugin.hideEntities && view != null;
        boolean sightRule = plugin.lineOfSight && plugin.worlds.contains(world.getName())
                && player.getGameMode() != GameMode.SPECTATOR && !player.hasPermission("antifreecam.bypass");
        Vector eye = player.getEyeLocation().toVector();
        Map<Long, Boolean> blocks = new HashMap<>(); // rays to nearby entities cross the same blocks: look each up once
        Raycast.Solid solid = (x, y, z) -> blocks.computeIfAbsent(blockKey(x, y, z), k -> world.getType(x, y, z).isOccluding());
        Vector predicted = sightRule ? predictEye(player, v, solid, eye) : null;
        long eyeBlock = blockKey(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ());
        Entity vehicle = player.getVehicle();
        List<Entity> passengers = player.getPassengers();

        double r = plugin.losMaxDistance;
        // The server sends entities at any height, so with something height-hidden the whole height is checked.
        double h = depth && view.maxHiddenY() >= view.minY() ? world.getMaxHeight() - world.getMinHeight() : r;
        Set<UUID> seen = new HashSet<>();
        List<Integer> hiddenIds = new ArrayList<>();
        List<Location> nearbyAt = new ArrayList<>();
        List<Boolean> nearbyHidden = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(r, h, r)) {
            // Who sees an entity hidden by default is another plugin's call: showing it would show it to everyone.
            if (!plugin.trackedTypes.contains(e.getType()) || !e.isVisibleByDefault()) continue;
            UUID id = e.getUniqueId();
            seen.add(id);
            boolean spawnWasDropped = v.droppedSpawns.remove(id);
            Location at = e.getLocation();
            boolean hide = depth && at.getY() <= view.maxHiddenY();
            if (hide) v.sight.remove(id); // out of the picture: start fresh when it comes back above the cut
            else if (sightRule && Math.abs(at.getY() - eye.getY()) <= r && !exempt(e, vehicle, passengers)) {
                Sight s = v.sight.computeIfAbsent(id, k -> new Sight());
                long entityBlock = blockKey(at.getBlockX(), at.getBlockY(), at.getBlockZ());
                boolean far = at.toVector().distanceSquared(eye) > FAR * FAR;
                int maxAge = s.clear ? (far ? 2 * REUSE_CHECKS : REUSE_CHECKS) : (far ? 2 : 1);
                if (s.computedAt == 0 || s.viewerBlock != eyeBlock || s.entityBlock != entityBlock || check - s.computedAt >= maxAge) {
                    BoundingBox box = e.getBoundingBox();
                    s.clear = canSee(solid, eye, box) || (predicted != null && canSee(solid, predicted, box));
                    s.viewerBlock = eyeBlock;
                    s.entityBlock = entityBlock;
                    s.computedAt = check;
                }
                if (s.clear) {
                    s.wasInSight = true;
                    s.checksOutOfSight = 0;
                } else {
                    s.checksOutOfSight++;
                    hide = !s.wasInSight || s.checksOutOfSight >= HIDE_AFTER_CHECKS;
                }
            }
            nearbyAt.add(at);
            nearbyHidden.add(hide);
            if (hide) {
                v.hidden.add(id);
                // Also when already marked: the server forgets its own hide when the entity unloads, changes world or
                // relogs, and then sends it again.
                if (player.canSee(e)) hideKeepingTab(player, v, e);
                hiddenIds.add(e.getEntityId());
            } else if (spawnWasDropped) { // the client never got this entity: have the server send it now
                v.hidden.remove(id);
                hideKeepingTab(player, v, e);
                player.showEntity(plugin, e);
            } else if (v.hidden.remove(id)) {
                player.showEntity(plugin, e);
            }
        }
        v.sight.keySet().retainAll(seen);
        // Entities that left range stay marked until seen again: the server keeps hiding them too. Only the gone ones
        // are dropped, as the server forgets those. shortcut: rescans every check while over 1024 live ones are hidden
        if (v.hidden.size() > 1024) {
            try {
                v.hidden.removeIf(id -> !seen.contains(id) && Bukkit.getEntity(id) == null);
            } catch (RuntimeException e) { // e.g. Folia refusing a lookup from this thread: try again next time
                if (plugin.debug) plugin.getLogger().warning("Could not prune hidden entities for " + player.getName() + ": " + e);
            }
        }
        settleDroppedSpawns(player, v, world, depth ? view : null, seen);

        v.hiddenIds = hiddenIds.stream().mapToInt(Integer::intValue).toArray();
        double[] positions = new double[nearbyAt.size() * 4];
        for (int i = 0; i < nearbyAt.size(); i++) {
            positions[4 * i] = nearbyAt.get(i).getX();
            positions[4 * i + 1] = nearbyAt.get(i).getY();
            positions[4 * i + 2] = nearbyAt.get(i).getZ();
            positions[4 * i + 3] = nearbyHidden.get(i) ? 1 : 0;
        }
        v.positions = positions;
    }

    /**
     * Dropped spawns of entities the pass didn't look at (farther away than max-distance): those still in the hidden
     * range get hidden through the server too, which otherwise keeps sending their movement and so their position;
     * the others are sent. Entities seen this check wait for the next one, which knows about line of sight.
     */
    private void settleDroppedSpawns(Player player, Viewer v, World world, View view, Set<UUID> seen) {
        for (UUID id : v.droppedSpawns) {
            if (seen.contains(id)) continue;
            try {
                Entity e = Bukkit.getEntity(id);
                if (e != null && e.isVisibleByDefault() && e.getWorld().equals(world)) {
                    if (view != null && e.getLocation().getY() <= view.maxHiddenY()) {
                        v.hidden.add(id);
                        if (player.canSee(e)) hideKeepingTab(player, v, e);
                    } else {
                        v.hidden.remove(id);
                        hideKeepingTab(player, v, e);
                        player.showEntity(plugin, e);
                    }
                }
                v.droppedSpawns.remove(id);
            } catch (RuntimeException e) { // e.g. owned by another Folia region: try again next time
                if (plugin.debug) plugin.getLogger().warning("Could not settle a dropped spawn for " + player.getName() + ": " + e);
            }
        }
        if (v.droppedSpawns.size() > 1024) v.droppedSpawns.clear(); // only if settling keeps failing
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private static boolean exempt(Entity e, Entity vehicle, List<Entity> passengers) {
        return e.isGlowing() || e.equals(vehicle) || passengers.contains(e);
    }

    /** Hides {@code e}; a player stays in the tab list when the hide makes the server take them off it. */
    private void hideKeepingTab(Player player, Viewer v, Entity e) {
        if (e instanceof Player && player.canSee(e)) v.keepInTab.put(e.getUniqueId(), System.currentTimeMillis() + TAB_KEEP_MS);
        player.hideEntity(plugin, e);
    }

    /**
     * Where the viewer's eyes will be by the time their client shows the result of the next check: the movement
     * since the last check, carried forward over the check interval plus their ping. Cuts pop-in when rounding a
     * corner. Not used when a solid block lies on the way, so pushing into a wall never lets anyone see through it.
     */
    private Vector predictEye(Player player, Viewer v, Raycast.Solid solid, Vector eye) {
        Vector last = v.lastEye;
        v.lastEye = eye;
        if (last == null) return null;
        Vector step = eye.clone().subtract(last);
        double length = step.length();
        if (length < 0.01 || length > MAX_PREDICTED_STEP) return null;
        double ahead = 1 + Math.min(player.getPing(), 500) / 50.0 / plugin.losInterval;
        Vector predicted = eye.clone().add(step.multiply(ahead));
        boolean blocked = solid.at(predicted.getBlockX(), predicted.getBlockY(), predicted.getBlockZ())
                || !Raycast.clear(eye.getX(), eye.getY(), eye.getZ(), predicted.getX(), predicted.getY(), predicted.getZ(), solid);
        return blocked ? null : predicted;
    }

    private boolean canSee(Raycast.Solid solid, Vector eye, BoundingBox box) {
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        double near = plugin.losAlwaysVisible;
        if (box.getCenter().distanceSquared(eye) <= near * near) return true;
        double inset = 0.05;
        double minX = box.getMinX() + inset, minY = box.getMinY() + inset, minZ = box.getMinZ() + inset;
        double maxX = box.getMaxX() - inset, maxY = box.getMaxY() - inset, maxZ = box.getMaxZ() - inset;
        if (Raycast.clear(ex, ey, ez, box.getCenterX(), box.getCenterY(), box.getCenterZ(), solid)) return true;
        if (Raycast.clear(ex, ey, ez, box.getCenterX(), maxY, box.getCenterZ(), solid)) return true;
        for (double x : new double[]{minX, maxX})
            for (double y : new double[]{minY, maxY})
                for (double z : new double[]{minZ, maxZ})
                    if (Raycast.clear(ex, ey, ez, x, y, z, solid)) return true;
        return false;
    }
}
