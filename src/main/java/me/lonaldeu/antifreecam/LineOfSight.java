package me.lonaldeu.antifreecam;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hides entities from players who have no line of sight to them, so ESP/wallhack clients don't learn where
 * players and mobs are behind walls. Each viewer is checked on their own scheduler; an entity counts as visible
 * when a ray from the viewer's eyes reaches its centre, head or a corner of its hitbox without passing through a
 * full solid block. Hiding uses the server's per-player hide/show API, which also re-sends everything on show.
 */
final class LineOfSight {

    private static final long TAB_KEEP_MS = 1000;

    private final AntiFreeCam plugin;
    private final Map<UUID, Set<UUID>> culled = new ConcurrentHashMap<>(); // viewer -> entities hidden for no line of sight
    // viewer -> player -> until when the tab-list removal caused by our own hide is suppressed
    private final Map<UUID, Map<UUID, Long>> keepInTab = new ConcurrentHashMap<>();

    LineOfSight(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    void start(Player viewer) {
        long offset = ThreadLocalRandom.current().nextLong(plugin.losInterval); // spread viewers over ticks
        viewer.getScheduler().runAtFixedRate(plugin, t -> {
            try {
                check(viewer);
            } catch (RuntimeException e) { // e.g. an entity owned by another Folia region; try again next time
                if (plugin.debug) plugin.getLogger().warning("Line-of-sight check failed for " + viewer.getName() + ": " + e);
            }
        }, null, 1 + offset, plugin.losInterval);
    }

    boolean isCulled(Player viewer, Entity e) {
        Set<UUID> set = culled.get(viewer.getUniqueId());
        return set != null && set.contains(e.getUniqueId());
    }

    /** Hides {@code e} from {@code viewer}; a hidden player stays in the viewer's tab list. */
    void hideKeepingTab(Player viewer, Entity e) {
        if (e instanceof Player) keepInTab.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(e.getUniqueId(), System.currentTimeMillis() + TAB_KEEP_MS);
        viewer.hideEntity(plugin, e);
    }

    /** Whether a tab-list removal of {@code player} sent to {@code viewer} was caused by our own hide. Netty-thread safe. */
    boolean shouldKeepInTab(UUID viewer, UUID player) {
        Map<UUID, Long> keep = keepInTab.get(viewer);
        if (keep == null) return false;
        Long until = keep.remove(player);
        return until != null && System.currentTimeMillis() < until;
    }

    void forget(UUID viewer) {
        culled.remove(viewer);
        keepInTab.remove(viewer);
    }

    private void check(Player viewer) {
        Set<UUID> hidden = culled.computeIfAbsent(viewer.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        World world = viewer.getWorld();
        if (!plugin.lineOfSight || viewer.getGameMode() == GameMode.SPECTATOR || viewer.hasPermission("antifreecam.bypass")
                || !plugin.worlds.contains(world.getName())) {
            if (!hidden.isEmpty()) showAll(viewer, hidden);
            return;
        }
        Location eye = viewer.getEyeLocation();
        double r = plugin.losMaxDistance;
        Set<UUID> seen = new HashSet<>();
        for (Entity e : viewer.getNearbyEntities(r, r, r)) {
            if (!plugin.losTypeKeys.contains(e.getType().getKey().toString())) continue;
            seen.add(e.getUniqueId());
            boolean visible = e.isGlowing() || e.equals(viewer.getVehicle()) || e.getPassengers().contains(viewer)
                    || viewer.getPassengers().contains(e) || canSee(world, eye, e.getBoundingBox());
            if (visible) {
                if (hidden.remove(e.getUniqueId())) viewer.showEntity(plugin, e);
            } else if (hidden.add(e.getUniqueId())) {
                hideKeepingTab(viewer, e);
            }
        }
        // ponytail: entities that left range stay marked until seen again (the server keeps its own hidden record too)
        if (hidden.size() > 1024) hidden.retainAll(seen);
    }

    private void showAll(Player viewer, Set<UUID> hidden) {
        for (Entity e : viewer.getNearbyEntities(plugin.losMaxDistance, plugin.losMaxDistance, plugin.losMaxDistance))
            if (hidden.remove(e.getUniqueId())) viewer.showEntity(plugin, e);
        hidden.clear();
    }

    private boolean canSee(World world, Location eye, BoundingBox box) {
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        double near = plugin.losAlwaysVisible;
        if (box.getCenter().distanceSquared(eye.toVector()) <= near * near) return true;
        Raycast.Solid solid = (x, y, z) -> world.getType(x, y, z).isOccluding();
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
