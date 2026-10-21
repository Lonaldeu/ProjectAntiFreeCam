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
    private static final int FAR_EVERY = 5; // the far pass runs every this many checks

    /** What the visibility pass knows about one viewer. */
    private static final class Viewer {
        final Set<UUID> hidden = ConcurrentHashMap.newKeySet(); // entities we hid via the API
        final Set<UUID> droppedSpawns = ConcurrentHashMap.newKeySet(); // reported from netty threads
        final Map<UUID, Long> keepInTab = new ConcurrentHashMap<>(); // player -> until when to keep in the tab list
        final Set<UUID> listed = ConcurrentHashMap.newKeySet(); // players we hid but kept in the tab list
        final Set<UUID> unlisted = ConcurrentHashMap.newKeySet(); // players we hid and took off it: another plugin hides them too
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
     * A player left: forget them as a viewer, and take them off the tab list of everyone who still listed them while
     * they were hidden. The server only tells players who can see them that they left.
     */
    void quit(UUID player) {
        viewers.remove(player);
        Nms nms = plugin.nms;
        viewers.forEach((viewerId, v) -> {
            v.hidden.remove(player);
            v.droppedSpawns.remove(player);
            v.unlisted.remove(player);
            if (!v.listed.remove(player)) return;
            v.keepInTab.remove(player);
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) nms.send(viewer, nms.playerInfoRemove(List.of(player)));
        });
    }

    /**
     * Shows everything this plugin hid, so nothing stays hidden once it is disabled. (Paper allows showing, not
     * hiding, from onDisable, so spawns dropped in the last couple of ticks stay unsent until they come back in range.)
     */
    void showAll() {
        Nms nms = plugin.nms;
        viewers.forEach((viewerId, v) -> {
            Player p = Bukkit.getPlayer(viewerId);
            if (p == null) return;
            for (UUID id : v.hidden) {
                try {
                    Entity e = Bukkit.getEntity(id);
                    if (e == null) continue;
                    if (e.isVisibleByDefault()) p.showEntity(plugin, e); // for one hidden by default, a show would show it
                    // Another plugin still hides them, so the show sent nothing: off the tab list we kept them on.
                    if (v.listed.contains(id) && !p.canSee(e)) nms.send(p, nms.playerInfoRemove(List.of(id)));
                } catch (RuntimeException ignored) { // e.g. an entity of another Folia region at shutdown
                }
            }
            for (UUID id : v.droppedSpawns) { // still paired by the server, but the client never got their spawn
                try {
                    Entity e = Bukkit.getEntity(id);
                    if (e != null) nms.resendEntity(p, e);
                } catch (RuntimeException ignored) { // as above
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
        boolean heightHidden = depth && view.maxHiddenY() >= view.minY();
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
        double h = heightHidden ? world.getMaxHeight() - world.getMinHeight() : r;
        Set<UUID> seen = new HashSet<>();
        List<Integer> hiddenIds = new ArrayList<>();
        List<Location> nearbyAt = new ArrayList<>();
        List<Boolean> nearbyHidden = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(r, h, r)) {
            if (!plugin.trackedTypes.contains(e.getType())) continue;
            UUID id = e.getUniqueId();
            seen.add(id);
            Location at = e.getLocation();
            boolean hide = depth && at.getY() <= view.maxHiddenY();
            if (!e.isVisibleByDefault()) {
                depthOnly(player, v, e, hide);
                continue;
            }
            boolean spawnWasDropped = v.droppedSpawns.remove(id);
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
            if (hide) hiddenIds.add(e.getEntityId());
            apply(player, v, e, hide, spawnWasDropped);
        }
        v.sight.keySet().retainAll(seen);

        v.hiddenIds = hiddenIds.stream().mapToInt(Integer::intValue).toArray();
        double[] positions = new double[nearbyAt.size() * 4];
        for (int i = 0; i < nearbyAt.size(); i++) {
            positions[4 * i] = nearbyAt.get(i).getX();
            positions[4 * i + 1] = nearbyAt.get(i).getY();
            positions[4 * i + 2] = nearbyAt.get(i).getZ();
            positions[4 * i + 3] = nearbyHidden.get(i) ? 1 : 0;
        }
        v.positions = positions;

        if (check % FAR_EVERY == 0) {
            // Also without a hidden range, as long as something out there may still be hidden: to show it again.
            if (heightHidden || v.hidden.size() > hiddenIds.size())
                farPass(player, v, world, heightHidden ? view.maxHiddenY() : Integer.MIN_VALUE, seen);
            // Entities that left range stay marked until seen again: the server keeps hiding them too. Only the gone
            // ones are dropped, as the server forgets those. shortcut: rescans while over 1024 live ones are hidden
            if (v.hidden.size() > 1024) {
                try {
                    v.hidden.removeIf(id -> !seen.contains(id) && Bukkit.getEntity(id) == null);
                } catch (RuntimeException e) { // e.g. Folia refusing a lookup from this thread: try again next time
                    if (plugin.debug) plugin.getLogger().warning("Could not prune hidden entities for " + player.getName() + ": " + e);
                }
            }
        }
        settleDroppedSpawns(player, v, world, depth ? view : null, seen);
        for (UUID id : v.listed) syncTab(player, v, id);
        for (UUID id : v.unlisted) syncTab(player, v, id);
    }

    /**
     * Entities farther away than max-distance that the server still sends (it tracks some types, such as displays,
     * up to the player's view distance): only the hidden range (at or below {@code cut}) applies to them. Every few
     * checks, as it covers far more ground.
     */
    private void farPass(Player player, Viewer v, World world, int cut, Set<UUID> seen) {
        double far = Math.max(world.getViewDistance(), player.getSendViewDistance()) * 16;
        if (far <= plugin.losMaxDistance) return;
        try {
            for (Entity e : player.getNearbyEntities(far, world.getMaxHeight() - world.getMinHeight(), far)) {
                UUID id = e.getUniqueId();
                if (!plugin.trackedTypes.contains(e.getType()) || !seen.add(id)) continue;
                boolean hide = e.getLocation().getY() <= cut;
                if (!e.isVisibleByDefault()) depthOnly(player, v, e, hide);
                // Most types aren't sent this far at all: only hide what the server sends this player.
                else if (!hide || v.hidden.contains(id) || v.droppedSpawns.contains(id) || e.getTrackedBy().contains(player))
                    apply(player, v, e, hide, v.droppedSpawns.remove(id));
            }
        } catch (RuntimeException e) { // e.g. entities of another Folia region: the next far pass tries again
            if (plugin.debug) plugin.getLogger().warning("Far entity check failed for " + player.getName() + ": " + e);
        }
    }

    /**
     * Dropped spawns of entities no pass looked at (beyond the view distance, or seen by none yet): those still in
     * the hidden range get hidden through the server too, which otherwise keeps sending their movement and so their
     * position; the others are sent. Entities seen this check were handled already.
     */
    private void settleDroppedSpawns(Player player, Viewer v, World world, View view, Set<UUID> seen) {
        for (UUID id : v.droppedSpawns) {
            if (seen.contains(id)) continue;
            try {
                Entity e = Bukkit.getEntity(id);
                if (e == null || !e.getWorld().equals(world)) {
                    v.droppedSpawns.remove(id);
                    continue;
                }
                boolean hide = view != null && e.getLocation().getY() <= view.maxHiddenY();
                if (!e.isVisibleByDefault()) {
                    depthOnly(player, v, e, hide);
                    if (!hide) v.droppedSpawns.remove(id); // sent again, or not possible on this version
                } else {
                    v.droppedSpawns.remove(id);
                    apply(player, v, e, hide, true);
                }
            } catch (RuntimeException e) { // e.g. owned by another Folia region: try again next time
                if (plugin.debug) plugin.getLogger().warning("Could not settle a dropped spawn for " + player.getName() + ": " + e);
            }
        }
        if (v.droppedSpawns.size() > 1024) v.droppedSpawns.clear(); // only if settling keeps failing
    }

    /** Makes what the server sends this viewer match {@code hide}; a dropped spawn is sent again once it may be seen. */
    private void apply(Player player, Viewer v, Entity e, boolean hide, boolean spawnWasDropped) {
        UUID id = e.getUniqueId();
        if (hide) {
            v.hidden.add(id);
            // Also when already marked: the server forgets its own hide when the entity unloads, changes world or
            // relogs, and then sends it again.
            if (player.canSee(e)) hideKeepingTab(player, v, e);
        } else if (spawnWasDropped) { // the client never got this entity: have the server send it now
            if (v.hidden.remove(id)) player.showEntity(plugin, e);
            if (!plugin.nms.resendEntity(player, e)) { // without the server's tracker, hiding and showing does it
                hideKeepingTab(player, v, e);
                player.showEntity(plugin, e);
            }
        } else if (v.hidden.remove(id)) {
            player.showEntity(plugin, e);
        }
    }

    /**
     * An entity another plugin shows to some players only. Hiding it through the server would change who sees it,
     * so line of sight leaves it alone, but the hidden range applies: the packet filter holds back its spawn and every
     * update about it, one already on the client is taken off it through the server's entity tracker, and it is sent
     * again once it may be seen. droppedSpawns holds it meanwhile.
     */
    private void depthOnly(Player player, Viewer v, Entity e, boolean hide) {
        UUID id = e.getUniqueId();
        if (hide) {
            if (!v.droppedSpawns.contains(id) && player.canSee(e) && plugin.nms.untrackEntity(player, e)) v.droppedSpawns.add(id);
        } else if (v.droppedSpawns.contains(id) && plugin.nms.resendEntity(player, e)) {
            v.droppedSpawns.remove(id);
        }
    }

    /**
     * A player we hide stays in the viewer's tab list, unless another plugin (a vanish plugin) hides them too: then
     * they come off it, and back on once only we hide them.
     */
    private void syncTab(Player viewer, Viewer v, UUID id) {
        Player other = Bukkit.getPlayer(id);
        if (other == null) { // left: quit() took care of the tab list
            v.listed.remove(id);
            v.unlisted.remove(id);
            return;
        }
        Nms nms = plugin.nms;
        boolean others = other.isVisibleByDefault() ? nms.hiddenByOthers(viewer, other, plugin) : !viewer.canSee(other);
        if (!v.hidden.contains(id)) {
            v.unlisted.remove(id);
            // Shown by us but another plugin hides them, so the server sent nothing: off the tab list we kept them on.
            if (v.listed.remove(id) && others) unlist(viewer, v, id);
        } else if (others) {
            if (!v.listed.remove(id)) return;
            unlist(viewer, v, id);
            v.unlisted.add(id);
        } else if (v.unlisted.remove(id)) {
            Object add = nms.playerInfoAdd(viewer, other);
            if (add != null) nms.send(viewer, add);
            v.listed.add(id);
        }
    }

    private void unlist(Player viewer, Viewer v, UUID id) {
        v.keepInTab.remove(id); // so the filter lets this removal through
        plugin.nms.send(viewer, plugin.nms.playerInfoRemove(List.of(id)));
    }

    private static long blockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private static boolean exempt(Entity e, Entity vehicle, List<Entity> passengers) {
        return e.isGlowing() || e.equals(vehicle) || passengers.contains(e);
    }

    /** Hides {@code e}; a player stays in the tab list when the hide makes the server take them off it. */
    private void hideKeepingTab(Player player, Viewer v, Entity e) {
        if (e instanceof Player && player.canSee(e) && plugin.nms.playerInfoRemovePacket != null) {
            v.keepInTab.put(e.getUniqueId(), System.currentTimeMillis() + TAB_KEEP_MS);
            v.listed.add(e.getUniqueId());
        }
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
