package me.lonaldeu.antifreecam;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Sits in one player's netty pipeline, ahead of the packet encoder, and rewrites or drops outgoing packets that
 * would show anything the player may not see: their hidden range, and sections cave occlusion hides. Runs on the
 * netty event loop: only reads thread-safe plugin state, and owns this player's {@link Occlusion}.
 */
final class PacketFilter extends ChannelOutboundHandlerAdapter {

    static final String NAME = "antifreecam_filter";
    static final int CONCEAL_TICKS = 100; // how long a closed path waits before what it cut off is re-sent hidden

    /** Where the player's eyes are: world, column (x, z), section index s. */
    record Eye(World world, int x, int s, int z) {
    }

    /** An entity the client has: its UUID, its height as the client last got it, and whether its type is listed. */
    private static final class Moving {
        final UUID uuid;
        final boolean listed;
        double y;

        Moving(UUID uuid, double y, boolean listed) {
            this.uuid = uuid;
            this.y = y;
            this.listed = listed;
        }
    }

    private final AntiFreeCam plugin;
    private final Nms nms;
    private final Player player;
    private final UUID id;
    private final Channel channel;
    private final Occlusion occlusion;
    final Containers containers;
    private boolean hideContainers, occluding; // event loop only: the view's, as of the packet being written
    private World world; // event loop only: world of the last posted eye, for re-sends and recomputes
    private Object dimension; // event loop only: the dimension of the chunks the client holds
    private volatile Eye posted;
    // Event loop only: entities whose spawn this filter dropped, so the client doesn't have them. Every packet about
    // them is dropped too, until one spawns them or the client is told they are gone.
    private final Set<Integer> spawnsDropped = new HashSet<>();
    // Event loop only: entities the client has, by entity id, so a move that would take a listed one (or a vehicle
    // carrying one) to or below the cut is stopped before its new position goes out; who rides what, as riders get no
    // moves of their own; and the vehicle the player rides, which moves with them.
    private final Map<Integer, Moving> moving = new HashMap<>();
    private final Map<Integer, int[]> riders = new HashMap<>();
    private int vehicle = -1;
    // Event loop only: what light cleaning left along the sides of each column sent (under cut maxY), and what it was
    // cleaned with from its neighbours; so hidden light that crosses into the next column is taken out there too.
    private record Edge(int maxY, LightLeak.Side[] sides, LightLeak.Side[] seen, int knew) { // knew: bit s, side s's neighbour
    }

    private static final int[] SIDE_DX = {0, 0, -1, 1}, SIDE_DZ = {-1, 1, 0, 0};
    private static final int MAX_LIGHT_RESENDS = 2; // re-sends one send can set off in a row: light settles in one or two
    private final Map<Long, Edge> edges = new HashMap<>();
    private final Map<Long, Integer> lightResends = new HashMap<>(); // a light re-send on its way, and how deep in its chain
    private boolean warnedUnparsable, warnedFailure;

    /** Created on the player's own thread, before the filter is in the pipeline. */
    PacketFilter(AntiFreeCam plugin, Player player, Channel channel) {
        this.plugin = plugin;
        this.nms = plugin.nms;
        this.player = player;
        this.id = player.getUniqueId();
        this.channel = channel;
        this.dimension = nms.opaque == null ? null : nms.dimension(player.getWorld());
        this.containers = new Containers(plugin);
        this.occlusion = new Occlusion(new Occlusion.Server() {
            // Scheduling failures must not abort the occlusion update that asked, or the packet would go out unfiltered.
            @Override
            public void resend(int x, int z, int n) {
                try {
                    if (world != null) plugin.revealLater(player, world, x, z, 1 + n / plugin.maxChunksPerTick);
                } catch (RuntimeException e) {
                    if (plugin.debug) plugin.getLogger().warning("Could not schedule a re-send: " + e);
                }
            }

            @Override
            public void recompute(int x, int z, int s, boolean slow) {
                int epoch = occlusion.epoch();
                try {
                    if (world != null) plugin.sectionConnectivityLater(world, x, z, s, slow,
                            conn -> channel.eventLoop().execute(() -> occlusion.exact(x, z, s, conn, epoch)));
                } catch (RuntimeException e) {
                    if (plugin.debug) plugin.getLogger().warning("Could not schedule a section check: " + e);
                }
            }

            @Override
            public void conceal(int x, int z, int n) {
                channel.eventLoop().schedule(() -> occlusion.concealNow(x, z),
                        (CONCEAL_TICKS + n / plugin.maxChunksPerTick) * 50L, TimeUnit.MILLISECONDS);
            }
        });
    }

    /**
     * Called on the player's own thread; hands the eye position to the event loop when it changed. Null when they have
     * no view: then no world is kept, as it may be unloaded meanwhile.
     */
    void moved(Eye eye) {
        if (Objects.equals(eye, posted)) return;
        posted = eye;
        channel.eventLoop().execute(() -> {
            world = eye == null ? null : eye.world();
            if (eye != null) occlusion.moveTo(eye.x(), eye.s(), eye.z());
        });
    }

    /** Whether cave occlusion applies to the view: only then is connectivity worked out. */
    private boolean tracking() {
        return occluding;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        View view = plugin.views.get(id);
        Object out = msg;
        try {
            Class<?> type = msg.getClass();
            if (type == nms.respawnPacket) {
                // A new dimension makes the client drop all chunks; a same-world respawn (e.g. a skin refresh) keeps them.
                Object to = nms.respawnDimension(msg);
                if (!to.equals(dimension)) {
                    occlusion.clear();
                    containers.clear();
                    edges.clear();
                    lightResends.clear();
                }
                dimension = to;
                spawnsDropped.clear(); // a respawn clears the client's entities
                moving.clear();
                riders.clear();
                vehicle = -1;
            } else if (type == nms.forgetPacket) {
                long chunk = nms.forgottenChunk(msg);
                occlusion.forget(chunk);
                containers.forget(chunk);
                edges.remove(chunk);
                lightResends.remove(chunk);
            }

            if (type == nms.playerInfoRemovePacket) out = keepCulledPlayersInTab(msg);
            else if (plugin.stripSideChannels && plugin.entities.givesAwayHidden(id, msg)) out = null;
            else if (view != null) {
                occluding = view.occlude(); // a change of it is a view change: every chunk is sent again
                if (!occluding) occlusion.clear(); // switched off: nothing is sent as occluded any more
                occlusion.view(view.minY(), view.maxHiddenY(), occluding);
                hideContainers = view.containers();
                out = filter(msg, view.minY(), view.maxHiddenY());
            } else if (!spawnsDropped.isEmpty() || plugin.entities.holdsAny(id) || !moving.isEmpty()) {
                out = withoutDroppedEntities(msg);
            }
        } catch (RuntimeException e) { // the packet goes out unfiltered, so say so at least once
            if (plugin.debug || !warnedFailure) plugin.getLogger().warning("Could not filter " + msg.getClass().getSimpleName()
                    + " for " + player.getName() + ", sending it unfiltered: " + e
                    + (plugin.debug ? "" : " (further failures only show with /afc debug)"));
            warnedFailure = true;
        }
        if (out == null) promise.trySuccess();
        else ctx.write(out, promise);
    }

    /** Hiding a player behind a wall would also drop them from the tab list; keep them there. */
    private Object keepCulledPlayersInTab(Object packet) {
        List<UUID> ids = nms.profileIds(packet);
        List<UUID> removed = ids.stream().filter(p -> !plugin.entities.shouldKeepInTab(id, p)).toList();
        if (removed.size() == ids.size()) return packet;
        return removed.isEmpty() ? null : nms.playerInfoRemove(removed);
    }

    /** Whether the client holds block (x, y, z) as replacement: under the cut, or in an occluded section. */
    private boolean hidden(int[] pos, int maxY) {
        return pos[1] <= maxY || occlusion.replaced(pos[0], pos[1], pos[2]);
    }

    /** The packet to send in place of {@code msg}, or null to drop it. */
    private Object filter(Object msg, int minY, int maxY) {
        Class<?> type = msg.getClass();
        if (type == nms.bundlePacket) return filterBundle(msg, part -> filter(part, minY, maxY));
        if (type == nms.chunkPacket) return filterChunk(msg, minY, maxY);
        if (type == nms.blockUpdatePacket) {
            int[] pos = nms.blockUpdatePos(msg);
            int state = nms.blockUpdateStateId(msg);
            if (tracking()) occlusion.changed(pos[0], pos[1], pos[2], nms.isOpaque(state));
            // The client already shows the replacement there, so the update can simply be dropped.
            if (hidden(pos, maxY)) return null;
            return hideContainers ? containers.blockUpdate(msg, pos, state) : msg;
        }
        if (type == nms.sectionUpdatePacket) return filterSectionUpdate(msg, maxY);
        if (type == nms.addEntityPacket || type == nms.addOrbPacket) return filterSpawn(msg, maxY);
        if (aboutDroppedEntity(msg)) return null;
        Object moved = filterMove(msg, maxY);
        if (moved != msg) return moved;
        if (hideContainers && containers.givesAway(msg)) return null;
        if (!plugin.stripSideChannels) return msg;
        if (type == nms.lightUpdatePacket)
            return nms.withoutLight(msg, minY, maxY, occlusion.replacedMask(nms.lightX(msg), nms.lightZ(msg)));
        // Biome edits (/fillbiome) are rare; players with a hidden range get the new biomes on their next refresh.
        if (type == nms.chunksBiomesPacket) return null;
        int[] pos = nms.sideChannelPos(msg);
        if (pos == null) return msg;
        return hidden(pos, maxY) ? null : nms.explosionWithout(msg, p -> hidden(p, maxY));
    }

    /**
     * A spawn: dropped when the entity is held back from this client, or listed and at or below {@code maxY}. Every
     * update about a dropped one is dropped too, until a spawn of it goes through.
     */
    private Object filterSpawn(Object msg, int maxY) {
        boolean orb = msg.getClass() == nms.addOrbPacket; // experience orbs, until 1.21.5
        int entity = orb ? nms.orbId(msg) : nms.addedEntityId(msg);
        boolean drop = plugin.entities.isHeld(id, entity);
        String type = orb ? "minecraft:experience_orb" : nms.entityTypeKey(msg);
        // Listed and at or below the cut; or a projectile whose owner is hidden: it spawns right where the owner is.
        if (!drop && ((orb ? nms.orbY(msg) : nms.entityY(msg)) <= maxY && plugin.hiddenTypeKeys.contains(type)
                || !orb && plugin.projectileKeys.contains(type) && plugin.entities.hides(id, nms.addedEntityData(msg))
                && !plugin.entities.showsShot(id, entity))) {
            drop = true;
            // So it gets sent if it later comes into view. An orb's spawn has no UUID: the pass hides it anyway.
            // shortcut: an orb that drifts back above the cut first stays unsent (before 1.21.5); report orb ids if seen
            if (!orb) plugin.entities.spawnDropped(id, nms.entityUuid(msg));
        }
        if (!drop) {
            spawnsDropped.remove(entity);
            if (!orb && entity >= 0 && nms.tracksMoves())
                moving.put(entity, new Moving(nms.entityUuid(msg), nms.entityY(msg), plugin.hiddenTypeKeys.contains(type)));
            return msg;
        }
        spawnsDropped.add(entity);
        moving.remove(entity);
        return null;
    }

    /**
     * A move that would take an entity to or below {@code maxY}: the client is told that it, if listed, and the listed
     * entities riding it are gone instead, so their positions down there never go out, and the visibility pass settles
     * them like dropped spawns (hides them through the server, or sends them again if they are back above by then).
     * Keeps the heights of the others current, and who rides what.
     */
    private Object filterMove(Object msg, int maxY) {
        Class<?> type = msg.getClass();
        if (type == nms.setPassengersPacket) {
            int ridden = nms.entityIdOf(msg), self = player.getEntityId();
            int[] on = nms.passengers(msg);
            boolean mine = false;
            for (int passenger : on) mine |= passenger == self;
            if (mine) vehicle = ridden;
            else if (ridden == vehicle) vehicle = -1;
            if (on.length == 0) riders.remove(ridden);
            else riders.put(ridden, on);
            return msg;
        }
        if (moving.isEmpty()) return msg;
        if (type == nms.removeEntitiesPacket) {
            for (Object entity : nms.removedEntityIds(msg)) {
                moving.remove(entity);
                riders.remove(entity);
            }
            return msg;
        }
        int entity = nms.entityIdOf(msg);
        Moving m = entity < 0 ? null : moving.get(entity);
        if (m == null) return msg;
        double y = nms.movedY(msg, m.y);
        if (Double.isNaN(y)) return msg;
        m.y = y;
        if (y > maxY || entity == vehicle) return msg; // the player's own vehicle is where they are
        List<Integer> off = new ArrayList<>();
        takeOff(entity, off, 0);
        if (off.isEmpty()) return msg;
        int[] ids = new int[off.size()];
        for (int k = 0; k < ids.length; k++) {
            ids[k] = off.get(k);
            spawnsDropped.add(ids[k]);
            plugin.entities.spawnDropped(id, moving.remove(ids[k]).uuid);
        }
        Object remove = nms.removeEntities(ids);
        return off.get(0) == entity ? remove : nms.bundle(List.of(remove, msg)); // an unlisted vehicle still moves
    }

    /**
     * Collects the entity, if listed, and the listed entities riding it, at any depth. Only ones a check found on the
     * server: one that just spawned waits for the next check, and one that exists only in another plugin's packets
     * couldn't be sent again.
     */
    private void takeOff(int entity, List<Integer> off, int depth) {
        Moving m = moving.get(entity);
        if (m != null && m.listed && plugin.entities.onServer(id, entity)) off.add(entity);
        int[] on = riders.get(entity);
        if (on != null && depth < 8) for (int passenger : on) takeOff(passenger, off, depth + 1);
    }

    /** With no view: held-back spawns, and updates about entities whose spawn was dropped, are still dropped. */
    private Object withoutDroppedEntities(Object msg) {
        if (msg.getClass() == nms.bundlePacket) return filterBundle(msg, this::withoutDroppedEntities);
        if (msg.getClass() == nms.addEntityPacket || msg.getClass() == nms.addOrbPacket) return filterSpawn(msg, Integer.MIN_VALUE);
        return aboutDroppedEntity(msg) ? null : filterMove(msg, Integer.MIN_VALUE); // no cut: only keeps heights current
    }

    /** Whether a packet is about an entity whose spawn was dropped. A removal forgets the entities it lists. */
    private boolean aboutDroppedEntity(Object msg) {
        if (spawnsDropped.isEmpty()) return false;
        if (msg.getClass() == nms.removeEntitiesPacket) {
            for (Object entity : nms.removedEntityIds(msg)) spawnsDropped.remove(entity);
            return false;
        }
        int entity = nms.entityIdOf(msg);
        return entity >= 0 && spawnsDropped.contains(entity);
    }

    private Object filterBundle(Object bundle, UnaryOperator<Object> each) {
        List<Object> parts = new ArrayList<>();
        boolean changed = false;
        for (Object part : nms.subPackets(bundle)) {
            Object filtered = each.apply(part);
            changed |= filtered != part;
            if (filtered == null) continue;
            if (filtered.getClass() == nms.bundlePacket) parts.addAll(nms.subPackets(filtered)); // bundles can't nest
            else parts.add(filtered);
        }
        if (!changed) return bundle;
        return parts.isEmpty() ? null : nms.bundle(parts);
    }

    private Object filterChunk(Object packet, int minY, int maxY) {
        byte[] in = nms.chunkBuffer(packet);
        long occluded = 0;
        if (tracking()) {
            long[] conn = ChunkSections.connectivity(in, nms::isOpaque); // from the real blocks, before any rewrite
            if (conn != null) occluded = occlusion.add(nms.chunkX(packet), nms.chunkZ(packet), conn);
        }
        byte[] out = ChunkSections.hideBelow(in, minY, maxY, occluded, plugin.replacementId, plugin.occludedId,
                nms::isAir, nms.globalBlockBits);
        if (out == null) {
            if (!warnedUnparsable) plugin.getLogger().warning("Unrecognized chunk format, sending chunks unfiltered");
            warnedUnparsable = true;
            return packet;
        }
        Set<Long> removed = Set.of();
        if (hideContainers) {
            Containers.Disguised disguised = containers.disguise(packet, out, minY, maxY, occluded);
            if (disguised != null) {
                out = disguised.buffer();
                removed = disguised.removed();
            }
        }
        if (out == in && removed.isEmpty()) return packet;
        if (!plugin.lightAcrossChunks || !plugin.stripSideChannels)
            return nms.withChunkBuffer(packet, out, plugin.stripSideChannels, minY, maxY, occluded, removed, null, null, null);
        int x = nms.chunkX(packet), z = nms.chunkZ(packet);
        long self = Occlusion.key(x, z);
        Integer pending = lightResends.remove(self);
        int depth = pending == null ? 0 : pending; // a load, refresh or reveal starts a new chain of re-sends
        LightLeak.Side[] neighbours = new LightLeak.Side[4], sides = new LightLeak.Side[4];
        boolean[] relight = new boolean[4];
        int knew = 0;
        for (int s = 0; s < 4; s++) {
            Edge n = edges.get(Occlusion.key(x + SIDE_DX[s], z + SIDE_DZ[s]));
            if (n == null || n.maxY() != maxY) continue;
            knew |= 1 << s;
            neighbours[s] = n.sides()[s ^ 1];
            // What it kept may still hold light from here, if it was cleaned before this column was known.
            relight[s] = (n.knew() >> (s ^ 1) & 1) != 0;
        }
        Object filtered = nms.withChunkBuffer(packet, out, true, minY, maxY, occluded, removed, neighbours, relight, sides);
        // A side used without relighting from it counts as not seen: that neighbour's next send re-sends this column.
        for (int s = 0; s < 4; s++) if (!relight[s]) neighbours[s] = null;
        edges.put(self, new Edge(maxY, sides, neighbours, knew));
        // A neighbour already sent and cleaned with other light from this side than it now has: send it again.
        for (int s = 0; s < 4; s++) {
            long key = Occlusion.key(x + SIDE_DX[s], z + SIDE_DZ[s]);
            Edge n = edges.get(key);
            if (n == null || n.maxY() != maxY || LightLeak.Side.same(n.seen()[s ^ 1], sides[s]) || world == null) continue;
            if (depth >= MAX_LIGHT_RESENDS || lightResends.containsKey(key)) continue; // one on its way reads the newest
            lightResends.put(key, depth + 1);
            try {
                plugin.revealLater(player, world, x + SIDE_DX[s], z + SIDE_DZ[s], 1);
            } catch (RuntimeException e) {
                if (plugin.debug) plugin.getLogger().warning("Could not schedule a re-send: " + e);
            }
        }
        return filtered;
    }

    /** All changes in one packet share a 16-high section: observe them, then pass, drop, or keep the part above the cut. */
    private Object filterSectionUpdate(Object packet, int maxY) {
        int[][] first = {null};
        boolean track = tracking();
        boolean[] disguise = {false};
        nms.forEachChange(packet, (pos, state) -> {
            int[] p = nms.xyz(pos);
            int id = nms.stateId(state);
            if (track) occlusion.changed(p[0], p[1], p[2], nms.isOpaque(id));
            if (hideContainers && p[1] > maxY && containers.wouldDisguise(p, id)) disguise[0] = true;
            if (first[0] == null) first[0] = p;
        });
        if (first[0] == null) return packet;
        int[] p = first[0];
        int sectionBottom = Math.floorDiv(p[1], 16) * 16;
        if (occlusion.replaced(p[0], p[1], p[2])) return null; // the client holds this whole section as replacement
        if (sectionBottom > maxY && !disguise[0]) return packet;
        if (sectionBottom + 15 <= maxY) return null;
        List<Object> kept = new ArrayList<>();
        nms.forEachChange(packet, (pos, state) -> {
            int[] at = nms.xyz(pos);
            if (at[1] <= maxY) return;
            Object single = nms.blockUpdate(pos, state);
            if (hideContainers) single = containers.blockUpdate(single, at, nms.stateId(state));
            if (single != null) kept.add(single);
        });
        return kept.isEmpty() ? null : nms.bundle(kept);
    }
}
