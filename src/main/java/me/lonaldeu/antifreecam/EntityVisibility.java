package me.lonaldeu.antifreecam;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashSet;
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
 * filter dropped are reported here, so they get sent once the entity becomes visible.
 */
final class EntityVisibility {

    private static final long TAB_KEEP_MS = 1000;
    private static final double MAX_PREDICTED_STEP = 3; // blocks per check; more is a teleport, not movement

    private final AntiFreeCam plugin;
    private final Map<UUID, Set<UUID>> hiddenFor = new ConcurrentHashMap<>(); // viewer -> entities we hid via the API
    private final Map<UUID, Set<UUID>> droppedSpawns = new ConcurrentHashMap<>(); // viewer -> spawns the filter dropped
    // viewer -> player -> until when the tab-list removal caused by our own hide is suppressed
    private final Map<UUID, Map<UUID, Long>> keepInTab = new ConcurrentHashMap<>();
    private final Map<UUID, Vector> lastEye = new ConcurrentHashMap<>();

    EntityVisibility(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    void start(Player viewer) {
        long offset = ThreadLocalRandom.current().nextLong(plugin.losInterval); // spread viewers over ticks
        viewer.getScheduler().runAtFixedRate(plugin, t -> {
            try {
                check(viewer);
            } catch (RuntimeException e) { // e.g. an entity owned by another Folia region; try again next time
                if (plugin.debug) plugin.getLogger().warning("Entity visibility check failed for " + viewer.getName() + ": " + e);
            }
        }, null, 1 + offset, plugin.losInterval);
    }

    /** Called from netty threads when the packet filter drops an entity's spawn for {@code viewer}. */
    void spawnDropped(UUID viewer, UUID entity) {
        droppedSpawns.computeIfAbsent(viewer, k -> ConcurrentHashMap.newKeySet()).add(entity);
    }

    /** Whether a tab-list removal of {@code player} sent to {@code viewer} was caused by our own hide. Netty-thread safe. */
    boolean shouldKeepInTab(UUID viewer, UUID player) {
        Map<UUID, Long> keep = keepInTab.get(viewer);
        if (keep == null) return false;
        Long until = keep.remove(player);
        return until != null && System.currentTimeMillis() < until;
    }

    void forget(UUID viewer) {
        hiddenFor.remove(viewer);
        droppedSpawns.remove(viewer);
        keepInTab.remove(viewer);
        lastEye.remove(viewer);
    }

    private void check(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        Set<UUID> hidden = hiddenFor.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
        Set<UUID> dropped = droppedSpawns.computeIfAbsent(viewerId, k -> ConcurrentHashMap.newKeySet());
        World world = viewer.getWorld();
        AntiFreeCam.View view = plugin.views.get(viewerId);
        boolean depth = plugin.hideEntities && view != null;
        boolean sight = plugin.lineOfSight && plugin.worlds.contains(world.getName())
                && viewer.getGameMode() != GameMode.SPECTATOR && !viewer.hasPermission("antifreecam.bypass");
        Vector eye = viewer.getEyeLocation().toVector();
        Vector predicted = sight ? predictEye(viewer, world, eye) : null;

        double r = plugin.losMaxDistance;
        Set<UUID> seen = new HashSet<>();
        for (Entity e : viewer.getNearbyEntities(r, r, r)) {
            if (!plugin.trackedTypeKeys.contains(e.getType().getKey().toString())) continue;
            UUID id = e.getUniqueId();
            seen.add(id);
            boolean spawnWasDropped = dropped.remove(id);
            boolean hide = (depth && e.getLocation().getY() <= view.maxHiddenY())
                    || (sight && !exempt(viewer, e) && !canSee(world, eye, e.getBoundingBox())
                    && (predicted == null || !canSee(world, predicted, e.getBoundingBox())));
            if (hide) {
                if (hidden.add(id)) hideKeepingTab(viewer, e);
            } else if (hidden.remove(id)) {
                viewer.showEntity(plugin, e);
            } else if (spawnWasDropped) { // the client never got this entity: have the server send it now
                hideKeepingTab(viewer, e);
                viewer.showEntity(plugin, e);
            }
        }
        // ponytail: entities that left range stay marked until seen again (the server keeps its own hidden record too)
        if (hidden.size() > 1024) hidden.retainAll(seen);
        if (dropped.size() > 1024) dropped.clear();
    }

    private static boolean exempt(Player viewer, Entity e) {
        return e.isGlowing() || e.equals(viewer.getVehicle()) || e.getPassengers().contains(viewer) || viewer.getPassengers().contains(e);
    }

    private void hideKeepingTab(Player viewer, Entity e) {
        if (e instanceof Player) keepInTab.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(e.getUniqueId(), System.currentTimeMillis() + TAB_KEEP_MS);
        viewer.hideEntity(plugin, e);
    }

    /**
     * Where the viewer's eyes will be by the time their client shows the result of the next check: the movement
     * since the last check, carried forward over the check interval plus their ping. Cuts pop-in when rounding a
     * corner. Not used when a solid block lies on the way, so pushing into a wall never lets anyone see through it.
     */
    private Vector predictEye(Player viewer, World world, Vector eye) {
        Vector last = lastEye.put(viewer.getUniqueId(), eye);
        if (last == null) return null;
        Vector step = eye.clone().subtract(last);
        double length = step.length();
        if (length < 0.01 || length > MAX_PREDICTED_STEP) return null;
        double ahead = 1 + Math.min(viewer.getPing(), 500) / 50.0 / plugin.losInterval;
        Vector predicted = eye.clone().add(step.multiply(ahead));
        Raycast.Solid solid = solid(world);
        boolean blocked = solid.at(predicted.getBlockX(), predicted.getBlockY(), predicted.getBlockZ())
                || !Raycast.clear(eye.getX(), eye.getY(), eye.getZ(), predicted.getX(), predicted.getY(), predicted.getZ(), solid);
        return blocked ? null : predicted;
    }

    private static Raycast.Solid solid(World world) {
        return (x, y, z) -> world.getType(x, y, z).isOccluding();
    }

    private boolean canSee(World world, Vector eye, BoundingBox box) {
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        double near = plugin.losAlwaysVisible;
        if (box.getCenter().distanceSquared(eye) <= near * near) return true;
        Raycast.Solid solid = solid(world);
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
