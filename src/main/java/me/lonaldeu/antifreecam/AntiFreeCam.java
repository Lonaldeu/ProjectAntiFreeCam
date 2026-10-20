package me.lonaldeu.antifreecam;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AntiFreeCam extends JavaPlugin implements Listener {

    private static final List<String> FALLBACK_TYPES = List.of("ARMOR_STAND", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
            "MINECART", "CHEST_MINECART", "HOPPER_MINECART", "TNT_MINECART", "FURNACE_MINECART", "SPAWNER_MINECART");

    // Players whose view is filtered. Read by the packet filter on netty threads.
    final Set<UUID> hidden = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> hideCooldownUntil = new ConcurrentHashMap<>();
    final Bedrock bedrock = new Bedrock(this);
    private PacketListenerCommon packetListener;

    // Settings snapshot, read from netty threads too.
    volatile boolean debug;
    volatile Set<String> worlds = Set.of();
    volatile boolean protectionEnabled;
    volatile double protectionY;
    volatile int hideBelowY;
    volatile boolean hideEntities;
    volatile Set<String> hiddenTypeKeys = Set.of(); // "minecraft:zombie"; always contains the player key when hiding entities
    volatile String replacementName;
    volatile int replacementId;
    volatile boolean modifyChunks, modifyBlockChanges, modifyMultiBlockChanges;
    volatile long refreshCooldownMs;
    volatile int maxChunksPerTick;
    volatile boolean limitedArea;
    volatile int limitedRadius;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        packetListener = PacketEvents.getAPI().getEventManager().registerListener(new PacketFilter(this), PacketListenerPriority.NORMAL);
        getServer().getPluginManager().registerEvents(this, this);
        Commands commands = new Commands(this);
        for (String name : List.of("antifreecam", "afcdebug", "afcreload", "afcworld")) {
            getCommand(name).setExecutor(commands);
            getCommand(name).setTabCompleter(commands);
        }
        reevaluateAll(true);
    }

    @Override
    public void onDisable() {
        if (packetListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        hidden.clear();
        hideCooldownUntil.clear();
    }

    void loadSettings() {
        reloadConfig();
        var c = getConfig();
        debug = c.getBoolean("settings.debug-mode", false);
        refreshCooldownMs = c.getInt("settings.refresh-cooldown-seconds", 3) * 1000L;
        worlds = Set.copyOf(c.getStringList("worlds.whitelist"));
        protectionY = c.getDouble("antixray.protection-y-level", 31.0);
        hideBelowY = c.getInt("antixray.hide-below-y", 16);
        protectionEnabled = c.getBoolean("performance.underground-protection.enabled", true);
        maxChunksPerTick = Math.max(1, c.getInt("performance.max-chunks-per-tick", 50));
        limitedArea = c.getBoolean("performance.limited-area.enabled", false);
        limitedRadius = c.getInt("performance.limited-area.chunk-radius", 3);
        modifyChunks = c.getBoolean("advanced.packet-processing.modify-chunk-data", true);
        modifyBlockChanges = c.getBoolean("advanced.packet-processing.modify-block-changes", true);
        modifyMultiBlockChanges = c.getBoolean("advanced.packet-processing.modify-multi-block-changes", true);

        hideEntities = c.getBoolean("entities.hide-entities", true);
        List<String> types = c.getStringList("entities.hidden-types");
        Set<String> keys = new HashSet<>();
        if (hideEntities) {
            for (String t : types.isEmpty() ? FALLBACK_TYPES : types) keys.add("minecraft:" + t.toLowerCase(Locale.ROOT));
            keys.add("minecraft:player");
        }
        hiddenTypeKeys = Set.copyOf(keys);

        String block = c.getString("performance.replacement.block-type", "air");
        if (!block.contains(":")) block = "minecraft:" + block;
        WrappedBlockState state = null;
        try {
            state = WrappedBlockState.getByString(block);
        } catch (RuntimeException ignored) {
        }
        if (state == null) {
            getLogger().warning("Unknown replacement block '" + block + "', using minecraft:air");
            block = "minecraft:air";
            state = WrappedBlockState.getByString(block);
        }
        replacementName = block;
        replacementId = state.getGlobalId();

        bedrock.load(c);
    }

    /** Re-check every online player, e.g. after the settings or the whitelist changed. */
    void reevaluateAll(boolean refresh) {
        for (Player p : getServer().getOnlinePlayers()) {
            p.getScheduler().run(this, t -> update(p, p.getLocation(), refresh), null);
        }
    }

    boolean shouldHide(Location at) {
        return protectionEnabled && worlds.contains(at.getWorld().getName()) && at.getY() >= protectionY;
    }

    /**
     * Sets the player's hidden state for the given location. When it changes (or {@code refresh} is set and the
     * player is hidden) the client's view is re-sent so it matches the new state.
     */
    void update(Player p, Location to, boolean refresh) {
        UUID id = p.getUniqueId();
        boolean hide = shouldHide(to);
        boolean changed = hide ? hidden.add(id) : hidden.remove(id);
        if (!changed && !(refresh && hide)) return;
        if (debug && changed) getLogger().info(p.getName() + (hide ? " is now above ground (hidden view)" : " is now below the protection level (full view)"));

        if (hide && !refresh) {
            long now = System.currentTimeMillis();
            Long until = hideCooldownUntil.get(id);
            if (until != null && now < until) return; // state is set; new packets are filtered anyway
            hideCooldownUntil.put(id, now + refreshCooldownMs);
        }
        p.getScheduler().runDelayed(this, t -> {
            refreshView(p);
            resyncEntities(p);
        }, null, 2);
    }

    /** Re-sends the chunks around the player, nearest first, spread over ticks. */
    void refreshView(Player p) {
        World world = p.getWorld();
        int r = world.getViewDistance();
        if (limitedArea) r = Math.min(r, limitedRadius);
        r = bedrock.adjustRadius(p, r);
        int cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
        int n = 0;
        for (int ring = 0; ring <= r; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = cx + dx, z = cz + dz;
                    Bukkit.getRegionScheduler().runDelayed(this, world, x, z, t -> {
                        if (world.isChunkLoaded(x, z)) world.refreshChunk(x, z);
                    }, 1 + n++ / maxChunksPerTick);
                }
            }
        }
    }

    /**
     * Hides then re-shows nearby entities so the client gets fresh spawn packets, which the packet filter
     * then lets through or drops according to the player's current state.
     */
    // ponytail: assumes entities within 64 blocks share the player's Folia region, schedule per entity if that bites
    void resyncEntities(Player p) {
        if (!hideEntities) return;
        List<Entity> nearby = p.getNearbyEntities(64, 64, 64);
        for (Entity e : nearby) p.hideEntity(this, e);
        p.getScheduler().runDelayed(this, t -> {
            for (Entity e : nearby) if (e.isValid()) p.showEntity(this, e);
        }, null, 2);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        bedrock.isBedrock(e.getPlayer());
        update(e.getPlayer(), e.getPlayer().getLocation(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hidden.remove(id);
        hideCooldownUntil.remove(id);
        bedrock.forget(id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getY() != e.getTo().getY()) update(e.getPlayer(), e.getTo(), false);
    }

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
