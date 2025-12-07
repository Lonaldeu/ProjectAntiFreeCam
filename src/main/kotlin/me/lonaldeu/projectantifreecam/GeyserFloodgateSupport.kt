package me.lonaldeu.projectantifreecam

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Enhanced Geyser/Floodgate support with multiple detection methods:
 * 1. Geyser API (if available)
 * 2. Floodgate API (if available)  
 * 3. UUID pattern detection (Bedrock UUIDs start with 00000000-0000-0000-)
 * 4. Player name prefix detection (configurable prefixes)
 */
class GeyserFloodgateSupport(private val plugin: Plugin) {

    private val bedrockPlayers = ConcurrentHashMap.newKeySet<UUID>()
    
    // Cache detection method used for each player (for logging)
    private val detectionMethods = ConcurrentHashMap<UUID, DetectionMethod>()

    // Lazy reflection-based API access
    private val geyserApi: Any? by lazy { initGeyserApi() }
    private val floodgateApi: Any? by lazy { initFloodgateApi() }
    private val isBedrockPlayerMethod by lazy { geyserApi?.javaClass?.getMethod("isBedrockPlayer", UUID::class.java) }
    private val floodgateIsBedrockMethod by lazy { floodgateApi?.javaClass?.getMethod("isFloodgatePlayer", UUID::class.java) }

    val isGeyserAvailable: Boolean by lazy { geyserApi != null }
    val isFloodgateAvailable: Boolean by lazy { floodgateApi != null }
    
    // Configurable settings (loaded from config)
    @Volatile private var bedrockEnabled = true
    @Volatile private var useGeyserApi = true
    @Volatile private var useFloodgateApi = true
    @Volatile private var useUuidPattern = true
    @Volatile private var useNamePrefix = true
    @Volatile private var namePrefixes: Set<String> = setOf(".", "*", "!", "BE_")
    @Volatile private var optimizationsEnabled = true  // Master switch for bedrock optimizations
    @Volatile private var chunkRadiusReduction = 1
    @Volatile private var minimumChunkRadius = 2
    @Volatile private var lighterEntityHiding = true
    @Volatile private var logDetection = true
    @Volatile private var logDetectionMethod = false
    
    enum class DetectionMethod {
        GEYSER_API,
        FLOODGATE_API,
        UUID_PATTERN,
        NAME_PREFIX,
        NONE
    }
    
    init {
        reloadConfig()
    }
    
    /**
     * Reload configuration values
     */
    fun reloadConfig() {
        val config = plugin.config
        bedrockEnabled = config.getBoolean("bedrock.enabled", true)
        useGeyserApi = config.getBoolean("bedrock.detection.use-geyser-api", true)
        useFloodgateApi = config.getBoolean("bedrock.detection.use-floodgate-api", true)
        useUuidPattern = config.getBoolean("bedrock.detection.use-uuid-pattern", true)
        useNamePrefix = config.getBoolean("bedrock.detection.use-name-prefix", true)
        namePrefixes = config.getStringList("bedrock.detection.name-prefixes")
            .ifEmpty { listOf(".", "*", "!", "BE_") }
            .toSet()
        optimizationsEnabled = config.getBoolean("bedrock.optimizations.enabled", true)
        chunkRadiusReduction = config.getInt("bedrock.optimizations.chunk-radius-reduction", 1)
        minimumChunkRadius = config.getInt("bedrock.optimizations.minimum-chunk-radius", 2)
        lighterEntityHiding = config.getBoolean("bedrock.optimizations.lighter-entity-hiding", true)
        logDetection = config.getBoolean("bedrock.logging.log-detection", true)
        logDetectionMethod = config.getBoolean("bedrock.logging.log-detection-method", false)
        
        // Clear caches on config reload
        bedrockPlayers.clear()
        detectionMethods.clear()
    }

    private fun initGeyserApi(): Any? {
        val geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot")
            ?: Bukkit.getPluginManager().getPlugin("Geyser")
        
        return if (geyserPlugin?.isEnabled == true) {
            runCatching {
                val apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi")
                apiClass.getMethod("api").invoke(null).also {
                    plugin.logger.info("✓ Geyser API detected and enabled")
                }
            }.onFailure { e ->
                plugin.logger.warning("Failed to initialize Geyser API: ${e.message}")
            }.getOrNull()
        } else null
    }

    private fun initFloodgateApi(): Any? {
        val floodgatePlugin = Bukkit.getPluginManager().getPlugin("floodgate")
        
        return if (floodgatePlugin?.isEnabled == true) {
            runCatching {
                val apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
                apiClass.getMethod("getInstance").invoke(null).also {
                    plugin.logger.info("✓ Floodgate API detected and enabled")
                }
            }.onFailure { e ->
                plugin.logger.warning("Failed to initialize Floodgate API: ${e.message}")
            }.getOrNull()
        } else null
    }

    /**
     * Check if a player is on Bedrock Edition using multiple detection methods
     */
    fun isBedrockPlayer(player: Player): Boolean = isBedrockPlayer(player.uniqueId, player.name)
    
    fun isBedrockPlayer(playerId: UUID, playerName: String? = null): Boolean {
        if (!bedrockEnabled) return false
        
        // Check cache first
        if (playerId in bedrockPlayers) return true

        // Try detection methods in order
        val (isBedrock, method) = detectBedrockPlayer(playerId, playerName)
        
        if (isBedrock) {
            bedrockPlayers.add(playerId)
            detectionMethods[playerId] = method
            
            if (logDetection) {
                val name = playerName ?: Bukkit.getPlayer(playerId)?.name ?: playerId.toString()
                val methodInfo = if (logDetectionMethod) " [${method.name}]" else ""
                plugin.logger.info("🎮 Bedrock player detected: $name$methodInfo")
            }
        }
        
        return isBedrock
    }
    
    /**
     * Detect Bedrock player using configured detection methods in priority order
     */
    private fun detectBedrockPlayer(playerId: UUID, playerName: String?): Pair<Boolean, DetectionMethod> {
        // 1. Try Geyser API
        if (useGeyserApi && checkGeyserBedrock(playerId)) {
            return true to DetectionMethod.GEYSER_API
        }
        
        // 2. Try Floodgate API
        if (useFloodgateApi && checkFloodgateBedrock(playerId)) {
            return true to DetectionMethod.FLOODGATE_API
        }
        
        // 3. Try UUID pattern detection
        if (useUuidPattern && checkUuidPattern(playerId)) {
            return true to DetectionMethod.UUID_PATTERN
        }
        
        // 4. Try name prefix detection
        val name = playerName ?: Bukkit.getPlayer(playerId)?.name
        if (useNamePrefix && name != null && checkNamePrefix(name)) {
            return true to DetectionMethod.NAME_PREFIX
        }
        
        return false to DetectionMethod.NONE
    }

    private fun checkGeyserBedrock(playerId: UUID): Boolean {
        if (!isGeyserAvailable) return false
        return runCatching {
            isBedrockPlayerMethod?.invoke(geyserApi, playerId) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun checkFloodgateBedrock(playerId: UUID): Boolean {
        if (!isFloodgateAvailable) return false
        return runCatching {
            floodgateIsBedrockMethod?.invoke(floodgateApi, playerId) as? Boolean ?: false
        }.getOrDefault(false)
    }
    
    /**
     * Check UUID pattern - Bedrock Edition UUIDs from Floodgate start with:
     * 00000000-0000-0000-000X-XXXXXXXXXXXX
     * The first 3 groups are all zeros
     */
    private fun checkUuidPattern(playerId: UUID): Boolean {
        val uuidString = playerId.toString()
        // Bedrock UUIDs: 00000000-0000-0000-XXXX-XXXXXXXXXXXX
        return uuidString.startsWith("00000000-0000-0000-")
    }
    
    /**
     * Check if player name starts with any configured Bedrock prefix
     */
    private fun checkNamePrefix(playerName: String): Boolean {
        return namePrefixes.any { prefix -> playerName.startsWith(prefix) }
    }
    
    /**
     * Get the detection method used for a player
     */
    fun getDetectionMethod(playerId: UUID): DetectionMethod {
        return detectionMethods[playerId] ?: DetectionMethod.NONE
    }

    /**
     * Check if Bedrock optimizations should apply
     */
    fun shouldApplyBedrockOptimizations(player: Player): Boolean {
        return bedrockEnabled && optimizationsEnabled && isBedrockPlayer(player)
    }
    
    /**
     * Check if lighter entity hiding should be used for this player
     */
    fun shouldUseLighterEntityHiding(player: Player): Boolean {
        return optimizationsEnabled && lighterEntityHiding && isBedrockPlayer(player)
    }

    /**
     * Get optimized chunk radius for Bedrock players (configurable)
     */
    fun getOptimizedChunkRadius(player: Player, defaultRadius: Int): Int {
        return if (optimizationsEnabled && isBedrockPlayer(player) && chunkRadiusReduction > 0) {
            maxOf(minimumChunkRadius, defaultRadius - chunkRadiusReduction)
        } else {
            defaultRadius
        }
    }

    /**
     * Check if special packet handling is needed
     */
    fun needsSpecialPacketHandling(player: Player): Boolean = isBedrockPlayer(player)

    fun cleanupPlayer(playerId: UUID) {
        bedrockPlayers.remove(playerId)
        detectionMethods.remove(playerId)
    }

    val supportStatus: String
        get() = buildString {
            append("Bedrock Support: ")
            if (!bedrockEnabled) {
                append("Disabled")
                return@buildString
            }
            appendLine("Enabled")
            append("  ├─ Geyser API: ${if (isGeyserAvailable) "✓" else "✗"}")
            appendLine()
            append("  ├─ Floodgate API: ${if (isFloodgateAvailable) "✓" else "✗"}")
            appendLine()
            append("  ├─ UUID Pattern: ${if (useUuidPattern) "✓" else "✗"}")
            appendLine()
            append("  ├─ Name Prefix: ${if (useNamePrefix) "✓ (${namePrefixes.joinToString()})" else "✗"}")
            appendLine()
            append("  └─ Bedrock Players Online: ${bedrockPlayers.size}")
        }

    val isSupported: Boolean
        get() = bedrockEnabled && (isGeyserAvailable || isFloodgateAvailable || useUuidPattern || useNamePrefix)

    val bedrockPlayerCount: Int
        get() = bedrockPlayers.size

    fun refreshPlayerStatus(player: Player) {
        bedrockPlayers.remove(player.uniqueId)
        detectionMethods.remove(player.uniqueId)
        isBedrockPlayer(player) // Re-check and cache
    }

    fun performPeriodicCleanup() {
        bedrockPlayers.removeIf { playerId ->
            val online = Bukkit.getPlayer(playerId)?.isOnline == true
            if (!online) detectionMethods.remove(playerId)
            !online
        }
    }
    
    /**
     * Get all currently detected Bedrock players with their detection methods
     */
    fun getBedrockPlayerInfo(): Map<UUID, DetectionMethod> {
        return detectionMethods.toMap()
    }
}
