package me.lonaldeu.antifreecam;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sits in one player's netty pipeline, ahead of the packet encoder, and rewrites or drops outgoing packets that
 * would show anything in the player's hidden range. Runs on the netty event loop: only reads thread-safe plugin state.
 */
final class PacketFilter extends ChannelOutboundHandlerAdapter {

    static final String NAME = "antifreecam_filter";

    private final AntiFreeCam plugin;
    private final Nms nms;
    private final UUID player;
    private boolean warnedUnparsable;

    PacketFilter(AntiFreeCam plugin, UUID player) {
        this.plugin = plugin;
        this.nms = plugin.nms;
        this.player = player;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        AntiFreeCam.View view = plugin.views.get(player);
        Object out = msg;
        if (view != null) {
            try {
                out = filter(msg, view.minY(), view.maxHiddenY());
            } catch (RuntimeException e) {
                if (plugin.debug) plugin.getLogger().warning("Could not filter " + msg.getClass().getSimpleName() + ": " + e);
            }
        }
        if (out == null) promise.trySuccess();
        else ctx.write(out, promise);
    }

    /** The packet to send in place of {@code msg}, or null to drop it. */
    private Object filter(Object msg, int minY, int maxY) {
        Class<?> type = msg.getClass();
        if (type == nms.bundlePacket) return filterBundle(msg, minY, maxY);
        if (type == nms.chunkPacket) return plugin.modifyChunks ? filterChunk(msg, minY, maxY) : msg;
        // The client already shows the replacement there, so updates at or below the limit can simply be dropped.
        if (type == nms.blockUpdatePacket) return plugin.modifyBlockChanges && nms.blockUpdateY(msg) <= maxY ? null : msg;
        if (type == nms.sectionUpdatePacket) return plugin.modifyMultiBlockChanges ? filterSectionUpdate(msg, maxY) : msg;
        if (type == nms.addEntityPacket)
            return nms.entityY(msg) <= maxY && plugin.hiddenTypeKeys.contains(nms.entityTypeKey(msg)) ? null : msg;
        if (!plugin.stripSideChannels) return msg;
        if (type == nms.lightUpdatePacket) return nms.withoutLight(msg, minY, maxY);
        // Biome edits (/fillbiome) are rare; players with a hidden range get the new biomes on their next refresh.
        if (type == nms.chunksBiomesPacket) return null;
        Integer y = nms.sideChannelY(msg);
        return y != null && y <= maxY ? null : msg;
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
        byte[] out = ChunkSections.hideBelow(in, minY, maxY, plugin.replacementId, nms::isAir, nms.globalBlockBits);
        if (out == null) {
            if (!warnedUnparsable) plugin.getLogger().warning("Unrecognized chunk format, sending chunks unfiltered");
            warnedUnparsable = true;
            return packet;
        }
        return out == in ? packet : nms.withChunkBuffer(packet, out, plugin.stripSideChannels, minY, maxY);
    }

    /** All changes in one packet share a 16-high section: pass it, drop it, or keep the part above the limit. */
    private Object filterSectionUpdate(Object packet, int maxY) {
        int[] lowest = {Integer.MAX_VALUE};
        nms.forEachChange(packet, (pos, state) -> lowest[0] = Math.min(lowest[0], nms.y(pos)));
        int sectionBottom = Math.floorDiv(lowest[0], 16) * 16;
        if (sectionBottom > maxY) return packet;
        if (sectionBottom + 15 <= maxY) return null;
        List<Object> kept = new ArrayList<>();
        nms.forEachChange(packet, (pos, state) -> {
            if (nms.y(pos) > maxY) kept.add(nms.blockUpdate(pos, state));
        });
        return kept.isEmpty() ? null : nms.bundle(kept);
    }
}
