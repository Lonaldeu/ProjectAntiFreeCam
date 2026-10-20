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
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Reflection into the server's Mojang-mapped internals (Paper 1.20.5+), so no packet library is needed.
 * Everything is looked up once at startup; a missing member fails there, not mid-game.
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

    Nms() throws ReflectiveOperationException {
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

    /** A copy of the chunk packet carrying different section data. The original may be shared with other players. */
    Object withChunkBuffer(Object packet, byte[] buffer) {
        Object data = copy(get(chunkDataField, packet), chunkDataFields);
        set(chunkBufferField, data, buffer);
        Object copy = copy(packet, chunkPacketFields);
        set(chunkDataField, copy, data);
        return copy;
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
