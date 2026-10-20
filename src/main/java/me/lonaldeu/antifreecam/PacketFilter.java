package me.lonaldeu.antifreecam;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import java.util.UUID;

/** Rewrites outgoing packets for hidden players. Runs on netty threads: only touches thread-safe plugin state. */
final class PacketFilter implements PacketListener {

    private final AntiFreeCam plugin;

    PacketFilter(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        UUID id = event.getUser().getUUID();
        if (id == null || !plugin.hidden.contains(id)) return;
        PacketTypeCommon type = event.getPacketType();
        try {
            if (type == PacketType.Play.Server.CHUNK_DATA && plugin.modifyChunks) chunk(event);
            else if (type == PacketType.Play.Server.BLOCK_CHANGE && plugin.modifyBlockChanges) blockChange(event);
            else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE && plugin.modifyMultiBlockChanges) multiBlockChange(event);
            else if (type == PacketType.Play.Server.SPAWN_ENTITY) spawn(event);
        } catch (RuntimeException e) {
            if (plugin.debug) plugin.getLogger().warning("Could not filter " + type + ": " + e);
        }
    }

    private void chunk(PacketSendEvent event) {
        BaseChunk[] sections = new WrapperPlayServerChunkData(event).getColumn().getChunks();
        int minY = event.getUser().getMinWorldHeight();
        int maxY = plugin.hideBelowY, rep = plugin.replacementId;
        boolean changed = false;
        for (int i = 0; i < sections.length; i++) {
            BaseChunk s = sections[i];
            int baseY = minY + i * 16;
            if (s == null || s.isEmpty() || baseY > maxY) continue;
            int top = Math.min(15, maxY - baseY);
            for (int y = 0; y <= top; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (s.getBlockId(x, y, z) != rep) {
                            s.set(x, y, z, rep);
                            changed = true;
                        }
        }
        if (changed) event.markForReEncode(true);
    }

    private void blockChange(PacketSendEvent event) {
        var w = new WrapperPlayServerBlockChange(event);
        if (w.getBlockPosition().getY() <= plugin.hideBelowY && w.getBlockId() != plugin.replacementId) {
            w.setBlockID(plugin.replacementId);
            event.markForReEncode(true);
        }
    }

    private void multiBlockChange(PacketSendEvent event) {
        boolean changed = false;
        for (var b : new WrapperPlayServerMultiBlockChange(event).getBlocks()) {
            if (b.getY() <= plugin.hideBelowY && b.getBlockId() != plugin.replacementId) {
                b.setBlockId(plugin.replacementId);
                changed = true;
            }
        }
        if (changed) event.markForReEncode(true);
    }

    // Metadata/movement for a dropped spawn is ignored by the client, so only the spawn needs filtering.
    private void spawn(PacketSendEvent event) {
        var w = new WrapperPlayServerSpawnEntity(event);
        if (w.getPosition().getY() <= plugin.hideBelowY
                && plugin.hiddenTypeKeys.contains(w.getEntityType().getName().toString())) {
            event.setCancelled(true);
        }
    }
}
