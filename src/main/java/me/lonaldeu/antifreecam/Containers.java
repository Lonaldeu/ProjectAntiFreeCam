package me.lonaldeu.antifreecam;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lidded;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chest ESP hiding for one player: containers (chests, barrels, shulker boxes, spawners) in parts of the world the
 * player is sent for real are disguised as the blocks around them until the player can actually see them, so chest
 * ESP and x-ray find nothing through walls. Only containers with a block entity in the chunk data are touched, so the
 * fake chests Paper's anti-xray writes into stone stay as they are. The packet filter (netty) disguises and records
 * them; the visibility task (the player's thread) reveals them once in sight.
 */
final class Containers {

    static final double NEAR = 6; // always shown this close: a little past the reach for using or placing blocks
    private static final int BUDGET = 64; // line-of-sight tests per check
    private static final int REUSE = 5; // checks an out-of-sight answer holds while the player's eyes stay in one block

    /** A chunk packet's section data with containers disguised, and the positions of their removed block entities. */
    record Disguised(byte[] buffer, Set<Long> removed) {
    }

    private final AntiFreeCam plugin;
    private final Nms nms;
    // Container positions this client holds, by chunk: true = shown real, false = disguised.
    private final Map<Long, Map<Long, Boolean>> chunks = new ConcurrentHashMap<>();
    private final Map<Long, long[]> tested = new HashMap<>(); // the player's thread only: check and eye block of the last test
    private final Set<Long> wet = ConcurrentHashMap.newKeySet(); // disguised containers the client holds as water
    private volatile double ex = Double.NaN, ey, ez; // the player's eyes, posted every tick

    Containers(AntiFreeCam plugin) {
        this.plugin = plugin;
        this.nms = plugin.nms;
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    void eyeAt(double x, double y, double z) {
        ex = x;
        ey = y;
        ez = z;
    }

    private boolean near(int x, int y, int z) {
        double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz <= NEAR * NEAR; // false while the eyes aren't known yet (NaN)
    }

    /** Whether the position holds a container, decided when first seen: shown if near, else disguised. Netty. */
    private boolean shown(Map<Long, Boolean> chunk, int x, int y, int z) {
        return chunk.computeIfAbsent(key(x, y, z), k -> near(x, y, z));
    }

    void forget(long chunkKey) {
        chunks.remove(chunkKey);
        wet.removeIf(k -> Occlusion.key(x(k) >> 4, z(k) >> 4) == chunkKey);
    }

    void clear() {
        chunks.clear();
        wet.clear();
    }

    /**
     * Disguises the containers of a chunk packet's sent-real sections (above {@code maxY}, not occluded), or returns
     * null when there is nothing to change. A block entity whose block isn't one any more (Paper's anti-xray turned a
     * buried chest into stone) is dropped too: it would point at the chest all the same. Netty.
     */
    Disguised disguise(Object packet, byte[] buffer, int minY, int maxY, long occludedMask) {
        List<int[]> entities = nms.blockEntityPositions(packet); // x, y, z
        if (entities.isEmpty()) return null;
        ChunkSections.States states = ChunkSections.states(buffer);
        if (states == null) return null;
        Map<Long, Boolean> chunk = chunks.computeIfAbsent(Occlusion.key(nms.chunkX(packet), nms.chunkZ(packet)), k -> new ConcurrentHashMap<>());
        Map<Integer, Map<Integer, Integer>> changes = new HashMap<>();
        Set<Long> removed = new HashSet<>();
        for (int[] p : entities) {
            int section = (p[1] - minY) >> 4;
            if (p[1] <= maxY || section < 0 || section >= states.count() || section < 64 && (occludedMask >> section & 1) != 0)
                continue; // hidden with its whole area anyway
            int index = (p[1] - minY & 15) << 8 | (p[2] & 15) << 4 | p[0] & 15;
            int state = states.blocks().state(section, index);
            if (nms.blockEntity != null && !nms.blockEntity[state]) {
                removed.add(key(p[0], p[1], p[2]));
                continue;
            }
            if (!plugin.isContainer(state) || shown(chunk, p[0], p[1], p[2])) continue;
            int disguise = disguise(state, states, section, index);
            changes.computeIfAbsent(section, k -> new HashMap<>()).put(index, disguise);
            removed.add(sent(key(p[0], p[1], p[2]), disguise));
        }
        if (removed.isEmpty()) return null;
        byte[] out = changes.isEmpty() ? buffer : ChunkSections.withBlocks(buffer, changes, nms::isAir, nms.globalBlockBits);
        return out == null ? null : new Disguised(out, removed);
    }

    /**
     * What a disguised container looks like: water if it's waterlogged, the occluded-section block if it is solid
     * (a barrel: its light must match too) or walled in on all sides, else air.
     */
    private int disguise(int state, ChunkSections.States states, int section, int index) {
        if (nms.fluid != null && nms.fluid[state]) return plugin.waterId;
        if (solid(state)) return plugin.occludedId;
        int x = index & 15, z = index >> 4 & 15, y = index >> 8;
        LightLeak.Blocks b = states.blocks();
        if (x > 0 && seeThrough(b.state(section, index - 1)) || x < 15 && seeThrough(b.state(section, index + 1))
                || z > 0 && seeThrough(b.state(section, index - 16)) || z < 15 && seeThrough(b.state(section, index + 16))
                || (y > 0 ? seeThrough(b.state(section, index - 256)) : section > 0 && seeThrough(b.state(section - 1, index + 15 * 256)))
                || (y < 15 ? seeThrough(b.state(section, index + 256))
                : section + 1 < states.count() && seeThrough(b.state(section + 1, index - 15 * 256))))
            return plugin.airId;
        return plugin.occludedId; // walled in (other containers count as walls): an air pocket would point right at it
    }

    private boolean seeThrough(int state) {
        return !nms.isOpaque(state) && !plugin.isContainer(state);
    }

    /** A disguise without the blocks around it (single block updates). */
    private int disguise(int state) {
        if (nms.fluid != null && nms.fluid[state]) return plugin.waterId;
        return solid(state) ? plugin.occludedId : plugin.airId;
    }

    /** Notes whether the disguise sent at k is water; returns k. */
    private long sent(long k, int disguise) {
        if (disguise == plugin.waterId) wet.add(k);
        else wet.remove(k);
        return k;
    }

    /** A full block that stops light, like a barrel (a chest doesn't): disguised as one, so its light matches too. */
    private boolean solid(int state) {
        return nms.lightBlock != null && nms.lightBlock[state] >= 15;
    }

    /**
     * A block update at a position the client holds real, or null to drop it: a container is disguised unless shown.
     * A change to one already disguised is dropped, as the client keeps its disguise, unless it was waterlogged or
     * drained: re-sending others (a barrel opening, a copper chest oxidizing, a chest turning) would also turn a
     * walled-in one's stone into a telltale air cell. Netty.
     */
    Object blockUpdate(Object packet, int[] pos, int state) {
        long chunkKey = Occlusion.key(pos[0] >> 4, pos[2] >> 4);
        if (!plugin.isContainer(state)) {
            gone(pos);
            return packet;
        }
        Map<Long, Boolean> chunk = chunks.computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
        long k = key(pos[0], pos[1], pos[2]);
        if (Boolean.FALSE.equals(chunk.get(k)) && (solid(state) || (nms.fluid != null && nms.fluid[state]) == wet.contains(k)))
            return null;
        if (shown(chunk, pos[0], pos[1], pos[2])) return packet;
        int disguise = disguise(state);
        sent(k, disguise);
        return nms.blockUpdate(pos, disguise);
    }

    /**
     * Whether a section update's change must go through {@link #blockUpdate} (a container to disguise, or a change to
     * a disguised one). A container that is gone is forgotten here, as the update may pass whole. Netty.
     */
    boolean wouldDisguise(int[] pos, int state) {
        if (!plugin.isContainer(state)) {
            gone(pos);
            return false;
        }
        Map<Long, Boolean> chunk = chunks.get(Occlusion.key(pos[0] >> 4, pos[2] >> 4));
        Boolean shown = chunk == null ? null : chunk.get(key(pos[0], pos[1], pos[2]));
        return shown == null ? !near(pos[0], pos[1], pos[2]) : !shown;
    }

    private void gone(int[] pos) {
        Map<Long, Boolean> chunk = chunks.get(Occlusion.key(pos[0] >> 4, pos[2] >> 4));
        if (chunk != null) chunk.remove(key(pos[0], pos[1], pos[2]));
        wet.remove(key(pos[0], pos[1], pos[2]));
    }

    /**
     * Whether a position-only packet (block entity data, a chest lid, a spawner's flames, a sound) is about a
     * disguised container. A sound on a block face (a barrel's) is checked against the block behind that face too.
     */
    boolean givesAway(Object packet) {
        int[] pos = nms.sideChannelPos(packet);
        if (pos == null) return false;
        if (disguised(pos[0], pos[1], pos[2])) return true;
        double[] at = nms.soundOrParticlePosition(packet);
        if (at == null) return false;
        return at[0] == pos[0] && disguised(pos[0] - 1, pos[1], pos[2])
                || at[1] == pos[1] && disguised(pos[0], pos[1] - 1, pos[2])
                || at[2] == pos[2] && disguised(pos[0], pos[1], pos[2] - 1);
    }

    private boolean disguised(int x, int y, int z) {
        Map<Long, Boolean> chunk = chunks.get(Occlusion.key(x >> 4, z >> 4));
        return chunk != null && Boolean.FALSE.equals(chunk.get(key(x, y, z)));
    }

    /**
     * Reveals disguised containers the player is right next to, then those they can see: the ones tested longest ago
     * first, at most {@link #BUDGET} tests per check. An out-of-sight answer is kept for a few checks while the
     * player's eyes stay in the same block. The player's thread.
     */
    void check(Player player, int check, Vector eye, Vector predicted) {
        World world = player.getWorld();
        double max = plugin.containerMaxDistance;
        long eyeBlock = key(eye.getBlockX(), eye.getBlockY(), eye.getBlockZ());
        List<long[]> candidates = new ArrayList<>(); // key, chunk key, last tested
        Set<Long> keys = new HashSet<>();
        int ecx = eye.getBlockX() >> 4, ecz = eye.getBlockZ() >> 4, reach = (int) Math.ceil(max / 16) + 1;
        for (Map.Entry<Long, Map<Long, Boolean>> chunk : chunks.entrySet()) {
            long ck = chunk.getKey();
            if (Math.abs((int) ck - ecx) > reach || Math.abs((int) (ck >> 32) - ecz) > reach) continue;
            for (Map.Entry<Long, Boolean> e : chunk.getValue().entrySet()) {
                if (e.getValue()) continue;
                long k = e.getKey();
                int x = x(k), y = y(k), z = z(k);
                if (near(x, y, z)) { // no line of sight needed: right away, outside the budget
                    reveal(player, world, chunk.getValue(), x, y, z, true);
                    continue;
                }
                double dx = x + 0.5 - eye.getX(), dy = y + 0.5 - eye.getY(), dz = z + 0.5 - eye.getZ();
                if (dx * dx + dy * dy + dz * dz > max * max) continue;
                keys.add(k);
                long[] last = tested.get(k);
                if (last != null && last[1] == eyeBlock && check - last[0] < REUSE) continue;
                candidates.add(new long[]{k, ck, last == null ? 0 : last[0]});
            }
        }
        tested.keySet().retainAll(keys);
        candidates.sort((a, b) -> Long.compare(a[2], b[2]));
        Map<Long, Boolean> blocks = new HashMap<>();
        Raycast.Solid solid = (x, y, z) -> blocks.computeIfAbsent(key(x, y, z), k -> blocksSight(world.getBlockData(x, y, z)));
        for (int i = 0; i < candidates.size() && i < BUDGET; i++) {
            long k = candidates.get(i)[0];
            tested.put(k, new long[]{check, eyeBlock});
            int x = x(k), y = y(k), z = z(k);
            Map<Long, Boolean> chunk = chunks.get(candidates.get(i)[1]);
            if (chunk == null) continue;
            if (visible(eye, x, y, z, solid) || predicted != null && visible(predicted, x, y, z, solid))
                reveal(player, world, chunk, x, y, z, true);
        }
    }

    /**
     * Sends the real block of a disguised container (and, for a spawner, its mob), or forgets it if it isn't a
     * container any more. A double chest's other half comes along.
     */
    private void reveal(Player player, World world, Map<Long, Boolean> chunk, int x, int y, int z, boolean withPartner) {
        Block block = world.getBlockAt(x, y, z);
        BlockData data = block.getBlockData();
        long k = key(x, y, z);
        int state;
        try {
            state = nms.blockId(data);
        } catch (ReflectiveOperationException e) {
            return;
        }
        if (!plugin.isContainer(state)) {
            chunk.remove(k);
            return;
        }
        if (!chunk.replace(k, false, true)) return; // shown meanwhile, or forgotten with its chunk
        wet.remove(k);
        Location at = block.getLocation();
        player.sendBlockChange(at, data);
        BlockState live = block.getState(false); // not a copy: who has it open is only known to the live one
        // A sign's text, a banner's pattern, a spawner's mob. Not for inventories: the client gets nothing from those,
        // and the server would copy the whole inventory to make the packet.
        if (live instanceof TileState tile && !(live instanceof Container)) player.sendBlockUpdate(at, tile);
        // A chest or shulker box someone has open: the client only opens its lid on a block event.
        if (live instanceof Lidded && live instanceof Container container) {
            // Spectators don't open lids; and a shulker box only starts opening on 1. The server's own events follow.
            boolean open = container.getInventory().getViewers().stream().anyMatch(h -> h.getGameMode() != GameMode.SPECTATOR);
            Object event = open ? nms.blockEvent(new int[]{x, y, z}, state, 1, 1) : null;
            if (event != null) nms.send(player, event);
        }
        if (withPartner && data instanceof Chest chest && chest.getType() != Chest.Type.SINGLE) {
            BlockFace side = chest.getType() == Chest.Type.LEFT ? clockwise(chest.getFacing()) : clockwise(clockwise(clockwise(chest.getFacing())));
            int px = x + side.getModX(), pz = z + side.getModZ();
            Map<Long, Boolean> other = chunks.get(Occlusion.key(px >> 4, pz >> 4));
            if (other != null && Boolean.FALSE.equals(other.get(key(px, y, pz)))) reveal(player, world, other, px, y, pz, false);
        }
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    /** Whether any ray from {@code eye} reaches the block's centre or one of its corners. */
    private static boolean visible(Vector eye, int x, int y, int z, Raycast.Solid solid) {
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ(), in = 0.1;
        if (Raycast.clear(ex, ey, ez, x + 0.5, y + 0.5, z + 0.5, solid)) return true;
        for (double cx : new double[]{x + in, x + 1 - in})
            for (double cy : new double[]{y + in, y + 1 - in})
                for (double cz : new double[]{z + in, z + 1 - in})
                    if (Raycast.clear(ex, ey, ez, cx, cy, cz, solid)) return true;
        return false;
    }

    /** Blocks sight for containers: also closed doors and trapdoors, slabs and stairs, as a reveal can't be undone. */
    private static boolean blocksSight(BlockData data) {
        return data.getMaterial().isOccluding() || data instanceof Door door && !door.isOpen()
                || data instanceof TrapDoor trapdoor && !trapdoor.isOpen() || data instanceof Slab || data instanceof Stairs;
    }

    private static int x(long key) {
        return (int) (key >> 38);
    }

    private static int y(long key) {
        int y = (int) (key & 0xFFF);
        return y >= 2048 ? y - 4096 : y;
    }

    private static int z(long key) {
        return (int) (key << 26 >> 38);
    }
}
