package me.lonaldeu.antifreecam;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
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
        final Map<Integer, UUID> held = new ConcurrentHashMap<>(); // per-player entities kept off this client, by entity id
        final Map<UUID, Sight> sight = new HashMap<>(); // viewer's own thread only
        volatile int[] hiddenIds = new int[0]; // read by netty threads
        volatile double[] positions = new double[0]; // x, y, z, hidden (1 or 0) per nearby entity; read by netty threads
        volatile Set<Integer> onServer = Set.of(); // ids of listed entities the last check found on the server; netty reads
        volatile Set<Integer> farOnServer = Set.of(); // the same from the last far pass, beyond the near box
        final Set<Integer> shownShots = ConcurrentHashMap.newKeySet(); // projectiles of hidden owners found in sight
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

    /** Whether {@code entity} (an entity id) is held back from {@code viewer}'s client. Netty-thread safe. */
    boolean isHeld(UUID viewer, int entity) {
        Viewer v = viewers.get(viewer);
        return v != null && v.held.containsKey(entity);
    }

    /**
     * Whether the last check found {@code entity} (an entity id) on the server near {@code viewer}: not one that exists
     * only in another plugin's packets, which can't be sent again once taken off a client. Netty-thread safe.
     */
    boolean onServer(UUID viewer, int entity) {
        Viewer v = viewers.get(viewer);
        return v != null && (v.onServer.contains(entity) || v.farOnServer.contains(entity));
    }

    /** Whether {@code entity} (an entity id, 0 for none) was hidden from {@code viewer} by the last check. Netty-thread safe. */
    boolean hides(UUID viewer, int entity) {
        Viewer v = viewers.get(viewer);
        if (v == null || entity == 0) return false;
        for (int hidden : v.hiddenIds) if (hidden == entity) return true;
        return false;
    }

    /** Whether the last check found {@code entity}, a projectile of a hidden owner, in {@code viewer}'s sight. Netty-thread safe. */
    boolean showsShot(UUID viewer, int entity) {
        Viewer v = viewers.get(viewer);
        return v != null && v.shownShots.contains(entity);
    }

    /** Whether anything is held back from {@code viewer}'s client. Netty-thread safe. */
    boolean holdsAny(UUID viewer) {
        Viewer v = viewers.get(viewer);
        return v != null && !v.held.isEmpty();
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
            v.held.values().removeIf(player::equals);
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
            Set<UUID> unsent = new HashSet<>(v.droppedSpawns);
            unsent.addAll(v.held.values());
            for (UUID id : unsent) { // still paired by the server, but the client never got their spawn
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
        boolean sightRule = plugin.worlds.contains(world.getName()) && plugin.settings(world).lineOfSight()
                && player.getGameMode() != GameMode.SPECTATOR && !player.hasPermission("antifreecam.bypass");
        Vector eye = player.getEyeLocation().toVector();
        Map<Long, Boolean> blocks = new HashMap<>(); // rays to nearby entities cross the same blocks: look each up once
        Raycast.Solid solid = (x, y, z) -> blocks.computeIfAbsent(blockKey(x, y, z), k -> world.getType(x, y, z).isOccluding());
        Containers containers = view != null && view.containers() ? plugin.containersOf(player.getUniqueId()) : null;
        Vector predicted = sightRule || containers != null ? predictEye(player, v, solid, eye) : null;
        if (containers != null) {
            try { // before the entities, which may fail near a Folia region border
                containers.check(player, check, eye, predicted);
            } catch (RuntimeException e) {
                if (plugin.debug) plugin.getLogger().warning("Container check failed for " + player.getName() + ": " + e);
            }
        }
        long eyeBlock = blockKey(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ());
        Entity vehicle = player.getVehicle();
        List<Entity> passengers = player.getPassengers();

        double r = plugin.losMaxDistance;
        // The server sends entities at any height, so with something height-hidden the whole height is checked.
        double h = heightHidden ? world.getMaxHeight() - world.getMinHeight() : r;
        Set<UUID> seen = new HashSet<>();
        List<Integer> hiddenIds = new ArrayList<>();
        int nearApiHidden = 0, nearHeld = 0;
        Set<Integer> onServer = new HashSet<>();
        List<Location> nearbyAt = new ArrayList<>();
        List<Boolean> nearbyHidden = new ArrayList<>();
        for (Entity e : player.getNearbyEntities(r, h, r)) {
            UUID id = e.getUniqueId();
            int entityId = e.getEntityId();
            boolean listed = plugin.trackedTypes.contains(e.getType());
            // A type taken off the lists by a reload: what is still marked is let go once.
            if (!listed && !v.hidden.contains(id) && !v.held.containsKey(entityId) && !v.droppedSpawns.contains(id))
                continue;
            boolean wasDropped = false;
            UUID heldAs = null;
            try {
                boolean perPlayer = !e.isVisibleByDefault();
                if (perPlayer && !holdable(player, e)) {
                    v.held.remove(entityId);
                    continue;
                }
                if (listed) onServer.add(entityId); // one this plugin can send again
                boolean losType = plugin.losTypes.contains(e.getType());
                // A projectile of a hidden owner gives the owner away unless it is in sight itself (an arrow flying
                // over the wall at the viewer): so it is checked like the listed types.
                boolean shot = plugin.projectileTypes.contains(e.getType()) && ownerHidden(v, e);
                // One stuck in a block no longer points at its owner: shown, without the rays.
                boolean landed = shot && e instanceof AbstractArrow arrow && arrow.isInBlock();
                boolean shotOfHidden = shot && !landed;
                if (shotOfHidden) losType = true;
                Location at = e.getLocation();
                boolean hide = listed && depth && depthY(e) <= view.maxHiddenY();
                // Depth-only types (items, XP orbs) above the cut need nothing unless one is to be shown again:
                // skipping them keeps item farms cheap.
                if (!losType && !hide && !v.hidden.contains(id) && !v.droppedSpawns.contains(id)
                        && !v.held.containsKey(entityId)) continue;
                seen.add(id);
                // Held while per-player: no longer, so its spawn was dropped all the same.
                if (!perPlayer) {
                    wasDropped = v.droppedSpawns.remove(id);
                    heldAs = v.held.remove(entityId);
                }
                if (hide) v.sight.remove(id); // out of the picture: start fresh when it comes back above the cut
                else if (losType && sightRule && Math.abs(at.getY() - eye.getY()) <= r && !exempt(e, vehicle, passengers)) {
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
                // Before showing it: the packet filter holds back such a projectile's spawn otherwise.
                if ((shotOfHidden || landed) && !hide) v.shownShots.add(entityId);
                else v.shownShots.remove(entityId);
                if (losType) { // sounds give away mobs and players; items and boats make none of their own
                    nearbyAt.add(at);
                    nearbyHidden.add(hide);
                }
                if (perPlayer) hold(player, v, e, hide);
                else apply(player, v, e, hide, wasDropped || heldAs != null);
                if (hide) { // after the call: one that failed is counted in the catch instead
                    hiddenIds.add(entityId);
                    if (perPlayer) nearHeld++;
                    else nearApiHidden++;
                }
            } catch (RuntimeException ex) {
                // E.g. Folia: an entity, or a block a ray crosses, of another region (by a region border). This one
                // keeps its state until a check can reach it; the others go on.
                if (wasDropped) v.droppedSpawns.add(id);
                if (heldAs != null) v.held.put(entityId, heldAs);
                seen.add(id);
                if (v.hidden.contains(id) || v.held.containsKey(entityId)) {
                    hiddenIds.add(entityId);
                    if (v.hidden.contains(id)) nearApiHidden++;
                    else nearHeld++;
                }
                if (plugin.debug) plugin.getLogger().warning("Could not check an entity for " + player.getName() + ": " + ex);
            }
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
            if (heightHidden || v.hidden.size() > nearApiHidden || v.held.size() > nearHeld)
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
        v.onServer = onServer;
        v.shownShots.retainAll(onServer);
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
        Set<Integer> onServer = new HashSet<>();
        try {
            for (Entity e : player.getNearbyEntities(far, world.getMaxHeight() - world.getMinHeight(), far)) {
                UUID id = e.getUniqueId();
                if (!plugin.trackedTypes.contains(e.getType())) continue;
                boolean wasDropped = false;
                UUID heldAs = null;
                try {
                    if (e.isVisibleByDefault() || holdable(player, e)) onServer.add(e.getEntityId()); // can be sent again
                    if (!seen.add(id)) continue;
                    // (A landed one too: shown only once in the near box, where its spawn may then go out.)
                    boolean hide = depthY(e) <= cut || plugin.projectileTypes.contains(e.getType()) && ownerHidden(v, e);
                    // Items and XP orbs (sent out to spigot's misc tracking range) above the cut need nothing unless one
                    // is to be shown again.
                    if (!plugin.losTypes.contains(e.getType()) && !hide && !v.hidden.contains(id) && !v.droppedSpawns.contains(id)
                            && !v.held.containsKey(e.getEntityId())) continue;
                    if (!e.isVisibleByDefault()) {
                        if (holdable(player, e)) hold(player, v, e, hide);
                    }
                    // Most types aren't sent this far at all: only hide what the server sends this player.
                    else if (!hide || v.hidden.contains(id) || v.droppedSpawns.contains(id) || e.getTrackedBy().contains(player)) {
                        wasDropped = v.droppedSpawns.remove(id);
                        heldAs = v.held.remove(e.getEntityId());
                        apply(player, v, e, hide, wasDropped || heldAs != null);
                    }
                } catch (RuntimeException ex) { // e.g. one of another Folia region: it keeps its state, the others go on
                    if (wasDropped) v.droppedSpawns.add(id);
                    if (heldAs != null) v.held.put(e.getEntityId(), heldAs);
                    if (plugin.debug) plugin.getLogger().warning("Could not check a far entity for " + player.getName() + ": " + ex);
                }
            }
        } catch (RuntimeException e) { // e.g. Folia refusing the lookup itself: the next far pass tries again
            if (plugin.debug) plugin.getLogger().warning("Far entity check failed for " + player.getName() + ": " + e);
            return;
        }
        v.farOnServer = onServer;
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
                boolean hide = view != null && plugin.trackedTypes.contains(e.getType()) && depthY(e) <= view.maxHiddenY();
                if (!e.isVisibleByDefault()) {
                    if (holdable(player, e)) hold(player, v, e, hide); // takes it out of droppedSpawns
                    else v.droppedSpawns.remove(id);
                } else {
                    v.droppedSpawns.remove(id);
                    apply(player, v, e, hide, true);
                }
            } catch (RuntimeException e) { // e.g. owned by another Folia region: try again next time
                if (plugin.debug) plugin.getLogger().warning("Could not settle a dropped spawn for " + player.getName() + ": " + e);
            }
        }
        if (v.droppedSpawns.size() > 1024) v.droppedSpawns.clear(); // only if settling keeps failing
        if (v.held.size() > 1024) { // entities that left without being seen again: only the gone ones are dropped
            try {
                v.held.values().removeIf(id -> !seen.contains(id) && Bukkit.getEntity(id) == null);
            } catch (RuntimeException e) { // e.g. Folia refusing a lookup from this thread: try again next time
                if (plugin.debug) plugin.getLogger().warning("Could not prune held entities for " + player.getName() + ": " + e);
            }
        }
    }

    /**
     * The height that decides whether an entity is in the hidden range: a rider's vehicle's, where that is lower, as
     * the packet filter takes riders off with their vehicle (riders get no moves of their own).
     */
    private static double depthY(Entity e) {
        double y = e.getLocation().getY();
        for (Entity vehicle = e.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
            y = Math.min(y, vehicle.getLocation().getY());
        return y;
    }

    /** Whether whoever shot or threw a projectile, or flies with a firework, is hidden from this viewer. */
    private static boolean ownerHidden(Viewer v, Entity e) {
        Entity owner = e instanceof Firework f && f.getAttachedTo() != null ? f.getAttachedTo()
                : e instanceof Projectile p && p.getShooter() instanceof Entity shooter ? shooter : null;
        return owner != null && (v.hidden.contains(owner.getUniqueId()) || v.held.containsKey(owner.getEntityId()));
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
     * Whether a per-player entity (one another plugin shows to some players only) can be held back from this viewer:
     * only one that plugin shows them, and only through the server's entity tracker.
     */
    private boolean holdable(Player player, Entity e) {
        return player.canSee(e) && plugin.nms.tracksEntities();
    }

    /**
     * A per-player entity: hiding it through the server would change who sees it, so it is held back from this client
     * instead. The packet filter drops its spawn and every update about it while it is held; one already on the
     * client is taken off it through the server's entity tracker, and it is sent again once it may be seen.
     */
    private void hold(Player player, Viewer v, Entity e, boolean hide) {
        int entity = e.getEntityId();
        boolean dropped = v.droppedSpawns.remove(e.getUniqueId());
        if (hide) {
            if (v.held.put(entity, e.getUniqueId()) == null && !dropped) plugin.nms.untrackEntity(player, e);
        } else if (v.held.remove(entity) != null | dropped) {
            plugin.nms.resendEntity(player, e);
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

    /** Never hidden for line of sight: drawn through walls anyway (glowing, see-through text), or part of the viewer's ride. */
    private static boolean exempt(Entity e, Entity vehicle, List<Entity> passengers) {
        return e.isGlowing() || e instanceof TextDisplay t && t.isSeeThrough() || e.equals(vehicle) || passengers.contains(e);
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
