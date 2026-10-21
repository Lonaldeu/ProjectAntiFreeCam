package me.lonaldeu.antifreecam;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Reflection into the server's Mojang-mapped internals (Paper 1.20.5+), so no packet library is needed.
 * Everything is looked up once at startup. The core lookups must succeed; the side-channel ones (light, block
 * entities, sounds and the like) only switch their own filtering off when missing.
 */
final class Nms {

    private static final String GAME = "net.minecraft.network.protocol.game.";

    final Class<?> chunkPacket, blockUpdatePacket, sectionUpdatePacket, bundlePacket, addEntityPacket;
    final int globalBlockBits;
    private final Field chunkDataField, chunkBufferField;
    private final Method blockUpdatePos, blockUpdateState, blockPosX, blockPosY, blockPosZ, blockPosImmutable, runUpdates, subPackets;
    private final Method entityY, entityType, entityTypeKey, blockId, chunkX, chunkZ;
    private final Constructor<?> newBlockUpdate, newBundle;
    private Set<Integer> airIds = Set.of();

    // Side channels; null when this server version doesn't have them.
    Class<?> lightUpdatePacket, chunksBiomesPacket;
    private Field chunkLightField, blockEntitiesField, blockEntityYField, lightUpdateDataField;
    private Field skyMask, blockMask, emptySkyMask, emptyBlockMask, skyArrays, blockArrays;
    private Method lightX, lightZ;
    private final Map<Class<?>, Method> blockPosOf = new HashMap<>();
    private final Map<Class<?>, Method[]> positionOf = new HashMap<>(); // sounds and particles: getX, getY, getZ
    private Class<?> soundEntityPacket;
    private Method soundEntityId;
    // Tab-list removal; null when unavailable (line-of-sight culling then briefly drops players from the tab list).
    Class<?> playerInfoRemovePacket;
    private Method profileIds;
    private Constructor<?> newPlayerInfoRemove;
    // Sending one chunk to one player; null when unavailable (refreshes then go to every nearby player).
    private Method worldHandle, chunkSource, chunkNow, lightEngine, playerHandle, sendPacket;
    private Field playerConnection;
    private Constructor<?> newChunkPacket;
    private final Method entityUuid;
    // Cave occlusion; opaque is null when unavailable, and occlusion is then off.
    boolean[] opaque; // by block state id: blocks sight (solid render, or has a block entity)
    Class<?> forgetPacket, respawnPacket;
    private Method forgetPos, chunkSection, sectionStates, containerWrite, levelDimension, respawnInfo, infoDimension;
    private Field chunkPosX, chunkPosZ;
    private Constructor<?> newFriendlyByteBuf;

    Nms(Logger log) throws ReflectiveOperationException {
        chunkPacket = Class.forName(GAME + "ClientboundLevelChunkWithLightPacket");
        Class<?> chunkData = Class.forName(GAME + "ClientboundLevelChunkPacketData");
        blockUpdatePacket = Class.forName(GAME + "ClientboundBlockUpdatePacket");
        sectionUpdatePacket = Class.forName(GAME + "ClientboundSectionBlocksUpdatePacket");
        bundlePacket = Class.forName(GAME + "ClientboundBundlePacket");
        addEntityPacket = Class.forName(GAME + "ClientboundAddEntityPacket");
        Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
        Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState");
        Class<?> block = Class.forName("net.minecraft.world.level.block.Block");
        Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");

        chunkDataField = accessible(chunkPacket.getDeclaredField("chunkData"));
        chunkBufferField = accessible(chunkData.getDeclaredField("buffer"));

        blockUpdatePos = blockUpdatePacket.getMethod("getPos");
        blockUpdateState = blockUpdatePacket.getMethod("getBlockState");
        blockPosX = blockPos.getMethod("getX");
        blockPosY = blockPos.getMethod("getY");
        blockPosZ = blockPos.getMethod("getZ");
        chunkX = method(chunkPacket, "getX", "x"); // record-style accessors since 26.1
        chunkZ = method(chunkPacket, "getZ", "z");
        try { // since 26.1 each section also carries a fluid count
            Class.forName("net.minecraft.world.level.chunk.LevelChunkSection").getDeclaredField("fluidCount");
            ChunkSections.fluidCounts = true;
        } catch (NoSuchFieldException before261) {
            ChunkSections.fluidCounts = false;
        }
        blockPosImmutable = blockPos.getMethod("immutable");
        newBlockUpdate = blockUpdatePacket.getConstructor(blockPos, blockState);
        runUpdates = sectionUpdatePacket.getMethod("runUpdates", BiConsumer.class);
        subPackets = bundlePacket.getMethod("subPackets");
        newBundle = bundlePacket.getConstructor(Iterable.class);
        entityY = addEntityPacket.getMethod("getY");
        entityType = addEntityPacket.getMethod("getType");
        entityUuid = addEntityPacket.getMethod("getUUID");
        entityTypeKey = entityTypeClass.getMethod("getKey", entityTypeClass);
        blockId = block.getMethod("getId", blockState);

        Object registry = block.getField("BLOCK_STATE_REGISTRY").get(null);
        int states = (int) registry.getClass().getMethod("size").invoke(registry);
        globalBlockBits = 32 - Integer.numberOfLeadingZeros(states - 1);


        try {
            chunkLightField = accessible(chunkPacket.getDeclaredField("lightData"));
            blockEntitiesField = accessible(chunkData.getDeclaredField("blockEntitiesData"));
            blockEntityYField = accessible(Class.forName(GAME + "ClientboundLevelChunkPacketData$BlockEntityInfo").getDeclaredField("y"));
            chunksBiomesPacket = Class.forName(GAME + "ClientboundChunksBiomesPacket");
            lightUpdatePacket = Class.forName(GAME + "ClientboundLightUpdatePacket");
            lightUpdateDataField = accessible(lightUpdatePacket.getDeclaredField("lightData"));
            lightX = method(lightUpdatePacket, "getX", "x");
            lightZ = method(lightUpdatePacket, "getZ", "z");
            Class<?> lightData = Class.forName(GAME + "ClientboundLightUpdatePacketData");
            skyMask = accessible(lightData.getDeclaredField("skyYMask"));
            blockMask = accessible(lightData.getDeclaredField("blockYMask"));
            emptySkyMask = accessible(lightData.getDeclaredField("emptySkyYMask"));
            emptyBlockMask = accessible(lightData.getDeclaredField("emptyBlockYMask"));
            skyArrays = accessible(lightData.getDeclaredField("skyUpdates"));
            blockArrays = accessible(lightData.getDeclaredField("blockUpdates"));
            for (String name : List.of("ClientboundBlockEntityDataPacket", "ClientboundBlockEventPacket",
                    "ClientboundBlockDestructionPacket", "ClientboundLevelEventPacket")) {
                Class<?> c = Class.forName(GAME + name);
                blockPosOf.put(c, c.getMethod("getPos"));
            }
            for (String name : List.of("ClientboundSoundPacket", "ClientboundLevelParticlesPacket")) {
                Class<?> c = Class.forName(GAME + name);
                positionOf.put(c, new Method[]{method(c, "getX", "x"), method(c, "getY", "y"), method(c, "getZ", "z")});
            }
            soundEntityPacket = Class.forName(GAME + "ClientboundSoundEntityPacket");
            soundEntityId = soundEntityPacket.getMethod("getId");
        } catch (ReflectiveOperationException e) {
            log.warning("Light, block entity, sound and particle filtering is unavailable on this version: " + e);
            lightUpdatePacket = null;
            chunksBiomesPacket = null;
            chunkLightField = null;
            blockPosOf.clear();
            positionOf.clear();
            soundEntityPacket = null;
        }

        try {
            Class<?> c = Class.forName(GAME + "ClientboundPlayerInfoRemovePacket");
            profileIds = c.getMethod("profileIds");
            newPlayerInfoRemove = c.getConstructor(List.class);
            playerInfoRemovePacket = c;
        } catch (ReflectiveOperationException e) {
            log.warning("Players hidden by line of sight will flicker in the tab list on this version: " + e);
        }

        try {
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            worldHandle = Class.forName("org.bukkit.craftbukkit.CraftWorld").getMethod("getHandle");
            chunkSource = serverLevel.getMethod("getChunkSource");
            chunkNow = Class.forName("net.minecraft.server.level.ServerChunkCache").getMethod("getChunkNow", int.class, int.class);
            lightEngine = serverLevel.getMethod("getLightEngine");
            playerHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
            playerConnection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
            sendPacket = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
                    .getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
            newChunkPacket = chunkPacket.getConstructor(Class.forName("net.minecraft.world.level.chunk.LevelChunk"),
                    Class.forName("net.minecraft.world.level.lighting.LevelLightEngine"), BitSet.class, BitSet.class);
        } catch (ReflectiveOperationException e) {
            log.warning("Chunk refreshes will go to every nearby player on this version: " + e);
            newChunkPacket = null;
        }

        try {
            // Which block states block sight: the same test the client's own cave culling uses, plus blocks with a
            // block entity, so the fake chests Paper's anti-xray puts in chunk data can't link sections together.
            Class<?> stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            Method hasBlockEntity = stateBase.getMethod("hasBlockEntity");
            Method solidRender;
            Object[] solidArgs = {};
            try {
                solidRender = stateBase.getMethod("isSolidRender");
            } catch (NoSuchMethodException before1212) {
                solidRender = stateBase.getMethod("isSolidRender", Class.forName("net.minecraft.world.level.BlockGetter"), blockPos);
                solidArgs = new Object[]{Class.forName("net.minecraft.world.level.EmptyBlockGetter").getField("INSTANCE").get(null),
                        blockPos.getField("ZERO").get(null)};
            }
            boolean[] table = new boolean[states];
            for (Object state : (Iterable<?>) registry) {
                int id = (int) blockId.invoke(null, state);
                if (id >= 0 && id < states)
                    table[id] = (boolean) solidRender.invoke(state, solidArgs) || (boolean) hasBlockEntity.invoke(state);
            }
            forgetPacket = Class.forName(GAME + "ClientboundForgetLevelChunkPacket");
            forgetPos = forgetPacket.getMethod("pos");
            Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
            chunkPosX = accessible(chunkPos.getDeclaredField("x"));
            chunkPosZ = accessible(chunkPos.getDeclaredField("z"));
            respawnPacket = Class.forName(GAME + "ClientboundRespawnPacket");
            respawnInfo = respawnPacket.getMethod("commonPlayerSpawnInfo");
            infoDimension = Class.forName(GAME + "CommonPlayerSpawnInfo").getMethod("dimension");
            levelDimension = Class.forName("net.minecraft.world.level.Level").getMethod("dimension");
            chunkSection = Class.forName("net.minecraft.world.level.chunk.ChunkAccess").getMethod("getSection", int.class);
            sectionStates = Class.forName("net.minecraft.world.level.chunk.LevelChunkSection").getMethod("getStates");
            Class<?> friendlyByteBuf = Class.forName("net.minecraft.network.FriendlyByteBuf");
            containerWrite = Class.forName("net.minecraft.world.level.chunk.PalettedContainer").getMethod("write", friendlyByteBuf);
            newFriendlyByteBuf = friendlyByteBuf.getConstructor(ByteBuf.class);
            if (chunkNow == null) throw new NoSuchMethodException("per-player chunk access");
            opaque = table;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("Cave occlusion is unavailable on this version: " + e);
            opaque = null;
            forgetPacket = null;
            respawnPacket = null;
        }
    }

    boolean isOpaque(int stateId) {
        boolean[] table = opaque;
        return table != null && stateId >= 0 && stateId < table.length && table[stateId];
    }

    int stateId(Object blockState) {
        return (int) call(blockId, null, blockState);
    }

    int[] xyz(Object blockPos) {
        return new int[]{(int) call(blockPosX, blockPos), (int) call(blockPosY, blockPos), (int) call(blockPosZ, blockPos)};
    }

    int[] blockUpdatePos(Object packet) {
        return xyz(call(blockUpdatePos, packet));
    }

    int blockUpdateStateId(Object packet) {
        return stateId(call(blockUpdateState, packet));
    }

    int chunkX(Object chunkPacket) {
        return (int) call(chunkX, chunkPacket);
    }

    int chunkZ(Object chunkPacket) {
        return (int) call(chunkZ, chunkPacket);
    }

    int lightX(Object lightPacket) {
        return (int) call(lightX, lightPacket);
    }

    int lightZ(Object lightPacket) {
        return (int) call(lightZ, lightPacket);
    }

    /** The column a forget-chunk packet drops, keyed like {@link Occlusion#key}. */
    long forgottenChunk(Object packet) {
        Object pos = call(forgetPos, packet);
        return Occlusion.key((int) get(chunkPosX, pos), (int) get(chunkPosZ, pos));
    }

    /** The first of these public no-argument methods that exists (names change between versions). */
    private static Method method(Class<?> type, String... names) throws NoSuchMethodException {
        for (String name : names) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException tryNext) {
                // next name
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + String.join("/", names) + "()");
    }

    /** Identifies a world's dimension, comparable with {@link #respawnDimension}. */
    Object dimension(World world) {
        return call(levelDimension, call(worldHandle, world));
    }

    Object respawnDimension(Object respawnPacket) {
        return call(infoDimension, call(respawnInfo, respawnPacket));
    }

    /**
     * The exact connectivity of section s of chunk (x, z), from the server's own blocks; -1 if the chunk isn't
     * loaded. Call on the thread that owns the chunk.
     */
    long sectionConnectivity(World world, int x, int z, int s) {
        Object chunk = call(chunkNow, call(chunkSource, call(worldHandle, world)), x, z);
        if (chunk == null) return -1;
        ByteBuf buffer = Unpooled.buffer();
        try {
            call(containerWrite, call(sectionStates, call(chunkSection, chunk, s)), construct(newFriendlyByteBuf, buffer));
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return ChunkSections.containerConnectivity(bytes, this::isOpaque);
        } finally {
            buffer.release();
        }
    }

    /**
     * Sends chunk (x, z) to this player only, from the chunk's owning thread. Returns false when that isn't possible
     * on this version, so the caller can fall back to World#refreshChunk.
     */
    boolean sendChunk(Player p, World world, int x, int z) {
        if (newChunkPacket == null) return false;
        Object packet = chunkPacket(world, x, z);
        if (packet == null) return true; // unloaded meanwhile: nothing to send
        call(sendPacket, get(playerConnection, call(playerHandle, p)), packet);
        return true;
    }

    /** A fresh chunk packet for chunk (x, z), or null if it isn't loaded. On the chunk's owning thread. */
    Object chunkPacket(World world, int x, int z) {
        Object level = call(worldHandle, world);
        Object chunk = call(chunkNow, call(chunkSource, level), x, z);
        return chunk == null ? null : construct(newChunkPacket, chunk, call(lightEngine, level), null, null);
    }

    /**
     * Runs a real chunk packet through everything the filter does to one (parsing, connectivity, replacing sections,
     * copying the packet with new data, light and block entities). Null when it all works, else what failed, so a
     * server version the plugin can't handle is reported at startup instead of chunks silently going out unfiltered.
     */
    String selfCheck(Object packet, int minY, int replacement, int occluded) {
        try {
            byte[] in = chunkBuffer(packet);
            if (opaque != null && ChunkSections.connectivity(in, this::isOpaque) == null) return "can't read this version's chunk format";
            byte[] out = ChunkSections.hideBelow(in, minY, minY + 31, 1L << 3, replacement, occluded, this::isAir, globalBlockBits);
            if (out == null) return "can't read this version's chunk format";
            Object copy = withChunkBuffer(packet, out, true, minY, minY + 31, 1L << 3);
            return chunkBuffer(copy) == out ? null : "the rewritten chunk data didn't stick";
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    UUID entityUuid(Object addEntityPacket) {
        return (UUID) call(entityUuid, addEntityPacket);
    }

    @SuppressWarnings("unchecked")
    List<UUID> profileIds(Object playerInfoRemove) {
        return (List<UUID>) call(profileIds, playerInfoRemove);
    }

    Object playerInfoRemove(List<UUID> ids) {
        return construct(newPlayerInfoRemove, ids);
    }

    void setAirIds(Set<Integer> ids) {
        airIds = Set.copyOf(ids);
    }

    boolean isAir(int id) {
        return airIds.contains(id);
    }

    Channel channel(Player p) throws ReflectiveOperationException {
        Object handle = p.getClass().getMethod("getHandle").invoke(p);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    int blockId(BlockData data) throws ReflectiveOperationException {
        Object state = data.getClass().getMethod("getState").invoke(data);
        return (int) blockId.invoke(null, state);
    }

    byte[] chunkBuffer(Object packet) {
        return (byte[]) get(chunkBufferField, get(chunkDataField, packet));
    }

    /**
     * A copy of the chunk packet with different section data. The original may be shared with other players.
     * With {@code sideChannels}, block entities and light at or below {@code maxY} or in occluded sections go too.
     */
    Object withChunkBuffer(Object packet, byte[] buffer, boolean sideChannels, int minY, int maxY, long occludedMask) {
        Object data = get(chunkDataField, packet);
        if (!sideChannels || chunkLightField == null)
            return Copies.with(packet, "chunkData", Copies.with(data, "buffer", buffer));
        List<Object> kept = new ArrayList<>();
        for (Object info : (List<?>) get(blockEntitiesField, data)) {
            int y = ((Number) get(blockEntityYField, info)).intValue(), section = (y - minY) >> 4; // a short since 26.1
            if (y > maxY && (section < 0 || section >= 64 || (occludedMask >> section & 1) == 0)) kept.add(info);
        }
        return Copies.with(packet, "chunkData", Copies.with(data, "buffer", buffer, "blockEntitiesData", kept),
                "lightData", hideLight(get(chunkLightField, packet), minY, maxY, occludedMask));
    }

    /** A copy of a light update packet without the light at or below {@code maxY} or in occluded sections, or the packet itself. */
    Object withoutLight(Object packet, int minY, int maxY, long occludedMask) {
        if (lightUpdatePacket == null || maxY < minY && occludedMask == 0) return packet;
        return Copies.with(packet, "lightData", hideLight(get(lightUpdateDataField, packet), minY, maxY, occludedMask));
    }

    @SuppressWarnings("unchecked")
    private Object hideLight(Object lightData, int minY, int maxY, long occludedMask) {
        var sky = ChunkSections.hideLight(new ChunkSections.LightLayer((BitSet) get(skyMask, lightData),
                (BitSet) get(emptySkyMask, lightData), (List<byte[]>) get(skyArrays, lightData)), minY, maxY, occludedMask);
        var blocks = ChunkSections.hideLight(new ChunkSections.LightLayer((BitSet) get(blockMask, lightData),
                (BitSet) get(emptyBlockMask, lightData), (List<byte[]>) get(blockArrays, lightData)), minY, maxY, occludedMask);
        return Copies.with(lightData, "skyYMask", sky.mask(), "emptySkyYMask", sky.empty(), "skyUpdates", sky.arrays(),
                "blockYMask", blocks.mask(), "emptyBlockYMask", blocks.empty(), "blockUpdates", blocks.arrays());
    }

    /** The block of a position-only packet (block entity data, block events, mining cracks, level events, sounds, particles), else null. */
    int[] sideChannelPos(Object packet) {
        Method pos = blockPosOf.get(packet.getClass());
        if (pos != null) return xyz(call(pos, packet));
        double[] at = soundOrParticlePosition(packet);
        return at == null ? null : new int[]{(int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2])};
    }

    /** The entity a sound is attached to, or null if it isn't an entity sound. */
    Integer soundEntityId(Object packet) {
        return packet.getClass() == soundEntityPacket ? (Integer) call(soundEntityId, packet) : null;
    }

    /** Where a positional sound or particle plays, or null for other packets. */
    double[] soundOrParticlePosition(Object packet) {
        Method[] xyz = positionOf.get(packet.getClass());
        if (xyz == null) return null;
        return new double[]{(double) call(xyz[0], packet), (double) call(xyz[1], packet), (double) call(xyz[2], packet)};
    }

    /** Calls {@code action(pos, state)} for each change in a section update packet. The pos object is reused. */
    void forEachChange(Object sectionUpdate, BiConsumer<Object, Object> action) {
        call(runUpdates, sectionUpdate, action);
    }

    Object blockUpdate(Object pos, Object state) {
        return construct(newBlockUpdate, call(blockPosImmutable, pos), state);
    }

    List<Object> subPackets(Object bundle) {
        List<Object> list = new ArrayList<>();
        ((Iterable<?>) call(subPackets, bundle)).forEach(list::add);
        return list;
    }

    Object bundle(List<Object> packets) {
        return construct(newBundle, packets);
    }

    double entityY(Object packet) {
        return (double) call(entityY, packet);
    }

    /** E.g. "minecraft:zombie". */
    String entityTypeKey(Object packet) {
        return call(entityTypeKey, null, call(entityType, packet)).toString();
    }

    private static Field accessible(Field f) {
        f.setAccessible(true);
        return f;
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object construct(Constructor<?> c, Object... args) {
        try {
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
