package me.lonaldeu.antifreecam;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.lang.ref.Reference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Reflection into the server's Mojang-mapped internals (Paper 1.20.5+), so no packet library is needed.
 * Everything is looked up once at startup. The core lookups must succeed; the side-channel ones (light, block
 * entities, sounds and the like) only switch their own filtering off when missing.
 */
final class Nms {

    private static final String GAME = "net.minecraft.network.protocol.game.";
    private final Logger log;
    private volatile boolean warnedLight;
    // Paper's anti-xray default hidden-blocks that aren't solid: its fakes can be these.
    private static final Set<String> FAKES = Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest");

    final Class<?> chunkPacket, blockUpdatePacket, sectionUpdatePacket, bundlePacket, addEntityPacket;
    final int globalBlockBits;
    private final Field chunkDataField, chunkBufferField;
    private final Method blockUpdatePos, blockUpdateState, blockPosX, blockPosY, blockPosZ, blockPosImmutable, runUpdates, subPackets;
    private final Method entityY, entityType, entityTypeKey, blockId, chunkX, chunkZ;
    private final Constructor<?> newBlockUpdate, newBundle, newBlockPos;
    private final Method stateById;
    private Set<Integer> airIds = Set.of();

    // Side channels; null when this server version doesn't have them.
    Class<?> lightUpdatePacket, chunksBiomesPacket;
    private Field chunkLightField, blockEntitiesField, blockEntityYField, blockEntityXZField, lightUpdateDataField;
    private Field skyMask, blockMask, emptySkyMask, emptyBlockMask, skyArrays, blockArrays;
    private Method lightX, lightZ;
    private final Map<Class<?>, Method> blockPosOf = new HashMap<>();
    private final Map<Class<?>, Method[]> positionOf = new HashMap<>(); // sounds and particles: getX, getY, getZ
    private Class<?> soundEntityPacket;
    private Method soundEntityId;
    private Class<?> explodePacket;
    private Method[] explodePosition; // getX, getY, getZ; or since 1.21.2 center, then its x, y, z
    private Field explodeBlocks; // the destroyed blocks, sent until 1.21.2
    // Tab-list removal; null when unavailable (line-of-sight culling then briefly drops players from the tab list).
    Class<?> playerInfoRemovePacket;
    private Method profileIds;
    private Constructor<?> newPlayerInfoRemove;
    // Sending one chunk to one player; null when unavailable (refreshes then go to every nearby player).
    private Method worldHandle, chunkSource, chunkNow, lightEngine, playerHandle, sendPacket;
    private Field playerConnection;
    private Constructor<?> newChunkPacket;
    // Pairing one entity with one player through the server's entity tracker; trackerUpdate is null when unavailable.
    private Method entityHandle, trackedEntityOf, trackerRemove, trackerUpdate;
    private Field trackerField;
    // Whether other plugins hide an entity, and listing a player again; invertedVisibility is null when unavailable.
    private Field invertedVisibility;
    private Method newPlayerInfoAdd;
    // Packets about one entity (movement, data, equipment and the like): their entity id field, by class. Empty, and
    // addedEntityId null, when unavailable.
    private final Map<Class<?>, Field> entityIdField = new HashMap<>();
    private Method addedEntityId, removedEntityIds;
    Class<?> removeEntitiesPacket, setPassengersPacket;
    // Entity movement, for heights: relative moves, and absolute teleports and position syncs (since 1.21.2 a
    // teleport's parts can each be relative). moveYa is null when unavailable.
    private Class<?> movePacket, teleportPacket, syncPacket;
    private Method moveYa, moveHasPos, teleportY, teleportChange, teleportRelatives, syncValues, newPosition, passengersOf;
    private Field vecY;
    private Object relativeY;
    private Constructor<?> newRemoveEntities;
    private Constructor<?> newBlockEvent; // null when unavailable
    private Class<?> loginPacket, seedRespawnPacket; // the packets with the hashed seed; null when unavailable
    private Method loginSpawnInfo, respawnSpawnInfo;
    private Method blockOf, addedEntityData;
    Class<?> addOrbPacket; // experience orbs had their own spawn packet until 1.21.5
    private Method orbId, orbY;
    private final Method entityUuid;
    // Cave occlusion; opaque is null when unavailable, and occlusion is then off.
    boolean[] opaque; // by block state id: blocks sight (solid render, or a chest Paper's anti-xray may fake)
    // By block state id, null when unavailable: the block's id ("minecraft:chest"), the light it gives off and how
    // much it dims light passing through, and whether it holds a fluid (waterlogged).
    String[] blockKeys;
    byte[] emission, lightBlock;
    boolean[] fluid, blockEntity;
    Class<?> forgetPacket, respawnPacket;
    private Method forgetPos, chunkSection, sectionStates, containerWrite, levelDimension, respawnInfo, infoDimension;
    private Field chunkPosX, chunkPosZ;
    private Constructor<?> newFriendlyByteBuf;

    Nms(Logger log) throws ReflectiveOperationException {
        this.log = log;
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
        newBlockPos = blockPos.getConstructor(int.class, int.class, int.class);
        stateById = block.getMethod("stateById", int.class);
        runUpdates = sectionUpdatePacket.getMethod("runUpdates", BiConsumer.class);
        subPackets = bundlePacket.getMethod("subPackets");
        newBundle = bundlePacket.getConstructor(Iterable.class);
        entityY = addEntityPacket.getMethod("getY");
        entityType = addEntityPacket.getMethod("getType");
        entityUuid = addEntityPacket.getMethod("getUUID");
        entityTypeKey = entityTypeClass.getMethod("getKey", entityTypeClass);
        blockId = block.getMethod("getId", blockState);
        try { // a chest lid, for a disguised one shown while someone has it open
            newBlockEvent = Class.forName(GAME + "ClientboundBlockEventPacket").getConstructor(blockPos, block, int.class, int.class);
            blockOf = blockState.getMethod("getBlock");
        } catch (ReflectiveOperationException e) {
            log.warning("Chests shown while someone has them open show closed on this version: " + e);
        }
        try { // a projectile's spawn carries its owner's entity id
            addedEntityData = addEntityPacket.getMethod("getData");
        } catch (NoSuchMethodException e) {
            log.warning("Projectiles of hidden entities show for a moment on this version: " + e);
        }

        Object registry = block.getField("BLOCK_STATE_REGISTRY").get(null);
        int states = (int) registry.getClass().getMethod("size").invoke(registry);
        globalBlockBits = 32 - Integer.numberOfLeadingZeros(states - 1);


        try {
            chunkLightField = accessible(chunkPacket.getDeclaredField("lightData"));
            blockEntitiesField = accessible(chunkData.getDeclaredField("blockEntitiesData"));
            Class<?> blockEntityInfo = Class.forName(GAME + "ClientboundLevelChunkPacketData$BlockEntityInfo");
            blockEntityYField = accessible(blockEntityInfo.getDeclaredField("y"));
            blockEntityXZField = accessible(blockEntityInfo.getDeclaredField("packedXZ"));
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
            Class<?> c = Class.forName(GAME + "ClientboundExplodePacket");
            try {
                explodePosition = new Method[]{c.getMethod("getX"), c.getMethod("getY"), c.getMethod("getZ")};
                explodeBlocks = accessible(c.getDeclaredField("toBlow"));
            } catch (NoSuchMethodException since1212) {
                Method center = c.getMethod("center");
                Class<?> vec = center.getReturnType();
                explodePosition = new Method[]{center, vec.getMethod("x"), vec.getMethod("y"), vec.getMethod("z")};
            }
            explodePacket = c;
        } catch (ReflectiveOperationException e) {
            log.warning("Explosions in hidden areas will be shown on this version: " + e);
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
            if (sendPacket == null) throw new NoSuchMethodException("player access");
            entityHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity").getMethod("getHandle");
            Class<?> tracked = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            trackerRemove = accessible(tracked.getDeclaredMethod("removePlayer", serverPlayer));
            trackerUpdate = accessible(tracked.getDeclaredMethod("updatePlayer", serverPlayer));
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            try { // each entity knows its tracker: a method since 1.21, Paper's own field before
                trackedEntityOf = entity.getMethod("moonrise$getTrackedEntity");
            } catch (NoSuchMethodException before121) {
                trackerField = entity.getField("tracker");
            }
        } catch (ReflectiveOperationException e) {
            log.warning("Entities other plugins show to some players only aren't hidden underground on this version: " + e);
            trackerUpdate = null;
        }

        try {
            if (sendPacket == null || playerInfoRemovePacket == null) throw new NoSuchMethodException("player access");
            invertedVisibility = accessible(Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer")
                    .getDeclaredField("invertedVisibilityEntities"));
            Class<?> update = Class.forName(GAME + "ClientboundPlayerInfoUpdatePacket");
            try { // Paper's: lists the player only where Player#unlistPlayer allows
                newPlayerInfoAdd = update.getMethod("createPlayerInitializing", Collection.class,
                        Class.forName("net.minecraft.server.level.ServerPlayer"));
            } catch (NoSuchMethodException vanilla) {
                newPlayerInfoAdd = update.getMethod("createPlayerInitializing", Collection.class);
            }
        } catch (ReflectiveOperationException e) {
            log.warning("Vanished players hidden by line of sight stay in the tab list on this version: " + e);
            invertedVisibility = null;
        }

        try {
            Class<?> c = Class.forName(GAME + "ClientboundAddExperienceOrbPacket");
            orbId = c.getMethod("getId");
            orbY = c.getMethod("getY");
            addOrbPacket = c;
        } catch (ReflectiveOperationException since1215) {
            // orbs spawn like other entities
        }

        try {
            addedEntityId = method(addEntityPacket, "getId", "id");
            removeEntitiesPacket = Class.forName(GAME + "ClientboundRemoveEntitiesPacket");
            removedEntityIds = method(removeEntitiesPacket, "getEntityIds", "entityIds");
            for (String name : List.of("ClientboundMoveEntityPacket$Pos", "ClientboundMoveEntityPacket$PosRot",
                    "ClientboundMoveEntityPacket$Rot", "ClientboundTeleportEntityPacket", "ClientboundEntityPositionSyncPacket",
                    "ClientboundSetEntityDataPacket", "ClientboundSetEquipmentPacket", "ClientboundUpdateAttributesPacket",
                    "ClientboundRotateHeadPacket", "ClientboundSetEntityMotionPacket", "ClientboundAnimatePacket",
                    "ClientboundEntityEventPacket", "ClientboundHurtAnimationPacket", "ClientboundSetPassengersPacket",
                    "ClientboundUpdateMobEffectPacket", "ClientboundRemoveMobEffectPacket", "ClientboundMoveMinecartPacket",
                    "ClientboundDamageEventPacket")) {
                Class<?> c;
                try {
                    c = Class.forName(GAME + name);
                } catch (ClassNotFoundException notOnThisVersion) { // e.g. position sync and minecart moves are newer
                    continue;
                }
                entityIdField.put(c, entityIdField(c));
            }
        } catch (ReflectiveOperationException e) {
            log.warning("Updates about entities whose spawn was held back still go out on this version: " + e);
            addedEntityId = null;
            entityIdField.clear();
        }

        try {
            movePacket = Class.forName(GAME + "ClientboundMoveEntityPacket");
            moveYa = movePacket.getMethod("getYa");
            moveHasPos = movePacket.getMethod("hasPosition");
            teleportPacket = Class.forName(GAME + "ClientboundTeleportEntityPacket");
            try {
                teleportY = teleportPacket.getMethod("getY");
            } catch (NoSuchMethodException since1212) { // the new position, and which of its parts are relative
                teleportChange = teleportPacket.getMethod("change");
                teleportRelatives = teleportPacket.getMethod("relatives");
                newPosition = teleportChange.getReturnType().getMethod("position");
                vecY = newPosition.getReturnType().getField("y");
                for (Object c : Class.forName("net.minecraft.world.entity.Relative").getEnumConstants())
                    if (((Enum<?>) c).name().equals("Y")) relativeY = c;
                syncPacket = Class.forName(GAME + "ClientboundEntityPositionSyncPacket");
                syncValues = syncPacket.getMethod("values");
            }
            setPassengersPacket = Class.forName(GAME + "ClientboundSetPassengersPacket");
            passengersOf = setPassengersPacket.getMethod("getPassengers");
            // entityIdField is empty, and removeEntitiesPacket maybe null, when entity ids couldn't be read above
            if (entityIdField.isEmpty() || teleportY == null && relativeY == null) throw new NoSuchFieldException("Relative.Y");
            newRemoveEntities = removeEntitiesPacket.getConstructor(int[].class);
        } catch (ReflectiveOperationException e) {
            log.warning("Entities that go below a player's cut are hidden a moment later on this version: " + e);
            moveYa = null;
            setPassengersPacket = null;
        }

        try {
            Class<?> stateBase = Class.forName("net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
            Method getBlock = stateBase.getMethod("getBlock"), emits = stateBase.getMethod("getLightEmission");
            Method fluidState = stateBase.getMethod("getFluidState"), noFluid = fluidState.getReturnType().getMethod("isEmpty");
            Method hasEntity = stateBase.getMethod("hasBlockEntity");
            // The block's id through CraftBukkit's Material: plain to look up on every version.
            Method material = Class.forName("org.bukkit.craftbukkit.util.CraftMagicNumbers").getMethod("getMaterial", block);
            Method dims;
            Object[] dimsArgs = {};
            try {
                dims = method(stateBase, "getLightDampening", "getLightBlock"); // 26.x, 1.21.2+
            } catch (NoSuchMethodException before1212) {
                dims = stateBase.getMethod("getLightBlock", Class.forName("net.minecraft.world.level.BlockGetter"), blockPos);
                dimsArgs = new Object[]{Class.forName("net.minecraft.world.level.EmptyBlockGetter").getField("INSTANCE").get(null),
                        blockPos.getField("ZERO").get(null)};
            }
            String[] keys = new String[states];
            byte[] emits0 = new byte[states], dims0 = new byte[states];
            boolean[] fluids = new boolean[states], entities = new boolean[states];
            for (Object state : (Iterable<?>) registry) {
                int id = (int) blockId.invoke(null, state);
                if (id < 0 || id >= states) continue;
                keys[id] = ((org.bukkit.Material) material.invoke(null, getBlock.invoke(state))).getKey().toString();
                emits0[id] = (byte) (int) emits.invoke(state);
                dims0[id] = (byte) (int) dims.invoke(state, dimsArgs);
                fluids[id] = !(boolean) noFluid.invoke(fluidState.invoke(state));
                entities[id] = (boolean) hasEntity.invoke(state);
            }
            blockKeys = keys;
            emission = emits0;
            lightBlock = dims0;
            fluid = fluids;
            blockEntity = entities;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("Light hiding next to hidden areas and container hiding are unavailable on this version: " + e);
        }

        try {
            // Which block states block sight: the same test the client's own cave culling uses, plus the containers
            // Paper's anti-xray may put in chunk data as fakes, so they can't link sections together.
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
                if (id >= 0 && id < states) table[id] = (boolean) solidRender.invoke(state, solidArgs)
                        || (blockKeys != null ? FAKES.contains(blockKeys[id]) : (boolean) hasBlockEntity.invoke(state));
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

        try { // the hashed world seed, in the spawn info of the login and respawn packets
            loginPacket = Class.forName(GAME + "ClientboundLoginPacket");
            seedRespawnPacket = Class.forName(GAME + "ClientboundRespawnPacket");
            loginSpawnInfo = loginPacket.getMethod("commonPlayerSpawnInfo");
            respawnSpawnInfo = seedRespawnPacket.getMethod("commonPlayerSpawnInfo");
            loginSpawnInfo.getReturnType().getMethod("seed");
        } catch (ReflectiveOperationException e) {
            log.warning("The hashed seed is sent as it is on this version: " + e);
            loginPacket = null;
        }
    }

    /** A login or respawn packet with {@code seed} as the hashed world seed; any other packet as it is. */
    Object withHashedSeed(Object packet, long seed) {
        Class<?> type = packet.getClass();
        if (loginPacket == null || type != loginPacket && type != seedRespawnPacket) return packet;
        Object info = call(type == loginPacket ? loginSpawnInfo : respawnSpawnInfo, packet);
        return Copies.with(packet, "commonPlayerSpawnInfo", Copies.with(info, "seed", seed));
    }

    /**
     * Runs {@code each} on every new connection as the server sets it up, before any packet, through Paper's hook for
     * that; false when unavailable.
     */
    boolean onNewChannels(String name, java.util.function.Consumer<Channel> each) {
        try {
            Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            Class<?> listener = Class.forName("io.papermc.paper.network.ChannelInitializeListener");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (self, m, args) -> switch (m.getName()) {
                        case "afterInitChannel" -> {
                            each.accept((Channel) args[0]);
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> name;
                        default -> null;
                    });
            holder.getMethod("addListener", net.kyori.adventure.key.Key.class, listener)
                    .invoke(null, net.kyori.adventure.key.Key.key("antifreecam", name), proxy);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("New connections can't be hooked before they log in on this version: " + e);
            return false;
        }
    }

    void stopNewChannels(String name) {
        try {
            Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder")
                    .getMethod("removeListener", net.kyori.adventure.key.Key.class)
                    .invoke(null, net.kyori.adventure.key.Key.key("antifreecam", name));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // nothing was added
        }
    }

    /**
     * Whether Paper places this world's ores and other features from seeds of their own
     * (feature-seeds.generate-random-seeds-for-all), not from the world seed; null when that can't be told.
     */
    Boolean randomFeatureSeeds(World world) {
        try {
            Object level = call(worldHandle, world);
            Object config = level.getClass().getMethod("paperConfig").invoke(level);
            Object seeds = config.getClass().getField("featureSeeds").get(config);
            return seeds.getClass().getField("generateRandomSeedsForAll").getBoolean(seeds);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
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
        return packet == null || send(p, packet); // unloaded meanwhile: nothing to send
    }

    /**
     * Has the server send entity {@code e} to this player again, as if it had just come into range (when they may
     * see it). False when that isn't possible on this version. On the entity's thread.
     */
    boolean resendEntity(Player p, org.bukkit.entity.Entity e) {
        Object tracked = trackedEntity(e);
        if (tracked == null) return false;
        Object player = call(playerHandle, p);
        call(trackerRemove, tracked, player);
        call(trackerUpdate, tracked, player);
        return true;
    }

    /** Has the server take entity {@code e} off this player's client; it may send it again by itself. On the entity's thread. */
    boolean untrackEntity(Player p, org.bukkit.entity.Entity e) {
        Object tracked = trackedEntity(e);
        if (tracked == null) return false;
        call(trackerRemove, tracked, call(playerHandle, p));
        return true;
    }

    private Object trackedEntity(org.bukkit.entity.Entity e) {
        if (trackerUpdate == null) return null;
        Object entity = call(entityHandle, e);
        return trackedEntityOf != null ? call(trackedEntityOf, entity) : get(trackerField, entity);
    }

    /** Whether a plugin other than {@code self} hides {@code entity} (visible by default) from {@code viewer}. Viewer's thread. */
    boolean hiddenByOthers(Player viewer, org.bukkit.entity.Entity entity, Object self) {
        if (invertedVisibility == null) return false;
        Set<?> plugins = (Set<?>) ((Map<?, ?>) get(invertedVisibility, viewer)).get(entity.getUniqueId());
        if (plugins == null) return false;
        for (Object plugin : plugins) if (plugin == null || ((Reference<?>) plugin).get() != self) return true;
        return false;
    }

    /** A packet listing {@code p} in {@code viewer}'s tab list again, or null when unavailable on this version. */
    Object playerInfoAdd(Player viewer, Player p) {
        if (invertedVisibility == null) return null;
        List<Object> players = List.of(call(playerHandle, p));
        return newPlayerInfoAdd.getParameterCount() == 2 ? call(newPlayerInfoAdd, null, players, call(playerHandle, viewer))
                : call(newPlayerInfoAdd, null, players);
    }

    /** The entity id field of a packet about one entity: entityId, id, entity or vehicle, here or in a superclass. */
    private static Field entityIdField(Class<?> packet) throws NoSuchFieldException {
        for (Class<?> c = packet; c != Object.class; c = c.getSuperclass())
            for (String name : List.of("entityId", "id", "entity", "vehicle")) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (f.getType() == int.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) return accessible(f);
                } catch (NoSuchFieldException tryNext) {
                    // next name
                }
            }
        throw new NoSuchFieldException(packet.getName() + ": entity id");
    }

    int orbId(Object addOrb) {
        return (int) call(orbId, addOrb);
    }

    double orbY(Object addOrb) {
        return (double) call(orbY, addOrb);
    }

    /** Whether entities can be paired with one player through the server's entity tracker on this version. */
    boolean tracksEntities() {
        return trackerUpdate != null;
    }

    /** The id of the entity an add-entity packet spawns, or -1 when unavailable on this version. */
    int addedEntityId(Object addEntity) {
        return addedEntityId == null ? -1 : (int) call(addedEntityId, addEntity);
    }

    /** The ids of the entities a remove-entities packet removes. */
    Iterable<?> removedEntityIds(Object removeEntities) {
        return (Iterable<?>) call(removedEntityIds, removeEntities);
    }

    /** Whether {@link #movedY} can follow entity movement on this version. */
    boolean tracksMoves() {
        return moveYa != null;
    }

    /**
     * The height a movement packet puts an entity at that was at {@code y}, or NaN when the packet doesn't move it
     * (a turn, or not a movement packet).
     */
    double movedY(Object packet, double y) {
        Class<?> type = packet.getClass();
        if (type.getSuperclass() == movePacket) // the Pos, PosRot and Rot moves: a change in 1/4096 blocks
            return (boolean) call(moveHasPos, packet) ? y + (short) call(moveYa, packet) / 4096.0 : Double.NaN;
        if (type == teleportPacket) {
            if (teleportY != null) return (double) call(teleportY, packet);
            double to = (double) get(vecY, call(newPosition, call(teleportChange, packet)));
            return ((Set<?>) call(teleportRelatives, packet)).contains(relativeY) ? y + to : to;
        }
        if (type == syncPacket) return (double) get(vecY, call(newPosition, call(syncValues, packet)));
        return Double.NaN;
    }

    int[] passengers(Object setPassengers) {
        return (int[]) call(passengersOf, setPassengers);
    }

    /** A packet telling the client these entities are gone. */
    Object removeEntities(int... ids) {
        return construct(newRemoveEntities, (Object) ids);
    }

    /** The data field of a spawn: for a projectile, its owner's entity id (0 for none). */
    int addedEntityData(Object addEntity) {
        return addedEntityData == null ? 0 : (int) call(addedEntityData, addEntity);
    }

    /** A block event (for a chest lid: type 1, data how many have it open) for a block of state {@code state}, or null. */
    Object blockEvent(int[] pos, int state, int type, int data) {
        if (newBlockEvent == null) return null;
        return construct(newBlockEvent, construct(newBlockPos, pos[0], pos[1], pos[2]), call(blockOf, call(stateById, null, state)), type, data);
    }

    /** The entity a packet about one entity (movement, data, equipment and the like) is about, else -1. */
    int entityIdOf(Object packet) {
        Field f = entityIdField.get(packet.getClass());
        return f == null ? -1 : (int) get(f, packet);
    }

    /**
     * The height up to which Paper's own anti-xray hides ores in this world, Integer.MIN_VALUE when it is off, or null
     * when that can't be told.
     */
    Integer paperAntiXrayHeight(World world) {
        try {
            Object level = call(worldHandle, world);
            Object config = level.getClass().getMethod("paperConfig").invoke(level);
            Object anticheat = config.getClass().getField("anticheat").get(config);
            Object antiXray = anticheat.getClass().getField("antiXray").get(anticheat);
            if (!antiXray.getClass().getField("enabled").getBoolean(antiXray)) return Integer.MIN_VALUE;
            return antiXray.getClass().getField("maxBlockHeight").getInt(antiXray);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Sends a packet to this player, through their filter. False when that isn't possible on this version. Any thread. */
    boolean send(Player p, Object packet) {
        if (sendPacket == null) return false;
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
            Object copy = withChunkBuffer(packet, out, true, minY, minY + 31, 1L << 3, Set.of(), null, null, null);
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
    Object withChunkBuffer(Object packet, byte[] buffer, boolean sideChannels, int minY, int maxY, long occludedMask,
                           Set<Long> removed, LightLeak.Side[] neighbours, boolean[] relight, LightLeak.Side[] ownSides) {
        Object data = get(chunkDataField, packet);
        boolean sides = sideChannels && chunkLightField != null;
        if (!sides && removed.isEmpty()) return Copies.with(packet, "chunkData", Copies.with(data, "buffer", buffer));
        List<Object> kept = new ArrayList<>();
        int cx = chunkX(packet) << 4, cz = chunkZ(packet) << 4;
        for (Object info : (List<?>) get(blockEntitiesField, data)) {
            int y = ((Number) get(blockEntityYField, info)).intValue(), section = (y - minY) >> 4; // a short since 26.1
            int xz = (int) get(blockEntityXZField, info);
            if (removed.contains(Containers.key(cx + (xz >> 4), y, cz + (xz & 15)))) continue; // a disguised container
            if (!sides || y > maxY && (section < 0 || section >= 64 || (occludedMask >> section & 1) == 0)) kept.add(info);
        }
        Object chunkData = Copies.with(data, "buffer", buffer, "blockEntitiesData", kept);
        return sides ? Copies.with(packet, "chunkData", chunkData, "lightData",
                hideLight(get(chunkLightField, packet), minY, maxY, occludedMask, buffer, neighbours, relight, ownSides))
                : Copies.with(packet, "chunkData", chunkData);
    }

    /** Where the block entities of a chunk packet are: x, y, z each. */
    List<int[]> blockEntityPositions(Object packet) {
        List<int[]> positions = new ArrayList<>();
        int cx = chunkX(packet) << 4, cz = chunkZ(packet) << 4;
        for (Object info : (List<?>) get(blockEntitiesField, get(chunkDataField, packet))) {
            int xz = (int) get(blockEntityXZField, info);
            positions.add(new int[]{cx + (xz >> 4), ((Number) get(blockEntityYField, info)).intValue(), cz + (xz & 15)});
        }
        return positions;
    }

    /** Whether containers can be disguised on this version: block ids and chunk packets' block entities are known. */
    boolean containersSupported() {
        return blockKeys != null && blockEntitiesField != null && blockEntityXZField != null;
    }

    /**
     * By block state id: whether it is one of these blocks ("chest" or "minecraft:chest", "*shulker_box" for every id
     * ending so) and gives off no light. Names that match no block are logged.
     */
    boolean[] statesOf(List<String> names) {
        boolean[] table = new boolean[blockKeys.length];
        Set<String> unmatched = new java.util.LinkedHashSet<>(names), lit = new java.util.LinkedHashSet<>(), hidden = new java.util.HashSet<>();
        for (int id = 0; id < table.length; id++) {
            String key = blockKeys[id];
            if (key == null) continue;
            String path = key.substring(key.indexOf(':') + 1);
            for (String name : names) {
                String n = name.toLowerCase(java.util.Locale.ROOT);
                boolean suffix = n.contains("*"); // also "minecraft:*shulker_box"
                n = n.substring(n.indexOf(':') + 1).replace("*", "");
                if (!(suffix ? path.endsWith(n) : path.equals(n))) continue;
                unmatched.remove(name);
                if (emission[id] == 0) { // a disguise can't give off its light
                    table[id] = true;
                    hidden.add(name);
                } else {
                    lit.add(name);
                }
            }
        }
        if (!unmatched.isEmpty()) log.warning("containers.hidden-blocks names no block: " + unmatched);
        lit.removeAll(hidden);
        if (!lit.isEmpty()) log.warning("containers.hidden-blocks: these give off light, so they can't be hidden: " + lit);
        return table;
    }

    /**
     * A copy of a light update packet without the light at or below {@code maxY} or in occluded sections, nor the block
     * light of visible sections near them (which may have spread out of them), or the packet itself.
     */
    Object withoutLight(Object packet, int minY, int maxY, long occludedMask) {
        if (lightUpdatePacket == null || maxY < minY && occludedMask == 0) return packet;
        return Copies.with(packet, "lightData", hideLight(get(lightUpdateDataField, packet), minY, maxY, occludedMask, null, null, null, null));
    }

    /**
     * Light data without the light at or below {@code maxY} or in occluded sections. Block light that reaches visible
     * cells from hidden ones goes too: worked out from the chunk's own section data when given, else (light updates)
     * by leaving the block light of visible sections near hidden cells to the client, which works it out itself.
     */
    @SuppressWarnings("unchecked")
    private Object hideLight(Object lightData, int minY, int maxY, long occludedMask, byte[] sections,
                             LightLeak.Side[] neighbours, boolean[] relight, LightLeak.Side[] sides) {
        var sky = ChunkSections.hideLight(new ChunkSections.LightLayer((BitSet) get(skyMask, lightData),
                (BitSet) get(emptySkyMask, lightData), (List<byte[]>) get(skyArrays, lightData)), minY, maxY, occludedMask);
        var blockLayer = new ChunkSections.LightLayer((BitSet) get(blockMask, lightData),
                (BitSet) get(emptyBlockMask, lightData), (List<byte[]>) get(blockArrays, lightData));
        blockLayer = sections != null ? withoutLeaks(blockLayer, minY, maxY, occludedMask, sections, neighbours, relight, sides)
                : withoutNearHidden(blockLayer, minY, maxY, occludedMask);
        var blocks = ChunkSections.hideLight(blockLayer, minY, maxY, occludedMask);
        return Copies.with(lightData, "skyYMask", sky.mask(), "emptySkyYMask", sky.empty(), "skyUpdates", sky.arrays(),
                "blockYMask", blocks.mask(), "emptyBlockYMask", blocks.empty(), "blockUpdates", blocks.arrays());
    }

    /** Block light without what reaches visible cells from hidden ones (see LightLeak); on any failure, as it was. */
    private ChunkSections.LightLayer withoutLeaks(ChunkSections.LightLayer layer, int minY, int maxY, long occludedMask, byte[] sections,
                                                  LightLeak.Side[] neighbours, boolean[] relight, LightLeak.Side[] sides) {
        if (emission == null) return layer;
        try { // a mistake here must only cost the light fix, never send the chunk unfiltered
            ChunkSections.States states = ChunkSections.states(sections);
            if (states == null) return layer;
            byte[][] light = new byte[states.count() + 2][];
            List<byte[]> arrays = layer.arrays();
            int rank = 0;
            for (int i = layer.mask().nextSetBit(0); i >= 0; i = layer.mask().nextSetBit(i + 1), rank++)
                if (i < light.length) light[i] = arrays.get(rank);
            if (!LightLeak.clean(light, minY, maxY, occludedMask, states.blocks(), emission, lightBlock, neighbours, relight, sides))
                return layer;
            List<byte[]> cleaned = new ArrayList<>(arrays.size());
            rank = 0;
            for (int i = layer.mask().nextSetBit(0); i >= 0; i = layer.mask().nextSetBit(i + 1), rank++)
                cleaned.add(i < light.length ? light[i] : arrays.get(rank));
            return new ChunkSections.LightLayer(layer.mask(), layer.empty(), cleaned);
        } catch (RuntimeException e) {
            if (!warnedLight) log.warning("Could not take hidden light out of a chunk, sending its light as before: " + e);
            warnedLight = true;
            return layer;
        }
    }

    /**
     * For a light update: visible sections within 15 blocks of hidden cells, which can get light from them, lose their
     * block light, so the client keeps the light it works out itself from the blocks it was sent.
     */
    private static ChunkSections.LightLayer withoutNearHidden(ChunkSections.LightLayer layer, int minY, int maxY, long occludedMask) {
        BitSet mask = (BitSet) layer.mask().clone(), empty = (BitSet) layer.empty().clone();
        List<byte[]> arrays = new ArrayList<>();
        boolean changed = false;
        int rank = 0;
        for (int i = layer.mask().nextSetBit(0); i >= 0; i = layer.mask().nextSetBit(i + 1), rank++) {
            if (LightLeak.nearHidden(i, minY, maxY, occludedMask)) {
                mask.clear(i);
                changed = true;
            } else {
                arrays.add(layer.arrays().get(rank));
            }
        }
        for (int i = layer.empty().nextSetBit(0); i >= 0; i = layer.empty().nextSetBit(i + 1)) {
            if (LightLeak.nearHidden(i, minY, maxY, occludedMask)) {
                empty.clear(i);
                changed = true;
            }
        }
        return changed ? new ChunkSections.LightLayer(mask, empty, arrays) : layer;
    }

    /**
     * The block of a position-only packet (block entity data, block events, mining cracks, level events, sounds,
     * particles, explosions), else null.
     */
    int[] sideChannelPos(Object packet) {
        Method pos = blockPosOf.get(packet.getClass());
        if (pos != null) return xyz(call(pos, packet));
        double[] at = packet.getClass() == explodePacket ? explosionPosition(packet) : soundOrParticlePosition(packet);
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

    /**
     * An explosion packet without the destroyed blocks {@code hidden} says the client holds as replacement (only sent
     * until 1.21.2); any other packet as it is.
     */
    Object explosionWithout(Object packet, Predicate<int[]> hidden) {
        if (explodeBlocks == null || packet.getClass() != explodePacket) return packet;
        List<?> blocks = (List<?>) get(explodeBlocks, packet);
        List<Object> kept = new ArrayList<>(blocks.size());
        for (Object pos : blocks) if (!hidden.test(xyz(pos))) kept.add(pos);
        return kept.size() == blocks.size() ? packet : Copies.with(packet, "toBlow", kept);
    }

    private double[] explosionPosition(Object packet) {
        Method[] m = explodePosition;
        Object at = m.length == 4 ? call(m[0], packet) : packet;
        int x = m.length - 3;
        return new double[]{(double) call(m[x], at), (double) call(m[x + 1], at), (double) call(m[x + 2], at)};
    }

    /** Calls {@code action(pos, state)} for each change in a section update packet. The pos object is reused. */
    void forEachChange(Object sectionUpdate, BiConsumer<Object, Object> action) {
        call(runUpdates, sectionUpdate, action);
    }

    /** A block update setting block (x, y, z) to state id {@code state}. */
    Object blockUpdate(int[] pos, int state) {
        return construct(newBlockUpdate, construct(newBlockPos, pos[0], pos[1], pos[2]), call(stateById, null, state));
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

    private static <T extends AccessibleObject> T accessible(T member) {
        member.setAccessible(true);
        return member;
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
