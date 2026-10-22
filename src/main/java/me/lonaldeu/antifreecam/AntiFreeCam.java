package me.lonaldeu.antifreecam;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    // Refreshes not caused by the player's own movement (new settings or whitelist, joining, a forced refresh) start
    // at least this far apart server-wide, so a reload on a full server doesn't re-send everyone's chunks at once.
    private static final long REFRESH_SPACING_MS = 250;

    // Players with a view. Read by the packet filter on netty threads.
    final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, PacketFilter> filters = new ConcurrentHashMap<>();
    private final Map<List<Object>, List<LongConsumer>> waitingSections = new ConcurrentHashMap<>(); // world, x, z, section
    private final Map<List<Object>, Long> checkedAt = new ConcurrentHashMap<>(); // section -> System.nanoTime() of its last quick check
    private final Map<UUID, Long> nextRefreshAt = new ConcurrentHashMap<>(); // monotonic millis, see now()
    private final Set<UUID> refreshQueued = ConcurrentHashMap.newKeySet(), spacedRefreshQueued = ConcurrentHashMap.newKeySet();
    private final AtomicLong refreshSlot = new AtomicLong(Long.MIN_VALUE / 2); // when the next spaced refresh may start
    private final Map<UUID, Integer> viewGenerations = new ConcurrentHashMap<>(); // settingsGeneration of each player's view
    volatile int settingsGeneration; // bumped whenever the settings or the whitelist change
    final EntityVisibility entities = new EntityVisibility(this);
    Nms nms;

    /** The settings that can differ per world (worlds.overrides). */
    record WorldSettings(boolean protection, double protectionY, double hysteresis, int hideBelowY, int viewDepth,
                         boolean caveOcclusion, boolean containers, boolean lineOfSight) {
    }

    // Their config keys, as in the main settings.
    private static final List<String> PER_WORLD = List.of("performance.underground-protection.enabled",
            "antixray.protection-y-level", "antixray.hysteresis", "antixray.hide-below-y", "antixray.underground-view-depth",
            "antixray.cave-occlusion", "containers.enabled", "entities.line-of-sight.enabled");

    // Settings snapshot, read from netty threads too.
    volatile boolean debug;
    volatile Set<String> worlds = Set.of();
    volatile WorldSettings defaults;
    volatile Map<String, WorldSettings> worldSettings = Map.of();
    volatile boolean hideEntities;
    volatile Set<String> hiddenTypeKeys = Set.of(); // "minecraft:zombie"; always contains the player key when hiding entities
    volatile String replacementName;
    volatile int replacementId;
    volatile int occludedId; // what occluded sections are sent as: the replacement if it blocks sight, else stone
    volatile boolean stripSideChannels;
    volatile long refreshCooldownMs;
    volatile int maxChunksPerTick;
    volatile long losInterval;
    volatile double losMaxDistance, losAlwaysVisible;
    volatile Set<EntityType> trackedTypes = Set.of(); // the listed types plus players, for the visibility pass
    volatile Set<EntityType> losTypes = Set.of(); // those also checked for line of sight (not the depth-only ones)
    volatile Set<EntityType> projectileTypes = Set.of(); // hidden below the cut, and with a hidden owner
    volatile Set<String> projectileKeys = Set.of(); // the same as "minecraft:arrow"
    volatile boolean hideHashedSeed, checkFeatureSeeds;
    final long hashedSeed = ThreadLocalRandom.current().nextLong(); // what clients get in place of the real one
    volatile boolean lightAcrossChunks;
    volatile boolean[] containerStates; // by block state id; null when containers can't be hidden on this version
    volatile double containerMaxDistance;
    volatile int airId, waterId;

    @Override
    public void onEnable() {
        try {
            nms = new Nms(getLogger());
            nms.setAirIds(Set.of(nms.blockId(Material.AIR.createBlockData()), nms.blockId(Material.CAVE_AIR.createBlockData()),
                    nms.blockId(Material.VOID_AIR.createBlockData())));
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("Unsupported server, Paper 1.20.5 or newer is required: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Running on " + platform());
        saveDefaultConfig();
        loadSettings();
        nms.onNewChannels("seed", channel -> SeedFilter.install(this, channel)); // before the login packet
        getServer().getPluginManager().registerEvents(this, this);
        Commands commands = new Commands(this);
        for (String name : List.of("antifreecam", "afcdebug", "afcreload", "afcworld")) {
            getCommand(name).setExecutor(commands);
            getCommand(name).setTabCompleter(commands);
        }
        getServer().getOnlinePlayers().forEach(this::track);
        reevaluateAll(true);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, t -> {
            selfCheck();
            hints();
        }, 20);
    }

    /** Filters one real chunk of the first world and reports whether this server version is handled. */
    private void selfCheck() {
        World world = getServer().getWorlds().get(0);
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() >> 4, z = spawn.getBlockZ() >> 4;
        // Spawn chunks aren't kept loaded any more (26.x), so load the one to check first.
        world.getChunkAtAsync(x, z).thenRun(() -> Bukkit.getRegionScheduler().execute(this, world, x, z, () -> {
            Object packet;
            try {
                packet = nms.chunkPacket(world, x, z);
            } catch (RuntimeException e) {
                getLogger().warning("Self-check skipped: " + e);
                return;
            }
            if (packet == null) {
                getLogger().warning("Self-check skipped: the spawn chunk isn't loaded");
                return;
            }
            String problem = nms.selfCheck(packet, world.getMinHeight(), replacementId, occludedId);
            if (problem == null) getLogger().info("Self-check passed: chunks are filtered on this server version");
            else getLogger().severe("Self-check FAILED, chunks would be sent unfiltered on this server version: " + problem);
        }));
    }

    @Override
    public void onDisable() {
        if (nms == null) return;
        nms.stopNewChannels("seed");
        for (Player p : getServer().getOnlinePlayers()) {
            Channel channel = channel(p);
            if (channel != null) channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(PacketFilter.NAME) != null) channel.pipeline().remove(PacketFilter.NAME);
                if (channel.pipeline().get(SeedFilter.NAME) != null) channel.pipeline().remove(SeedFilter.NAME);
            });
        }
        entities.showAll(); // queued behind the filter removal, so the re-sent spawns go out as they are
        views.clear();
        filters.clear();
        nextRefreshAt.clear();
        refreshQueued.clear();
        spacedRefreshQueued.clear();
        viewGenerations.clear();
    }

    /** E.g. "Folia 1.21.8". All scheduling goes through Paper's region/entity schedulers, which work on both. */
    String platform() {
        boolean folia;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException e) {
            folia = false;
        }
        return (folia ? "Folia " : "Paper ") + Bukkit.getMinecraftVersion();
    }

    private Channel channel(Player p) {
        try {
            return nms.channel(p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().warning("Could not reach the connection of " + p.getName() + ": " + e);
            return null;
        }
    }

    /**
     * Hooks the player's connection and re-checks their position every tick on their own scheduler. Polling catches
     * every way of moving (walking, /tp, plugin teleports, portals, respawns) the same way on Paper and Folia, where
     * async teleports fire no teleport event. The task ends by itself when the player leaves.
     */
    private void track(Player p) {
        hook(p);
        p.getScheduler().runAtFixedRate(this, t -> update(p, p.getLocation(), false), null, 1, 1);
        entities.start(p);
    }

    /** Adds the packet filter to the player's connection, just before the server's own packet handler. */
    private void hook(Player p) {
        Channel channel = channel(p);
        if (channel == null) return;
        PacketFilter filter = new PacketFilter(this, p, channel);
        filters.put(p.getUniqueId(), filter);
        SeedFilter.install(this, channel); // for players already online when the plugin was enabled
        channel.eventLoop().execute(() -> {
            var pipeline = channel.pipeline();
            if (pipeline.get(PacketFilter.NAME) == null && pipeline.get("packet_handler") != null)
                pipeline.addBefore("packet_handler", PacketFilter.NAME, filter);
        });
    }

    void loadSettings() {
        reloadConfig();
        var c = getConfig();
        debug = c.getBoolean("settings.debug-mode", false);
        refreshCooldownMs = Math.round(c.getDouble("settings.refresh-cooldown-seconds", 1) * 1000);
        worlds = Set.copyOf(c.getStringList("worlds.whitelist"));
        defaults = worldSettings(c, null);
        Map<String, WorldSettings> perWorld = new HashMap<>();
        ConfigurationSection overrides = c.getConfigurationSection("worlds.overrides");
        if (overrides != null) for (String name : overrides.getKeys(false)) {
            ConfigurationSection o = overrides.getConfigurationSection(name);
            if (o == null) continue;
            for (String key : o.getKeys(true))
                if (!o.isConfigurationSection(key) && !PER_WORLD.contains(key))
                    getLogger().warning("worlds.overrides." + name + "." + key + " can't be set per world, ignored");
            if (!worlds.contains(name)) getLogger().warning("worlds.overrides." + name + ": not in worlds.whitelist, so not protected");
            perWorld.put(name, worldSettings(c, o));
        }
        worldSettings = Map.copyOf(perWorld);
        maxChunksPerTick = Math.max(1, c.getInt("performance.max-chunks-per-tick", 8));
        stripSideChannels = c.getBoolean("advanced.packet-processing.strip-side-channels", true);

        hideEntities = c.getBoolean("entities.hide-entities", true);
        Set<EntityType> los = entityTypes(c.getStringList("entities.hidden-types"));
        los.add(EntityType.PLAYER);
        Set<EntityType> tracked = new HashSet<>(los);
        tracked.addAll(entityTypes(c.getStringList("entities.depth-only-types")));
        Set<EntityType> shot = c.getBoolean("entities.projectiles.enabled", true)
                ? entityTypes(c.getStringList("entities.projectiles.types")) : new HashSet<>();
        shot.removeAll(los); // listed for line of sight already
        tracked.addAll(shot);
        losTypes = Set.copyOf(los);
        trackedTypes = Set.copyOf(tracked);
        projectileTypes = Set.copyOf(shot);
        Set<String> shotKeys = new HashSet<>();
        for (EntityType type : shot) shotKeys.add(type.getKey().toString());
        projectileKeys = hideEntities ? Set.copyOf(shotKeys) : Set.of();
        Set<String> keys = new HashSet<>();
        for (EntityType type : tracked) keys.add(type.getKey().toString());
        hiddenTypeKeys = hideEntities ? Set.copyOf(keys) : Set.of();
        losInterval = Math.max(1, c.getInt("entities.line-of-sight.check-interval-ticks", 2));
        losMaxDistance = c.getDouble("entities.line-of-sight.max-distance", 64);
        losAlwaysVisible = c.getDouble("entities.line-of-sight.always-visible-distance", 3);

        String block = c.getString("performance.replacement.block-type", "air");
        BlockData data;
        try {
            data = Bukkit.createBlockData(block);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Unknown replacement block '" + block + "', using minecraft:air");
            data = Material.AIR.createBlockData();
        }
        replacementName = data.getAsString();
        try {
            replacementId = nms.blockId(data);
            // Occluded sections must look solid: anything see-through would let the client draw what's behind them.
            occludedId = nms.isOpaque(replacementId) ? replacementId : nms.blockId(Material.STONE.createBlockData());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        boolean containers = defaults.containers() || perWorld.values().stream().anyMatch(WorldSettings::containers);
        containerMaxDistance = c.getDouble("containers.max-distance", 64);
        containerStates = nms.containersSupported() ? nms.statesOf(c.getStringList("containers.hidden-blocks")) : null;
        hideHashedSeed = c.getBoolean("seed-protection.hide-hashed-seed", true);
        checkFeatureSeeds = c.getBoolean("seed-protection.check-feature-seeds", true);
        lightAcrossChunks = c.getBoolean("advanced.packet-processing.light-across-chunks", true);
        if (containers && containerStates == null)
            getLogger().warning("Container hiding is unavailable on this version: block entities can't be read");
        try {
            airId = nms.blockId(Material.AIR.createBlockData());
            waterId = nms.blockId(Material.WATER.createBlockData());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        // An existing config.yml keeps its old keys and values when the plugin is updated.
        List<String> unused = List.of("bedrock", "performance.limited-area", "advanced.packet-processing.modify-chunk-data",
                "advanced.packet-processing.modify-block-changes", "advanced.packet-processing.modify-multi-block-changes")
                .stream().filter(c::isSet).toList();
        if (!unused.isEmpty()) getLogger().warning("config.yml is from an older version: " + unused
                + " are no longer used. See 'Upgrading' in the README for the new defaults.");
        settingsGeneration++;
    }

    /** One world's settings: each key from its overrides section {@code o} where set, else from the main ones. */
    private static WorldSettings worldSettings(ConfigurationSection main, ConfigurationSection o) {
        java.util.function.Function<String, ConfigurationSection> at = key -> o != null && o.isSet(key) ? o : main;
        return new WorldSettings(
                at.apply(PER_WORLD.get(0)).getBoolean(PER_WORLD.get(0), true),
                at.apply(PER_WORLD.get(1)).getDouble(PER_WORLD.get(1), 31.0),
                Math.max(0, at.apply(PER_WORLD.get(2)).getDouble(PER_WORLD.get(2), 2.0)),
                at.apply(PER_WORLD.get(3)).getInt(PER_WORLD.get(3), 16),
                at.apply(PER_WORLD.get(4)).getInt(PER_WORLD.get(4), 48),
                at.apply(PER_WORLD.get(5)).getBoolean(PER_WORLD.get(5), true),
                at.apply(PER_WORLD.get(6)).getBoolean(PER_WORLD.get(6), true),
                at.apply(PER_WORLD.get(7)).getBoolean(PER_WORLD.get(7), true));
    }

    /** The settings of a world: its overrides, else the main ones. Any thread. */
    WorldSettings settings(World world) {
        return worldSettings.getOrDefault(world.getName(), defaults);
    }

    /**
     * The entity types a config list names, those this server version has: "SKELETON" means skeleton and every
     * *_skeleton, such as wither_skeleton.
     */
    private static Set<EntityType> entityTypes(List<String> names) {
        Set<EntityType> types = new HashSet<>();
        for (EntityType type : Registry.ENTITY_TYPE) {
            String key = type.getKey().getKey();
            for (String name : names) {
                String n = name.toLowerCase(Locale.ROOT);
                if (key.equals(n) || key.endsWith("_" + n)) types.add(type);
            }
        }
        return types;
    }

    /**
     * Ores in what players can reach are left to Paper's own anti-xray, and ores placed from the world seed can be
     * worked out from a cracked seed: say so where the server leaves them uncovered.
     */
    private void hints() {
        for (String name : worlds) {
            World world = Bukkit.getWorld(name);
            if (world != null && checkFeatureSeeds && Boolean.FALSE.equals(nms.randomFeatureSeeds(world)))
                getLogger().warning("Ores in " + name + " (diamonds, gold, redstone, lapis, emeralds, debris) are placed from"
                        + " the world seed, so a seed-cracking mod can work out where they are. Set"
                        + " feature-seeds.generate-random-seeds-for-all to true in config/paper-world-defaults.yml (for chunks"
                        + " generated from then on; the big iron and copper veins, caves and structures follow the seed"
                        + " regardless).");
            Integer height = world == null ? null : nms.paperAntiXrayHeight(world);
            if (height == null) continue;
            if (height == Integer.MIN_VALUE)
                getLogger().info("Paper's anti-xray is off in " + name + ", so ores in caves players can reach are sent as"
                        + " they are. Turn on anticheat.anti-xray in config/paper-world-defaults.yml (engine-mode 2 also adds"
                        + " fake ores).");
            else if (height < world.getMaxHeight())
                getLogger().info("Paper's anti-xray in " + name + " stops at Y " + height + ": ores above it in caves"
                        + " players can reach are sent as they are. anticheat.anti-xray.max-block-height raises it, at some"
                        + " cost.");
        }
    }

    /** Re-check every online player, e.g. after the settings or the whitelist changed. */
    void reevaluateAll(boolean refresh) {
        for (Player p : getServer().getOnlinePlayers()) {
            p.getScheduler().run(this, t -> update(p, p.getLocation(), refresh), null);
        }
    }

    /**
     * The view for a player at {@code at}, given their current one (for hysteresis), or null for none.
     * <ul>
     * <li>At or above the protection level: everything at or below hide-below-y. The full view only comes back
     * once the player is {@code hysteresis} blocks under the protection level, so jumping on a ledge doesn't flicker.</li>
     * <li>Below it: whole 16-block sections more than underground-view-depth blocks below the player, so digging a
     * shaft to just under the protection level doesn't reveal the deep underground. Moving up only hides more once
     * the player is {@code hysteresis} blocks past a section boundary.</li>
     * <li>With cave occlusion, a player with nothing height-hidden still gets a view, for occlusion alone.</li>
     * </ul>
     */
    View viewAt(Location at, View current, boolean occlude, boolean containers) {
        WorldSettings s = settings(at.getWorld());
        if (!s.protection() || !worlds.contains(at.getWorld().getName())) return null;
        int minY = at.getWorld().getMinHeight();
        View none = s.caveOcclusion() && nms.opaque != null || containers ? new View(minY, minY - 1, false, occlude, containers) : null;
        double y = at.getY();
        boolean wasSurface = current != null && current.surface();
        if (y >= s.protectionY() || (wasSurface && y >= s.protectionY() - s.hysteresis()))
            return new View(minY, s.hideBelowY(), true, occlude, containers);
        if (s.viewDepth() <= 0) return none;

        double reach = y - s.viewDepth(); // the lowest Y this player gets to see, before rounding to sections
        int cut = Math.min(View.depthCut(reach, current == null ? minY - 1 : current.maxHiddenY(), s.hysteresis()), s.hideBelowY());
        return cut < minY ? none : new View(minY, cut, false, occlude, containers);
    }

    /** By block state id: whether this block is a container that gets hidden. Any thread. */
    boolean isContainer(int state) {
        boolean[] table = containerStates;
        return table != null && state >= 0 && state < table.length && table[state];
    }

    /** The container hiding of a player's connection, or null. */
    Containers containersOf(UUID player) {
        PacketFilter filter = filters.get(player);
        return filter == null ? null : filter.containers;
    }

    /**
     * Updates the player's view for the given location. When it changes (or {@code refresh} is set and the
     * player has one) the client's view is re-sent so it matches.
     */
    void update(Player p, Location to, boolean refresh) {
        UUID id = p.getUniqueId();
        View before = views.get(id);
        boolean exempt = p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("antifreecam.bypass");
        WorldSettings s = settings(to.getWorld());
        boolean occlude = s.caveOcclusion() && nms.opaque != null && !exempt;
        View after = viewAt(to, before, occlude, s.containers() && containerStates != null && !exempt);
        if (after == null) views.remove(id);
        else views.put(id, after);
        PacketFilter filter = filters.get(id);
        if (filter != null) filter.containers.eyeAt(to.getX(), to.getY() + p.getEyeHeight(), to.getZ());
        if (filter != null && after != null) {
            World world = to.getWorld();
            int eyeSection = Math.floorDiv((int) Math.floor(to.getY() + p.getEyeHeight()) - world.getMinHeight(), 16);
            filter.moved(new PacketFilter.Eye(world, to.getBlockX() >> 4, eyeSection, to.getBlockZ() >> 4));
        } else if (filter != null) {
            filter.moved(null);
        }
        Integer generation = viewGenerations.put(id, settingsGeneration);
        boolean changed = !Objects.equals(before, after);
        if (!changed && !(refresh && after != null)) return;
        if (debug && changed) getLogger().info(p.getName() + ": " + (after == null ? "full view"
                : (after.maxHiddenY() < after.minY() ? "nothing height-hidden" : "hiding Y <= " + after.maxHiddenY())
                + (after.surface() ? " (above ground)" : "") + (after.occlude() ? ", cave occlusion" : "")
                + (after.containers() ? ", containers" : "")));
        // Only a view the player's own movement changed is urgent; one from new settings or a first view is spaced.
        requestRefresh(p, !changed || generation == null || generation != settingsGeneration);
    }

    /**
     * Re-sends the player's view, at most once per refresh cooldown and never while their last refresh is still
     * being sent. A request inside the cooldown is never dropped: it runs when the cooldown ends, using whatever the
     * player's state is by then. {@code spaced} requests also wait for a server-wide slot; urgent ones never wait
     * for those.
     */
    void requestRefresh(Player p, boolean spaced) {
        UUID id = p.getUniqueId();
        Set<UUID> queued = spaced ? spacedRefreshQueued : refreshQueued;
        if (spaced && refreshQueued.contains(id) || !queued.add(id)) return; // one is already on its way
        long now = now();
        long start = nextRefreshAt.getOrDefault(id, now);
        if (spaced) start = Math.max(start, refreshSlot.accumulateAndGet(now, (last, t) -> Math.max(last + REFRESH_SPACING_MS, t)));
        p.getScheduler().runDelayed(this, t -> {
            queued.remove(id);
            int chunks = refreshView(p); // entities follow on their own: the visibility pass re-checks them every few ticks
            nextRefreshAt.put(id, now() + Math.max(refreshCooldownMs, chunks * 50L / maxChunksPerTick));
        }, () -> queued.remove(id), 2 + (Math.max(0, start - now) + 49) / 50);
    }

    /** Milliseconds on a clock that never steps back, so a system clock correction can't stall refreshes. */
    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    /**
     * Re-sends the chunks the player's client holds, nearest first, spread over ticks; returns how many. Each chunk
     * goes to this player only; World#refreshChunk would re-send it to everyone nearby. Always the whole view
     * distance: a chunk left out would keep whatever an earlier, less strict view let through.
     */
    int refreshView(Player p) {
        World world = p.getWorld();
        int r = world.getViewDistance();
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        // Empty where the server doesn't track it (Paper 1.21.4): then send everything in range; clients ignore
        // chunks they don't hold.
        Set<Long> sent = p.getSentChunkKeys();
        int n = 0;
        for (int ring = 0; ring <= r; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = cx + dx, z = cz + dz;
                    if (!sent.isEmpty() && !sent.contains(Chunk.getChunkKey(x, z))) continue;
                    sendChunk(p, world, x, z, 1 + n++ / maxChunksPerTick);
                }
            }
        }
        return n;
    }

    /** Re-sends chunk (x, z) to this player only, after {@code delay} ticks, on the thread that owns the chunk. */
    private void sendChunk(Player p, World world, int x, int z, long delay) {
        Bukkit.getRegionScheduler().runDelayed(this, world, x, z, t -> {
            if (!p.isOnline() || !p.getWorld().equals(world)) return;
            if (Bukkit.isOwnedByCurrentRegion(p)) { // always on Paper; the client may have dropped it since it was queued
                Set<Long> sent = p.getSentChunkKeys(); // empty where the server doesn't track it (Paper 1.21.4)
                if (!sent.isEmpty() && !sent.contains(Chunk.getChunkKey(x, z))) return;
            }
            if (!nms.sendChunk(p, world, x, z) && world.isChunkLoaded(x, z)) world.refreshChunk(x, z);
        }, delay);
    }

    /**
     * A cave-occlusion reveal: re-sends chunk (x, z) to this player after {@code delay} ticks, unless they changed
     * world or their client no longer holds it (a stale re-send would leave a chunk the client never unloads).
     * Any thread.
     */
    void revealLater(Player p, World world, int x, int z, long delay) {
        p.getScheduler().runDelayed(this, t -> {
            Set<Long> sent = p.getSentChunkKeys(); // empty where the server doesn't track it (Paper 1.21.4)
            if (p.getWorld().equals(world) && (sent.isEmpty() || sent.contains(Chunk.getChunkKey(x, z))))
                sendChunk(p, world, x, z, 1);
        }, null, delay);
    }

    /**
     * Works out the exact connectivity of section s of chunk (x, z) on the chunk's own thread: on the next tick, or a
     * few ticks later for a section worked out moments ago (with {@code slow}, a few seconds). Any thread. Players
     * asking about the same section before it runs share one computation, so a redstone clock or a farm changing
     * blocks every tick costs one computation every few ticks.
     */
    void sectionConnectivityLater(World world, int x, int z, int s, boolean slow, LongConsumer done) {
        List<Object> key = List.of(world.getUID(), x, z, s, slow);
        boolean[] first = {false};
        waitingSections.compute(key, (k, waiting) -> {
            if (waiting == null) {
                first[0] = true;
                waiting = new ArrayList<>();
            }
            waiting.add(done);
            return waiting;
        });
        if (!first[0]) return;
        Long last = slow ? null : checkedAt.get(key);
        long delay = slow ? PacketFilter.CONCEAL_TICKS - 10 : last != null && System.nanoTime() - last < 200_000_000L ? 4 : 1;
        try {
            Bukkit.getRegionScheduler().runDelayed(this, world, x, z, t -> {
                List<LongConsumer> waiting = waitingSections.remove(key);
                if (!slow) {
                    checkedAt.put(key, System.nanoTime());
                    if (checkedAt.size() > 4096) checkedAt.values().removeIf(at -> System.nanoTime() - at > 1_000_000_000L);
                }
                long conn;
                try {
                    conn = nms.sectionConnectivity(world, x, z, s);
                } catch (RuntimeException e) {
                    conn = -1;
                }
                if (conn != -1 && waiting != null) for (LongConsumer answer : waiting) answer.accept(conn);
            }, delay);
        } catch (RuntimeException e) { // e.g. the plugin is being disabled
            waitingSections.remove(key);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        track(e.getPlayer());
        update(e.getPlayer(), e.getPlayer().getLocation(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        views.remove(id);
        filters.remove(id);
        nextRefreshAt.remove(id);
        refreshQueued.remove(id);
        spacedRefreshQueued.remove(id);
        viewGenerations.remove(id);
        entities.quit(id);
    }

    // Paper fires this before the destination chunks are sent, so the state is right from the first chunk.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        update(e.getPlayer(), e.getTo(), false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        update(e.getPlayer(), e.getPlayer().getLocation(), true);
    }

    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent e) {
        update(e.getPlayer(), e.getPlayer().getLocation(), true);
    }
}
