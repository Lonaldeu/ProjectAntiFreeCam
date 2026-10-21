package me.lonaldeu.antifreecam;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    private static final List<String> FALLBACK_TYPES = List.of("ARMOR_STAND", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
            "MINECART", "CHEST_MINECART", "HOPPER_MINECART", "TNT_MINECART", "FURNACE_MINECART", "SPAWNER_MINECART");

    /**
     * What a player may not see: everything at or below {@code maxHiddenY}. {@code surface} means the player is above
     * the protection level; otherwise the cut follows the player's own depth.
     */
    record View(int minY, int maxHiddenY, boolean surface) {
    }

    // Players with a hidden range. Read by the packet filter on netty threads.
    final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextRefreshAt = new ConcurrentHashMap<>();
    private final Set<UUID> refreshQueued = ConcurrentHashMap.newKeySet();
    final Bedrock bedrock = new Bedrock(this);
    final EntityVisibility entities = new EntityVisibility(this);
    // Chunk packets already rewritten, per original packet and view. Another plugin's World#refreshChunk sends one
    // packet to every nearby player; most of them share a view, so it only needs rewriting once. Weak keys: entries
    // go away with the packet.
    final Map<Object, Map<Long, Object>> rewrittenChunks = Collections.synchronizedMap(new WeakHashMap<>());
    Nms nms;

    // Settings snapshot, read from netty threads too.
    volatile boolean debug;
    volatile Set<String> worlds = Set.of();
    volatile boolean protectionEnabled;
    volatile double protectionY, hysteresis;
    volatile int hideBelowY, viewDepth;
    volatile boolean hideEntities;
    volatile Set<String> hiddenTypeKeys = Set.of(); // "minecraft:zombie"; always contains the player key when hiding entities
    volatile String replacementName;
    volatile int replacementId;
    volatile boolean modifyChunks, modifyBlockChanges, modifyMultiBlockChanges, stripSideChannels;
    volatile long refreshCooldownMs;
    volatile int maxChunksPerTick;
    volatile boolean limitedArea;
    volatile int limitedRadius;
    volatile boolean lineOfSight;
    volatile long losInterval;
    volatile double losMaxDistance, losAlwaysVisible;
    volatile Set<String> trackedTypeKeys = Set.of(); // the listed types plus players, for the visibility pass

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
        getServer().getPluginManager().registerEvents(this, this);
        Commands commands = new Commands(this);
        for (String name : List.of("antifreecam", "afcdebug", "afcreload", "afcworld")) {
            getCommand(name).setExecutor(commands);
            getCommand(name).setTabCompleter(commands);
        }
        getServer().getOnlinePlayers().forEach(this::track);
        reevaluateAll(true);
    }

    @Override
    public void onDisable() {
        if (nms == null) return;
        for (Player p : getServer().getOnlinePlayers()) {
            Channel channel = channel(p);
            if (channel != null) channel.eventLoop().execute(() -> {
                if (channel.pipeline().get(PacketFilter.NAME) != null) channel.pipeline().remove(PacketFilter.NAME);
            });
        }
        views.clear();
        nextRefreshAt.clear();
        refreshQueued.clear();
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
        UUID id = p.getUniqueId();
        channel.eventLoop().execute(() -> {
            var pipeline = channel.pipeline();
            if (pipeline.get(PacketFilter.NAME) == null && pipeline.get("packet_handler") != null)
                pipeline.addBefore("packet_handler", PacketFilter.NAME, new PacketFilter(this, id));
        });
    }

    void loadSettings() {
        reloadConfig();
        var c = getConfig();
        debug = c.getBoolean("settings.debug-mode", false);
        refreshCooldownMs = Math.round(c.getDouble("settings.refresh-cooldown-seconds", 1) * 1000);
        worlds = Set.copyOf(c.getStringList("worlds.whitelist"));
        protectionY = c.getDouble("antixray.protection-y-level", 31.0);
        hideBelowY = c.getInt("antixray.hide-below-y", 16);
        hysteresis = Math.max(0, c.getDouble("antixray.hysteresis", 2.0));
        viewDepth = c.getInt("antixray.underground-view-depth", 48);
        protectionEnabled = c.getBoolean("performance.underground-protection.enabled", true);
        maxChunksPerTick = Math.max(1, c.getInt("performance.max-chunks-per-tick", 50));
        limitedArea = c.getBoolean("performance.limited-area.enabled", false);
        limitedRadius = c.getInt("performance.limited-area.chunk-radius", 3);
        modifyChunks = c.getBoolean("advanced.packet-processing.modify-chunk-data", true);
        modifyBlockChanges = c.getBoolean("advanced.packet-processing.modify-block-changes", true);
        modifyMultiBlockChanges = c.getBoolean("advanced.packet-processing.modify-multi-block-changes", true);
        stripSideChannels = c.getBoolean("advanced.packet-processing.strip-side-channels", true);

        hideEntities = c.getBoolean("entities.hide-entities", true);
        List<String> types = c.getStringList("entities.hidden-types");
        Set<String> keys = new HashSet<>();
        for (String t : types.isEmpty() ? FALLBACK_TYPES : types) keys.add("minecraft:" + t.toLowerCase(Locale.ROOT));
        keys.add("minecraft:player");
        hiddenTypeKeys = hideEntities ? Set.copyOf(keys) : Set.of();
        trackedTypeKeys = Set.copyOf(keys);
        lineOfSight = c.getBoolean("entities.line-of-sight.enabled", true);
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
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }

        bedrock.load(c);
    }

    /** Re-check every online player, e.g. after the settings or the whitelist changed. */
    void reevaluateAll(boolean refresh) {
        for (Player p : getServer().getOnlinePlayers()) {
            p.getScheduler().run(this, t -> update(p, p.getLocation(), refresh), null);
        }
    }

    /**
     * The hidden range for a player at {@code at}, given their current one (for hysteresis), or null for none.
     * <ul>
     * <li>At or above the protection level: everything at or below hide-below-y. The full view only comes back
     * once the player is {@code hysteresis} blocks under the protection level, so jumping on a ledge doesn't flicker.</li>
     * <li>Below it: whole 16-block sections more than underground-view-depth blocks below the player, so digging a
     * shaft to just under the protection level doesn't reveal the deep underground. Moving up only hides more once
     * the player is {@code hysteresis} blocks past a section boundary.</li>
     * </ul>
     */
    View viewAt(Location at, View current) {
        if (!protectionEnabled || !worlds.contains(at.getWorld().getName())) return null;
        int minY = at.getWorld().getMinHeight();
        double y = at.getY();
        boolean wasSurface = current != null && current.surface();
        if (y >= protectionY || (wasSurface && y >= protectionY - hysteresis)) return new View(minY, hideBelowY, true);
        if (viewDepth <= 0) return null;

        double reach = y - viewDepth; // the lowest Y this player gets to see, before rounding to sections
        int cut = Math.floorDiv((int) Math.floor(reach), 16) * 16 - 1;
        int held = current == null || wasSurface ? minY - 1 : current.maxHiddenY();
        if (!wasSurface && cut > held && reach < held + 1 + hysteresis) cut = held;
        cut = Math.min(cut, hideBelowY);
        return cut < minY ? null : new View(minY, cut, false);
    }

    /**
     * Updates the player's hidden range for the given location. When it changes (or {@code refresh} is set and the
     * player has one) the client's view is re-sent so it matches.
     */
    void update(Player p, Location to, boolean refresh) {
        UUID id = p.getUniqueId();
        View before = views.get(id);
        View after = viewAt(to, before);
        if (after == null) views.remove(id);
        else views.put(id, after);
        boolean changed = !Objects.equals(before, after);
        if (!changed && !(refresh && after != null)) return;
        if (debug && changed) getLogger().info(p.getName() + ": " + (after == null ? "full view"
                : "hiding Y <= " + after.maxHiddenY() + (after.surface() ? " (above ground)" : " (depth limit)")));
        requestRefresh(p);
    }

    /**
     * Re-sends the player's view, at most once per refresh cooldown. A request inside the cooldown is never dropped:
     * it runs when the cooldown ends, using whatever the player's state is by then.
     */
    void requestRefresh(Player p) {
        UUID id = p.getUniqueId();
        if (!refreshQueued.add(id)) return; // one is already on its way
        long wait = Math.max(0, nextRefreshAt.getOrDefault(id, 0L) - System.currentTimeMillis());
        p.getScheduler().runDelayed(this, t -> {
            refreshQueued.remove(id);
            nextRefreshAt.put(id, System.currentTimeMillis() + refreshCooldownMs);
            refreshView(p); // entities follow on their own: the visibility pass re-checks them every few ticks
        }, () -> refreshQueued.remove(id), 2 + (wait + 49) / 50);
    }

    /**
     * Re-sends the chunks the player's client holds, nearest first, spread over ticks. Each chunk goes to this
     * player only; World#refreshChunk would re-send it to everyone nearby.
     */
    void refreshView(Player p) {
        World world = p.getWorld();
        int r = world.getViewDistance();
        if (limitedArea) r = Math.min(r, limitedRadius);
        r = bedrock.adjustRadius(p, r);
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
                    Bukkit.getRegionScheduler().runDelayed(this, world, x, z, t -> {
                        if (!p.isOnline() || !p.getWorld().equals(world)) return;
                        if (!nms.sendChunk(p, world, x, z) && world.isChunkLoaded(x, z)) world.refreshChunk(x, z);
                    }, 1 + n++ / maxChunksPerTick);
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        bedrock.logJoin(e.getPlayer());
        track(e.getPlayer());
        update(e.getPlayer(), e.getPlayer().getLocation(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        views.remove(id);
        nextRefreshAt.remove(id);
        refreshQueued.remove(id);
        entities.forget(id);
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
