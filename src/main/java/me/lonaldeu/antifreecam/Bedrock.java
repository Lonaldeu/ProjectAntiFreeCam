package me.lonaldeu.antifreecam;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Detects Geyser/Floodgate players so their refresh radius can be smaller. APIs are reached by reflection. */
final class Bedrock {

    private final AntiFreeCam plugin;
    private final Map<UUID, Boolean> cache = new ConcurrentHashMap<>();

    boolean enabled, useGeyser, useFloodgate, useUuid, useNames, logDetection, logMethod;
    List<String> prefixes = List.of();
    int radiusReduction, minRadius;

    Bedrock(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    void load(FileConfiguration c) {
        enabled = c.getBoolean("bedrock.enabled", true);
        useGeyser = c.getBoolean("bedrock.detection.use-geyser-api", true);
        useFloodgate = c.getBoolean("bedrock.detection.use-floodgate-api", true);
        useUuid = c.getBoolean("bedrock.detection.use-uuid-pattern", true);
        useNames = c.getBoolean("bedrock.detection.use-name-prefix", true);
        prefixes = c.getStringList("bedrock.detection.name-prefixes");
        if (prefixes.isEmpty()) prefixes = List.of(".", "*", "!", "BE_");
        radiusReduction = c.getInt("bedrock.optimizations.chunk-radius-reduction", 1);
        minRadius = c.getInt("bedrock.optimizations.minimum-chunk-radius", 2);
        logDetection = c.getBoolean("bedrock.logging.log-detection", true);
        logMethod = c.getBoolean("bedrock.logging.log-detection-method", false);
        cache.clear();
    }

    int adjustRadius(Player p, int radius) {
        return radiusReduction > 0 && isBedrock(p) ? Math.max(minRadius, radius - radiusReduction) : radius;
    }

    boolean isBedrock(Player p) {
        if (!enabled) return false;
        return cache.computeIfAbsent(p.getUniqueId(), id -> {
            String method = detect(p);
            if (method != null && logDetection)
                plugin.getLogger().info("Bedrock player: " + p.getName() + (logMethod ? " (" + method + ")" : ""));
            return method != null;
        });
    }

    long onlineCount() {
        return Bukkit.getOnlinePlayers().stream().filter(this::isBedrock).count();
    }

    void forget(UUID id) {
        cache.remove(id);
    }

    boolean geyserPresent() {
        return Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot");
    }

    boolean floodgatePresent() {
        return Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    private String detect(Player p) {
        UUID id = p.getUniqueId();
        if (useGeyser && geyserPresent() && call("org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", id))
            return "Geyser API";
        if (useFloodgate && floodgatePresent() && call("org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", id))
            return "Floodgate API";
        if (useUuid && id.toString().startsWith("00000000-0000-0000-")) return "UUID pattern";
        if (useNames && prefixes.stream().anyMatch(p.getName()::startsWith)) return "name prefix";
        return null;
    }

    private static boolean call(String className, String getter, String check, UUID id) {
        try {
            Class<?> api = Class.forName(className);
            Object instance = api.getMethod(getter).invoke(null);
            return (Boolean) api.getMethod(check, UUID.class).invoke(instance, id);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
