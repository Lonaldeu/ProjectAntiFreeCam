package me.lonaldeu.antifreecam;

import io.netty.channel.Channel;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
    private final Field[] chunkPacketFields, chunkDataFields;
    private final Method blockUpdatePos, blockPosY, blockPosImmutable, runUpdates, subPackets;
    private final Method entityY, entityType, entityTypeKey, blockId;
    private final Constructor<?> newBlockUpdate, newBundle;
    private final Unsafe unsafe;
    private Set<Integer> airIds = Set.of();

    // Side channels; null when this server version doesn't have them.
    Class<?> lightUpdatePacket, chunksBiomesPacket;
    private Field chunkLightField, blockEntitiesField, blockEntityYField, lightUpdateDataField;
    private Field skyMask, blockMask, emptySkyMask, emptyBlockMask, skyArrays, blockArrays;
    private Field[] lightPacketFields, lightDataFields;
    private final Map<Class<?>, Method> blockPosOf = new HashMap<>(), yOf = new HashMap<>();
    // Tab-list removal; null when unavailable (line-of-sight culling then briefly drops players from the tab list).
    Class<?> playerInfoRemovePacket;
    private Method profileIds;
    private Constructor<?> newPlayerInfoRemove;

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
        chunkPacketFields = instanceFields(chunkPacket);
        chunkDataFields = instanceFields(chunkData);

        blockUpdatePos = blockUpdatePacket.getMethod("getPos");
        blockPosY = blockPos.getMethod("getY");
        blockPosImmutable = blockPos.getMethod("immutable");
        newBlockUpdate = blockUpdatePacket.getConstructor(blockPos, blockState);
        runUpdates = sectionUpdatePacket.getMethod("runUpdates", BiConsumer.class);
        subPackets = bundlePacket.getMethod("subPackets");
        newBundle = bundlePacket.getConstructor(Iterable.class);
        entityY = addEntityPacket.getMethod("getY");
        entityType = addEntityPacket.getMethod("getType");
        entityTypeKey = entityTypeClass.getMethod("getKey", entityTypeClass);
        blockId = block.getMethod("getId", blockState);

        Object registry = block.getField("BLOCK_STATE_REGISTRY").get(null);
        int states = (int) registry.getClass().getMethod("size").invoke(registry);
        globalBlockBits = 32 - Integer.numberOfLeadingZeros(states - 1);

        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        unsafe = (Unsafe) f.get(null);

        try {
            chunkLightField = accessible(chunkPacket.getDeclaredField("lightData"));
            blockEntitiesField = accessible(chunkData.getDeclaredField("blockEntitiesData"));
            blockEntityYField = accessible(Class.forName(GAME + "ClientboundLevelChunkPacketData$BlockEntityInfo").getDeclaredField("y"));
            chunksBiomesPacket = Class.forName(GAME + "ClientboundChunksBiomesPacket");
            lightUpdatePacket = Class.forName(GAME + "ClientboundLightUpdatePacket");
            lightUpdateDataField = accessible(lightUpdatePacket.getDeclaredField("lightData"));
            Class<?> lightData = Class.forName(GAME + "ClientboundLightUpdatePacketData");
            skyMask = accessible(lightData.getDeclaredField("skyYMask"));
            blockMask = accessible(lightData.getDeclaredField("blockYMask"));
            emptySkyMask = accessible(lightData.getDeclaredField("emptySkyYMask"));
            emptyBlockMask = accessible(lightData.getDeclaredField("emptyBlockYMask"));
            skyArrays = accessible(lightData.getDeclaredField("skyUpdates"));
            blockArrays = accessible(lightData.getDeclaredField("blockUpdates"));
            lightPacketFields = instanceFields(lightUpdatePacket);
            lightDataFields = instanceFields(lightData);
            for (String name : List.of("ClientboundBlockEntityDataPacket", "ClientboundBlockEventPacket",
                    "ClientboundBlockDestructionPacket", "ClientboundLevelEventPacket")) {
                Class<?> c = Class.forName(GAME + name);
                blockPosOf.put(c, c.getMethod("getPos"));
            }
            for (String name : List.of("ClientboundSoundPacket", "ClientboundLevelParticlesPacket")) {
                Class<?> c = Class.forName(GAME + name);
                yOf.put(c, c.getMethod("getY"));
            }
        } catch (ReflectiveOperationException e) {
            log.warning("Light, block entity, sound and particle filtering is unavailable on this version: " + e);
            lightUpdatePacket = null;
            chunksBiomesPacket = null;
            chunkLightField = null;
            blockPosOf.clear();
            yOf.clear();
        }

        try {
            Class<?> c = Class.forName(GAME + "ClientboundPlayerInfoRemovePacket");
            profileIds = c.getMethod("profileIds");
            newPlayerInfoRemove = c.getConstructor(List.class);
            playerInfoRemovePacket = c;
        } catch (ReflectiveOperationException e) {
            log.warning("Players hidden by line of sight will flicker in the tab list on this version: " + e);
        }
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
     * With {@code sideChannels}, block entities and light at or below {@code maxY} are removed too.
     */
    Object withChunkBuffer(Object packet, byte[] buffer, boolean sideChannels, int minY, int maxY) {
        Object data = copy(get(chunkDataField, packet), chunkDataFields);
        set(chunkBufferField, data, buffer);
        Object copy = copy(packet, chunkPacketFields);
        set(chunkDataField, copy, data);
        if (sideChannels && chunkLightField != null) {
            List<Object> kept = new ArrayList<>();
            for (Object info : (List<?>) get(blockEntitiesField, data))
                if ((int) get(blockEntityYField, info) > maxY) kept.add(info);
            set(blockEntitiesField, data, kept);
            set(chunkLightField, copy, hideLight(get(chunkLightField, packet), minY, maxY));
        }
        return copy;
    }

    /** A copy of a light update packet with the light at or below {@code maxY} removed, or the packet itself. */
    Object withoutLight(Object packet, int minY, int maxY) {
        if (lightUpdatePacket == null) return packet;
        Object copy = copy(packet, lightPacketFields);
        set(lightUpdateDataField, copy, hideLight(get(lightUpdateDataField, packet), minY, maxY));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object hideLight(Object lightData, int minY, int maxY) {
        Object copy = copy(lightData, lightDataFields);
        var sky = ChunkSections.hideLight(new ChunkSections.LightLayer((BitSet) get(skyMask, lightData),
                (BitSet) get(emptySkyMask, lightData), (List<byte[]>) get(skyArrays, lightData)), minY, maxY);
        var blocks = ChunkSections.hideLight(new ChunkSections.LightLayer((BitSet) get(blockMask, lightData),
                (BitSet) get(emptyBlockMask, lightData), (List<byte[]>) get(blockArrays, lightData)), minY, maxY);
        set(skyMask, copy, sky.mask());
        set(emptySkyMask, copy, sky.empty());
        set(skyArrays, copy, sky.arrays());
        set(blockMask, copy, blocks.mask());
        set(emptyBlockMask, copy, blocks.empty());
        set(blockArrays, copy, blocks.arrays());
        return copy;
    }

    /** The Y of a position-only packet (block entity data, block events, mining cracks, level events, sounds, particles), else null. */
    Integer sideChannelY(Object packet) {
        Method pos = blockPosOf.get(packet.getClass());
        if (pos != null) return y(call(pos, packet));
        Method y = yOf.get(packet.getClass());
        return y == null ? null : (int) Math.floor((double) call(y, packet));
    }

    int blockUpdateY(Object packet) {
        return y(call(blockUpdatePos, packet));
    }

    int y(Object blockPos) {
        return (int) call(blockPosY, blockPos);
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

    private static Field[] instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers())) fields.add(accessible(f));
        return fields.toArray(Field[]::new);
    }

    private Object copy(Object source, Field[] fields) {
        try {
            Object copy = unsafe.allocateInstance(source.getClass());
            for (Field f : fields) f.set(copy, f.get(source));
            return copy;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Field f, Object target) {
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Field f, Object target, Object value) {
        try {
            f.set(target, value);
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
