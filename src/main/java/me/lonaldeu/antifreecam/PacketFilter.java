package me.lonaldeu.antifreecam;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sits in one player's netty pipeline, ahead of the packet encoder, and rewrites or drops outgoing packets that
 * would show anything the player may not see: their hidden range, and sections cave occlusion hides. Runs on the
 * netty event loop: only reads thread-safe plugin state, and owns this player's {@link Occlusion}.
 */
final class PacketFilter extends ChannelOutboundHandlerAdapter {

    static final String NAME = "antifreecam_filter";

    /** Where the player's eyes are: world, column (x, z), section index s. */
    record Eye(UUID world, int x, int s, int z) {
    }

    private final AntiFreeCam plugin;
    private final Nms nms;
    private final Player player;
    private final UUID id;
    private final Channel channel;
    private final Occlusion occlusion;
    private UUID worldId; // event loop only: world of the last posted eye, for re-sends and recomputes
    private Object dimension; // event loop only: the dimension of the chunks the client holds
    private volatile Eye posted;
    private boolean warnedUnparsable, warnedFailure;

    /** Created on the player's own thread, before the filter is in the pipeline. */
    PacketFilter(AntiFreeCam plugin, Player player, Channel channel) {
        this.plugin = plugin;
        this.nms = plugin.nms;
        this.player = player;
        this.id = player.getUniqueId();
        this.channel = channel;
        this.dimension = nms.opaque == null ? null : nms.dimension(player.getWorld());
        this.occlusion = new Occlusion(new Occlusion.Server() {
            // Scheduling failures must not abort the occlusion update that asked, or the packet would go out unfiltered.
            @Override
            public void resend(int x, int z, int n) {
                try {
                    if (worldId != null) plugin.revealLater(player, worldId, x, z, 1 + n / plugin.maxChunksPerTick);
                } catch (RuntimeException e) {
                    if (plugin.debug) plugin.getLogger().warning("Could not schedule a re-send: " + e);
                }
            }

            @Override
            public void recompute(int x, int z, int s) {
                int epoch = occlusion.epoch();
                try {
                    if (worldId != null) plugin.sectionConnectivityLater(worldId, x, z, s,
                            conn -> channel.eventLoop().execute(() -> occlusion.exact(x, z, s, conn, epoch)));
                } catch (RuntimeException e) {
                    if (plugin.debug) plugin.getLogger().warning("Could not schedule a section check: " + e);
                }
            }
        });
    }

    /** Called on the player's own thread; hands the eye position to the event loop when it changed. */
    void moved(Eye eye) {
        if (eye.equals(posted)) return;
        posted = eye;
        channel.eventLoop().execute(() -> {
            worldId = eye.world();
            occlusion.moveTo(eye.x(), eye.s(), eye.z());
        });
    }

    private boolean tracking() {
        return plugin.caveOcclusion && nms.opaque != null;
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
                if (!to.equals(dimension)) occlusion.clear();
                dimension = to;
            } else if (type == nms.forgetPacket) {
                occlusion.forget(nms.forgottenChunk(msg));
            }

            if (type == nms.playerInfoRemovePacket) out = keepCulledPlayersInTab(msg);
            else if (plugin.stripSideChannels && plugin.entities.givesAwayHidden(id, msg)) out = null;
            else if (view != null) {
                boolean track = tracking();
                if (!track) occlusion.clear(); // switched off: nothing is sent as occluded any more
                occlusion.view(view.minY(), view.maxHiddenY(), view.occlude() && track);
                out = filter(msg, view.minY(), view.maxHiddenY());
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
        if (type == nms.bundlePacket) return filterBundle(msg, minY, maxY);
        if (type == nms.chunkPacket) return filterChunk(msg, minY, maxY);
        if (type == nms.blockUpdatePacket) {
            int[] pos = nms.blockUpdatePos(msg);
            if (tracking()) occlusion.changed(pos[0], pos[1], pos[2], nms.isOpaque(nms.blockUpdateStateId(msg)));
            // The client already shows the replacement there, so the update can simply be dropped.
            return hidden(pos, maxY) ? null : msg;
        }
        if (type == nms.sectionUpdatePacket) return filterSectionUpdate(msg, maxY);
        if (type == nms.addEntityPacket) {
            if (nms.entityY(msg) > maxY || !plugin.hiddenTypeKeys.contains(nms.entityTypeKey(msg))) return msg;
            plugin.entities.spawnDropped(id, nms.entityUuid(msg)); // so it gets sent if it later comes into view
            return null;
        }
        if (!plugin.stripSideChannels) return msg;
        if (type == nms.lightUpdatePacket)
            return nms.withoutLight(msg, minY, maxY, occlusion.replacedMask(nms.lightX(msg), nms.lightZ(msg)));
        // Biome edits (/fillbiome) are rare; players with a hidden range get the new biomes on their next refresh.
        if (type == nms.chunksBiomesPacket) return null;
        int[] pos = nms.sideChannelPos(msg);
        return pos != null && hidden(pos, maxY) ? null : msg;
    }

    private Object filterBundle(Object bundle, int minY, int maxY) {
        List<Object> parts = new ArrayList<>();
        boolean changed = false;
        for (Object part : nms.subPackets(bundle)) {
            Object filtered = filter(part, minY, maxY);
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
        return out == in ? packet : nms.withChunkBuffer(packet, out, plugin.stripSideChannels, minY, maxY, occluded);
    }

    /** All changes in one packet share a 16-high section: observe them, then pass, drop, or keep the part above the cut. */
    private Object filterSectionUpdate(Object packet, int maxY) {
        int[][] first = {null};
        boolean track = tracking();
        nms.forEachChange(packet, (pos, state) -> {
            int[] p = nms.xyz(pos);
            if (track) occlusion.changed(p[0], p[1], p[2], nms.isOpaque(nms.stateId(state)));
            if (first[0] == null) first[0] = p;
        });
        if (first[0] == null) return packet;
        int[] p = first[0];
        int sectionBottom = Math.floorDiv(p[1], 16) * 16;
        if (occlusion.replaced(p[0], p[1], p[2])) return null; // the client holds this whole section as replacement
        if (sectionBottom > maxY) return packet;
        if (sectionBottom + 15 <= maxY) return null;
        List<Object> kept = new ArrayList<>();
        nms.forEachChange(packet, (pos, state) -> {
            if (nms.xyz(pos)[1] > maxY) kept.add(nms.blockUpdate(pos, state));
        });
        return kept.isEmpty() ? null : nms.bundle(kept);
    }
}
