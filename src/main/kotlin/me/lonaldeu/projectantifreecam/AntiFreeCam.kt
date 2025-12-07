/*
 * This project is a fork of TazAntixRAY
 * Original project: https://github.com/MinhTaz/TazAntixRAY
 * Original author: MinhTaz
 * Licensed under GNU General Public License v3.0
 */

package me.lonaldeu.projectantifreecam

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import me.lonaldeu.projectantifreecam.cache.MassiveCache
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.*
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.StringUtil
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AntiFreeCam : JavaPlugin(), Listener {

    // Using inline value class for zero-overhead player state tracking
    @JvmInline
    value class PlayerState(val isHidden: Boolean)

    val playerHiddenState = ConcurrentHashMap<UUID, Boolean>()
    private val refreshCooldowns = ConcurrentHashMap<UUID, Long>()
    private val internallyTeleporting = ConcurrentHashMap.newKeySet<UUID>()

    // Lateinit for faster initialization checks
    private lateinit var replacementBlockState: WrappedBlockState
    private var replacementBlockGlobalId = 0
    private var replacementBlockType = "air"
    
    // Config values as primitives for speed
    private var debugMode = false
    private var refreshCooldownMillis = 3000
    private var whitelistedWorlds = hashSetOf<String>()
    private var configViewDistance: Int? = null // null = use server default
    
    // Performance settings
    private var limitedAreaEnabled = false
    private var limitedAreaChunkRadius = 3
    private var instantProtectionEnabled = true
    private var instantLoadRadius = 15
    private var preLoadDistance = 10
    private var forceImmediateRefresh = true
    private var undergroundProtectionEnabled = true
    private var maxChunksPerTick = 50
    private var maxEntitiesPerTick = 100
    
    // Transition settings
    private var smoothTransitionEnabled = true
    private var transitionZoneSize = 5
    
    // Teleport handling
    private var teleportDelayTicks = 1L
    private var manageTeleportStates = true
    
    // Bedrock optimizations master switch
    private var bedrockOptimizationsEnabled = true

    // Optimizers - lazy initialization
    private var foliaOptimizer: FoliaOptimizer? = null
    private var paperOptimizer: PaperOptimizer? = null
    private var geyserFloodgateSupport: GeyserFloodgateSupport? = null
    private var chunkPacketListener: ChunkPacketListener? = null
    private var entityPacketListener: EntityPacketListener? = null
    private var entityHider: EntityHider? = null

    companion object {
        @Volatile
        private var instance: AntiFreeCam? = null
        
        @JvmStatic
        fun getInstance(): AntiFreeCam = instance ?: throw IllegalStateException("Plugin not initialized")
    }

    // Inline functions for zero-overhead logging
    private inline fun debugLog(message: () -> String) {
        if (debugMode) logger.info("[AntiFreeCam DEBUG] ${message()}")
    }

    private inline fun infoLog(message: () -> String) {
        logger.info("[AntiFreeCam] ${message()}")
    }

    private fun loadConfigValues() {
        saveDefaultConfig()
        reloadConfig()
        val cfg = config

        // Load world whitelist
        val worldsFromConfig = cfg.getStringList("worlds.whitelist").ifEmpty {
            cfg.getStringList("whitelisted-worlds").also { oldFormat ->
                if (oldFormat.isNotEmpty()) {
                    cfg.set("worlds.whitelist", oldFormat)
                    cfg.set("whitelisted-worlds", null)
                    saveConfig()
                    infoLog { "Migrated world whitelist to new config format." }
                }
            }
        }
        whitelistedWorlds = worldsFromConfig.toHashSet()
        debugLog { "Loaded whitelisted worlds: $whitelistedWorlds" }

        // Load settings using Kotlin's run for scoping
        refreshCooldownMillis = cfg.getInt("settings.refresh-cooldown-seconds", 3) * 1000
        debugMode = cfg.getBoolean("settings.debug-mode", false)

        // Load block replacement type
        replacementBlockType = cfg.getString("performance.replacement.block-type", "air")?.let {
            if (it.startsWith("minecraft:")) it else "minecraft:$it"
        } ?: "minecraft:air"

        // Load view distance (null = use server default)
        configViewDistance = cfg.getInt("settings.view-distance", -1).takeIf { it > 0 }
        
        // Load transition settings
        smoothTransitionEnabled = cfg.getBoolean("antixray.transition.smooth-transition", true)
        transitionZoneSize = cfg.getInt("antixray.transition.transition-zone-size", 5)
        
        // Load performance settings
        limitedAreaEnabled = cfg.getBoolean("performance.limited-area.enabled", false)
        limitedAreaChunkRadius = cfg.getInt("performance.limited-area.chunk-radius", 3)
        instantProtectionEnabled = cfg.getBoolean("performance.instant-protection.enabled", true)
        instantLoadRadius = cfg.getInt("performance.instant-protection.instant-load-radius", 15)
        preLoadDistance = cfg.getInt("performance.instant-protection.pre-load-distance", 10)
        forceImmediateRefresh = cfg.getBoolean("performance.instant-protection.force-immediate-refresh", true)
        undergroundProtectionEnabled = cfg.getBoolean("performance.underground-protection.enabled", true)
        maxChunksPerTick = cfg.getInt("performance.max-chunks-per-tick", 50)
        maxEntitiesPerTick = cfg.getInt("performance.max-entities-per-tick", 100)
        
        // Load advanced teleport settings
        teleportDelayTicks = cfg.getInt("advanced.teleport-handling.delay-ticks", 1).toLong()
        manageTeleportStates = cfg.getBoolean("advanced.teleport-handling.manage-teleport-states", true)
        
        // Load bedrock master switch
        bedrockOptimizationsEnabled = cfg.getBoolean("bedrock.optimizations.enabled", true)
        
        // Load and initialize massive RAM caching
        val ramCachingEnabled = cfg.getBoolean("performance.ram-caching", true)
        MassiveCache.initialize(ramCachingEnabled)
        
        if (ramCachingEnabled) {
            infoLog { "RAM caching enabled - trading ~12-15MB RAM for 10-15x CPU reduction" }
        } else {
            infoLog { "RAM caching disabled - using local caches (higher CPU)" }
        }
    }

    fun isWorldWhitelisted(worldName: String?): Boolean = worldName in whitelistedWorlds

    override fun onLoad() {
        instance = this

        runCatching {
            val packetEventsAPI = PacketEvents.getAPI() ?: run {
                logger.severe("""
                    === PACKETEVENTS ERROR ===
                    PacketEvents API is null!
                    Make sure PacketEvents plugin is installed and loaded BEFORE ProjectAntiFreeCam.
                    Download from: https://github.com/retrooper/packetevents/releases
                    ========================
                """.trimIndent())
                server.pluginManager.disablePlugin(this)
                return
            }

            logger.info("PacketEvents found. Version: ${packetEventsAPI.version}")
            packetEventsAPI.load()
            
            if (!packetEventsAPI.isLoaded) {
                logger.severe("PacketEvents failed to load!")
                server.pluginManager.disablePlugin(this)
                return
            }
            logger.info("PacketEvents loaded successfully!")
        }.onFailure { e ->
            when (e) {
                is NoClassDefFoundError -> logger.severe("PacketEvents plugin is not installed!")
                else -> {
                    logger.severe("Failed to initialize PacketEvents: ${e.message}")
                    logger.severe(e.stackTraceToString())
                }
            }
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onEnable() {
        // Display startup banner
        MessageFormatter.createStartupBanner("1.3.0", 
            if (PlatformCompatibility.isFolia()) "Folia" else "Paper/Spigot"
        ).forEach { line ->
            if (line.isNotBlank()) {
                server.consoleSender.sendMessage(MessageFormatter.format(line))
            } else {
                server.consoleSender.sendMessage("")
            }
        }
        
        // Version info
        logger.info("Detected: ${PlatformCompatibility.getPlatformInfo()}")

        // Initialize platform-specific optimizers
        when {
            PlatformCompatibility.isFolia() && FoliaOptimizer.shouldUseFoliaOptimizations() -> {
                foliaOptimizer = FoliaOptimizer(this)
            }
            PaperOptimizer.shouldUsePaperOptimizations() -> {
                paperOptimizer = PaperOptimizer(this)
            }
        }

        geyserFloodgateSupport = GeyserFloodgateSupport(this)
        entityHider = EntityHider(this)
        
        loadConfigValues()

        val packetEventsAPI = PacketEvents.getAPI()
        if (packetEventsAPI == null || !packetEventsAPI.isLoaded) {
            logger.severe("PacketEvents API not available. ProjectAntiFreeCam will not function.")
            server.pluginManager.disablePlugin(this)
            return
        }

        // Initialize replacement block state
        initReplacementBlockState()
        
        if (!::replacementBlockState.isInitialized) {
            logger.severe("Could not initialize replacement block state. Plugin disabled.")
            server.pluginManager.disablePlugin(this)
            return
        }

        packetEventsAPI.settings.checkForUpdates(true)
        chunkPacketListener = ChunkPacketListener(this)
        entityPacketListener = EntityPacketListener(this)
        packetEventsAPI.eventManager.registerListener(chunkPacketListener, PacketListenerPriority.NORMAL)
        packetEventsAPI.eventManager.registerListener(entityPacketListener, PacketListenerPriority.NORMAL)
        Bukkit.getPluginManager().registerEvents(this, this)

        // Register commands
        registerCommands()

        // Initialize PacketEvents on correct thread
        PlatformCompatibility.runTask(this) {
            if (isEnabled && packetEventsAPI.isLoaded) {
                packetEventsAPI.init()
                infoLog { "PacketEvents initialized." }
            }
        }

        // Process online players
        Bukkit.getOnlinePlayers()
            .filter { isWorldWhitelisted(it.world.name) }
            .forEach { handlePlayerInitialState(it, false) }
    }

    private fun initReplacementBlockState() {
        runCatching {
            replacementBlockState = WrappedBlockState.getByString(replacementBlockType)
            replacementBlockGlobalId = replacementBlockState.globalId
        }.onFailure { e ->
            logger.warning("Failed to init block state ($replacementBlockType): ${e.message}")
            
            // Try fallbacks
            sequenceOf("minecraft:air", "minecraft:stone", "minecraft:dirt")
                .firstNotNullOfOrNull { fallback ->
                    runCatching {
                        WrappedBlockState.getByString(fallback).also {
                            replacementBlockState = it
                            replacementBlockGlobalId = it.globalId
                            replacementBlockType = fallback
                            logger.info("Fell back to $fallback")
                        }
                    }.getOrNull()
                }
        }
    }

    private fun registerCommands() {
        listOf("antifreecam", "afcdebug", "afcreload", "afcworld").forEach { cmd ->
            getCommand(cmd)?.apply {
                setExecutor(this@AntiFreeCam)
                tabCompleter = this@AntiFreeCam
            }
        }
    }

    override fun onDisable() {
        infoLog { "Shutting down..." }
        
        MessageFormatter.createShutdownBanner().forEach { line ->
            if (line.isNotBlank()) {
                server.consoleSender.sendMessage(MessageFormatter.format(line))
            }
        }

        PacketEvents.getAPI()?.takeIf { it.isLoaded }?.terminate()
        paperOptimizer?.shutdown()
        playerHiddenState.clear()
        
        // Clean up MassiveCache if enabled
        if (MassiveCache.ramCachingEnabled) {
            val stats = MassiveCache.getStats()
            infoLog { "MassiveCache shutdown stats: $stats" }
            MassiveCache.clearAll()
        }
        
        logger.info("ProjectAntiFreeCam v1.3.0 disabled.")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        return when (command.name.lowercase()) {
            "antifreecam" -> handleMainCommand(sender, args)
            "afcdebug" -> handleDebugCommand(sender)
            "afcreload" -> handleReloadCommand(sender)
            "afcworld" -> handleWorldCommand(sender, args)
            else -> false
        }
    }

    private fun handleMainCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("antifreecam.admin")) {
            MessageFormatter.sendError(sender, MessageFormatter.createPermissionDenied())
            return true
        }

        if (args.isEmpty()) {
            MessageFormatter.sendInfo(sender, "Usage: /antifreecam <debug|reload|world|stats|test> [args]")
            return true
        }

        val subArgs = args.drop(1).toTypedArray()
        return when (args[0].lowercase()) {
            "debug" -> handleDebugCommand(sender)
            "reload" -> handleReloadCommand(sender)
            "world" -> handleWorldCommand(sender, subArgs)
            "stats" -> handleStatsCommand(sender)
            "test" -> handleTestCommand(sender, subArgs)
            else -> {
                MessageFormatter.sendError(sender, "Unknown subcommand: ${args[0]}")
                true
            }
        }
    }

    private fun handleDebugCommand(sender: CommandSender): Boolean {
        if (!sender.hasPermission("antifreecam.admin")) {
            MessageFormatter.sendError(sender, MessageFormatter.createPermissionDenied())
            return true
        }
        debugMode = !debugMode
        config.set("settings.debug-mode", debugMode)
        saveConfig()
        
        val status = if (debugMode) "§aENABLED" else "§7DISABLED"
        MessageFormatter.sendSuccess(sender, "Debug mode $status")
        return true
    }

    private fun handleReloadCommand(sender: CommandSender): Boolean {
        if (!sender.hasPermission("antifreecam.admin")) {
            MessageFormatter.sendError(sender, MessageFormatter.createPermissionDenied())
            return true
        }
        reloadConfig()
        loadConfigValues()
        chunkPacketListener?.refreshCaches() // Refresh RAM caches on config reload
        entityPacketListener?.refreshCaches() // Refresh entity caches on config reload
        entityHider?.loadSettings() // Refresh EntityHider settings
        geyserFloodgateSupport?.reloadConfig() // Refresh Bedrock detection settings
        MessageFormatter.sendSuccess(sender, MessageFormatter.createReloadSuccess())
        MessageFormatter.sendInfo(sender, "Active worlds: §b$whitelistedWorlds")
        
        // Show Bedrock support status
        geyserFloodgateSupport?.let {
            MessageFormatter.sendInfo(sender, "§dBedrock: §f${it.bedrockPlayerCount} players detected")
        }
        
        Bukkit.getOnlinePlayers().forEach { player ->
            if (isWorldWhitelisted(player.world.name)) {
                handlePlayerInitialState(player, true)
            } else {
                playerHiddenState.remove(player.uniqueId)?.let { refreshFullView(player) }
            }
        }
        return true
    }

    private fun handleWorldCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("antifreecam.admin")) {
            MessageFormatter.sendError(sender, MessageFormatter.createPermissionDenied())
            return true
        }

        if (args.isEmpty()) {
            MessageFormatter.sendInfo(sender, "Usage: /afcworld <add|remove|list> [world]")
            return true
        }

        when (args[0].lowercase()) {
            "list" -> {
                if (whitelistedWorlds.isEmpty()) {
                    MessageFormatter.sendInfo(sender, "No worlds whitelisted.")
                } else {
                    MessageFormatter.sendSuccess(sender, "Whitelisted: ${whitelistedWorlds.joinToString()}")
                }
            }
            "add" -> {
                val worldName = args.getOrNull(1) ?: run {
                    MessageFormatter.sendError(sender, "Specify a world name.")
                    return true
                }
                if (Bukkit.getWorld(worldName) == null) {
                    MessageFormatter.sendError(sender, "World '$worldName' not found.")
                    return true
                }
                if (whitelistedWorlds.add(worldName)) {
                    config.set("worlds.whitelist", whitelistedWorlds.toList())
                    saveConfig()
                    MessageFormatter.sendSuccess(sender, "World '$worldName' added.")
                    Bukkit.getOnlinePlayers()
                        .filter { it.world.name == worldName }
                        .forEach { handlePlayerInitialState(it, true) }
                }
            }
            "remove" -> {
                val worldName = args.getOrNull(1) ?: run {
                    MessageFormatter.sendError(sender, "Specify a world name.")
                    return true
                }
                if (whitelistedWorlds.remove(worldName)) {
                    config.set("worlds.whitelist", whitelistedWorlds.toList())
                    saveConfig()
                    MessageFormatter.sendSuccess(sender, "World '$worldName' removed.")
                    Bukkit.getOnlinePlayers()
                        .filter { it.world.name == worldName }
                        .forEach { 
                            playerHiddenState.remove(it.uniqueId)
                            refreshFullView(it)
                        }
                }
            }
        }
        return true
    }

    private fun handleStatsCommand(sender: CommandSender): Boolean {
        if (!sender.hasPermission("antifreecam.admin")) {
            MessageFormatter.sendError(sender, MessageFormatter.createPermissionDenied())
            return true
        }

        MessageFormatter.send(sender, "§3§l=== ProjectAntiFreeCam Statistics ===")
        MessageFormatter.send(sender, "§eVersion: §a1.3.0")
        MessageFormatter.send(sender, "§ePlatform: §a${if (PlatformCompatibility.isFolia()) "Folia" else "Paper/Spigot"}")
        MessageFormatter.send(sender, "§eActive Players: §a${playerHiddenState.size}")
        MessageFormatter.send(sender, "§eActive Worlds: §a${whitelistedWorlds.size}")
        MessageFormatter.send(sender, "§eDebug: §a${if (debugMode) "ON" else "OFF"}")
        
        // Show MassiveCache stats
        MessageFormatter.send(sender, "§3§l=== RAM Cache Statistics ===")
        MessageFormatter.send(sender, "§eRAM Caching: §a${if (MassiveCache.ramCachingEnabled) "ENABLED" else "DISABLED"}")
        if (MassiveCache.ramCachingEnabled) {
            val stats = MassiveCache.getStats()
            MessageFormatter.send(sender, "§eEntity Positions: §a${stats.entityPositionCacheSize} cached")
            MessageFormatter.send(sender, "§eChunk Metadata: §a${stats.chunkCacheSize} cached")
            MessageFormatter.send(sender, "§eVisibility Decisions: §a${stats.visibilityDecisionCacheSize} cached")
            MessageFormatter.send(sender, "§eEstimated RAM: §a${"%.2f".format(stats.totalEstimatedMemoryMB)} MB")
            MessageFormatter.send(sender, "§3§l=== Cache Performance ===")
            MessageFormatter.send(sender, "§eCache Hits: §a${stats.cacheHits}")
            MessageFormatter.send(sender, "§eCache Misses: §a${stats.cacheMisses}")
            MessageFormatter.send(sender, "§eHit Rate: §a${"%.2f".format(stats.hitRatePercent)}%")
            
            // Performance indicator
            when {
                stats.hitRatePercent > 80 -> MessageFormatter.send(sender, "§a✓ Excellent cache performance!")
                stats.hitRatePercent > 50 -> MessageFormatter.send(sender, "§e⚠ Moderate cache performance")
                else -> MessageFormatter.send(sender, "§c✗ Poor cache performance")
            }
        }
        
        geyserFloodgateSupport?.let { MessageFormatter.send(sender, "§eGeyser/Floodgate: §a${it.supportStatus}") }
        foliaOptimizer?.let { MessageFormatter.send(sender, "§eFolia Stats: §a${it.optimizationStats}") }
        paperOptimizer?.let { MessageFormatter.send(sender, "§ePaper Stats: §a${it.optimizationStats}") }
        return true
    }

    private fun handleTestCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("antifreecam.admin")) {
            MessageFormatter.sendError(sender, MessageFormatter.createPermissionDenied())
            return true
        }

        val player = sender as? Player ?: run {
            MessageFormatter.sendError(sender, "Players only.")
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "block" -> {
                MessageFormatter.sendInfo(sender, "§3=== Block Test ===")
                MessageFormatter.sendInfo(sender, "§eBlock: §b$replacementBlockType")
                MessageFormatter.sendInfo(sender, "§eGlobal ID: §b$replacementBlockGlobalId")
            }
            "state" -> {
                val isHidden = playerHiddenState[player.uniqueId] ?: false
                val protectionY = config.getDouble("antixray.protection-y-level", 31.0)
                MessageFormatter.sendInfo(sender, "§3=== State Test ===")
                MessageFormatter.sendInfo(sender, "§eY: §b${player.location.y.format(1)}")
                MessageFormatter.sendInfo(sender, "§eProtection Y: §b$protectionY")
                MessageFormatter.sendInfo(sender, "§eAnti-XRay: §b$isHidden")
            }
            "refresh" -> {
                if (isWorldWhitelisted(player.world.name)) {
                    refreshFullView(player)
                    MessageFormatter.sendSuccess(sender, "View refreshed!")
                } else {
                    MessageFormatter.sendWarning(sender, "World not whitelisted.")
                }
            }
            else -> {
                MessageFormatter.sendInfo(sender, "Tests: block, state, refresh")
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when {
            command.name.equals("afcworld", true) -> when (args.size) {
                1 -> StringUtil.copyPartialMatches(args[0], listOf("list", "add", "remove"), mutableListOf())
                2 -> when (args[0].lowercase()) {
                    "remove" -> StringUtil.copyPartialMatches(args[1], whitelistedWorlds.toList(), mutableListOf())
                    "add" -> StringUtil.copyPartialMatches(args[1], Bukkit.getWorlds().map { it.name }, mutableListOf())
                    else -> emptyList()
                }
                else -> emptyList()
            }
            command.name.equals("antifreecam", true) -> when (args.size) {
                1 -> StringUtil.copyPartialMatches(args[0], listOf("debug", "reload", "world", "stats", "test"), mutableListOf())
                2 -> when (args[0].lowercase()) {
                    "world" -> StringUtil.copyPartialMatches(args[1], listOf("list", "add", "remove"), mutableListOf())
                    "test" -> StringUtil.copyPartialMatches(args[1], listOf("block", "state", "refresh"), mutableListOf())
                    else -> emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    fun handlePlayerInitialState(player: Player, immediateRefresh: Boolean) {
        if (!isWorldWhitelisted(player.world.name)) {
            playerHiddenState.remove(player.uniqueId)?.let {
                if (immediateRefresh) refreshFullView(player)
            }
            return
        }

        val currentY = player.location.y
        val protectionY = config.getDouble("antixray.protection-y-level", 31.0)
        val initialHidden = undergroundProtectionEnabled && currentY >= protectionY
        playerHiddenState[player.uniqueId] = initialHidden
        
        // Note: We don't send destroy packets for already-rendered entities
        // The packet listener will block NEW spawn packets when player is above Y31
        
        if (immediateRefresh || initialHidden) {
            refreshFullView(player)
        }
    }

    fun refreshFullView(player: Player) {
        performRefresh(player, Bukkit.getServer().viewDistance)
    }
    
    /**
     * Force respawn of all nearby entities by hiding and showing them
     * This ensures entities become visible when transitioning from Y100 to Y31
     * IMPORTANT: On Folia, each entity operation must run on the entity's owning region thread
     */
    private fun refreshNearbyEntities(player: Player, radius: Double = 64.0) {
        if (!player.isOnline) return
        
        // Run on player's region thread to get nearby entities
        PlatformCompatibility.runTask(this, player.location) {
            if (!player.isOnline) return@runTask
            
            val nearbyEntities = player.getNearbyEntities(radius, radius, radius)
            debugLog { "Refreshing ${nearbyEntities.size} entities for ${player.name}" }
            
            // Hide each entity using entity-specific scheduler
            nearbyEntities.forEach { entity ->
                PlatformCompatibility.runEntityTask(this, entity, Runnable {
                    if (player.isOnline && entity.isValid) {
                        player.hideEntity(this, entity)
                    }
                })
            }
            
            // Re-show entities after 2 ticks, each on its own entity thread
            PlatformCompatibility.runTaskLater(this, Runnable {
                if (!player.isOnline) return@Runnable
                
                nearbyEntities.forEach { entity ->
                    PlatformCompatibility.runEntityTask(this, entity, Runnable {
                        if (player.isOnline && entity.isValid) {
                            player.showEntity(this, entity)
                        }
                    })
                }
                debugLog { "Re-showed entities for ${player.name}" }
            }, 2L)
        }
    }

    private fun performRefresh(player: Player, radiusChunks: Int) {
        if (!player.isOnline || !isWorldWhitelisted(player.world.name)) return

        if (!PlatformCompatibility.isOwnedByCurrentRegion(player.location)) {
            PlatformCompatibility.runTask(this, player.location) {
                performRefresh(player, radiusChunks)
            }
            return
        }

        refreshChunksOptimized(player, radiusChunks)
    }

    private fun refreshChunksOptimized(player: Player, radiusChunks: Int) {
        var finalRadius = if (limitedAreaEnabled) minOf(radiusChunks, limitedAreaChunkRadius) else radiusChunks

        geyserFloodgateSupport?.let {
            if (it.isBedrockPlayer(player)) {
                finalRadius = it.getOptimizedChunkRadius(player, finalRadius)
            }
        }

        if (instantProtectionEnabled && forceImmediateRefresh) {
            val playerY = player.location.y
            val protectionY = config.getDouble("antixray.protection-y-level", 31.0)
            val hideBelow = config.getInt("antixray.hide-below-y", 16)
            
            if (playerY <= protectionY + preLoadDistance && playerY > hideBelow) {
                finalRadius = maxOf(finalRadius, instantLoadRadius)
            }
        }

        when {
            foliaOptimizer != null -> foliaOptimizer!!.refreshChunksOptimized(player, finalRadius)
            paperOptimizer != null -> paperOptimizer!!.refreshChunksOptimized(player, finalRadius)
            else -> refreshChunksBasic(player, finalRadius)
        }
    }

    private fun refreshChunksBasic(player: Player, radiusChunks: Int) {
        val world = player.world
        val loc = player.location
        val playerChunkX = loc.blockX shr 4
        val playerChunkZ = loc.blockZ shr 4

        for (cx in (playerChunkX - radiusChunks)..(playerChunkX + radiusChunks)) {
            for (cz in (playerChunkZ - radiusChunks)..(playerChunkZ + radiusChunks)) {
                runCatching { world.refreshChunk(cx, cz) }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        debugLog { "Player joined: ${player.name}" }
        
        geyserFloodgateSupport?.let {
            if (it.isBedrockPlayer(player)) debugLog { "Bedrock player: ${player.name}" }
        }

        if (isWorldWhitelisted(player.world.name)) {
            handlePlayerInitialState(player, false)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        refreshCooldowns.remove(playerId)
        playerHiddenState.remove(playerId)
        foliaOptimizer?.cleanupPlayer(playerId)
        paperOptimizer?.cleanupPlayer(playerId)
        geyserFloodgateSupport?.cleanupPlayer(playerId)
        chunkPacketListener?.cleanupPlayer(playerId)
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player
        val toWorld = player.world

        if (isWorldWhitelisted(toWorld.name)) {
            handlePlayerInitialState(player, true)
        } else {
            playerHiddenState.remove(player.uniqueId)?.let { refreshFullView(player) }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val player = event.player
        if (player.uniqueId in internallyTeleporting) return

        val to = event.to
        val toWorldWhitelisted = isWorldWhitelisted(to.world.name)
        val fromWorldWhitelisted = isWorldWhitelisted(event.from.world.name)

        if (!toWorldWhitelisted) {
            if (fromWorldWhitelisted) {
                playerHiddenState.remove(player.uniqueId)?.let {
                    PlatformCompatibility.runTask(this, to) {
                        if (player.isOnline) refreshFullView(player)
                    }
                }
            }
            return
        }

        val protectionY = 31.0
        val oldHidden = playerHiddenState[player.uniqueId] ?: (to.y >= protectionY)
        val newHidden = to.y >= protectionY

        if (oldHidden == newHidden) return

        if (!newHidden) {
            playerHiddenState[player.uniqueId] = false
            if (manageTeleportStates) {
                event.isCancelled = true
                PlatformCompatibility.runTaskLater(this, Runnable {
                    PlatformCompatibility.runTask(this, to) {
                        if (!player.isOnline) return@runTask
                        internallyTeleporting.add(player.uniqueId)
                        // Use PlatformCompatibility.teleportAsync for Folia compatibility
                        PlatformCompatibility.teleportAsync(player, to) { success ->
                            internallyTeleporting.remove(player.uniqueId)
                            if (!success) {
                                debugLog { "Failed to teleport ${player.name} to Y=${to.y}" }
                            }
                        }
                    }
                }, teleportDelayTicks)
            }
        } else {
            playerHiddenState[player.uniqueId] = true
        }
    }

    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        val player = event.player
        val to = event.to
        val from = event.from

        if (!isWorldWhitelisted(player.world.name)) {
            playerHiddenState.remove(player.uniqueId)?.let { refreshFullView(player) }
            return
        }

        if (from.blockY == to.blockY) return

        val currentY = to.y
        val playerId = player.uniqueId
        val protectionY = config.getDouble("antixray.protection-y-level", 31.0)

        val oldHidden = playerHiddenState[playerId] ?: (currentY >= protectionY)
        val newHidden = undergroundProtectionEnabled && currentY >= protectionY

        if (newHidden != oldHidden) {
            playerHiddenState[playerId] = newHidden

            // Update entity visibility using Bukkit API
            entityHider?.handlePlayerMovement(player, from, to)
            
            // When transitioning from protected (Y100) to unprotected (Y31)
            // Force a view refresh to make entities/players visible
            if (!newHidden) {
                // Small delay to ensure state is updated before refresh
                PlatformCompatibility.runTaskLater(this, Runnable {
                    if (player.isOnline) {
                        debugLog { "${player.name} transitioned to unprotected mode - refreshing view" }
                        // Refresh chunks first
                        refreshFullView(player)
                        // Then force entity respawn after another tick
                        PlatformCompatibility.runTaskLater(this, Runnable {
                            if (player.isOnline) {
                                refreshNearbyEntities(player, 64.0)
                            }
                        }, 2L)
                    }
                }, 2L)
            } else {
                // Going from unprotected to protected
                val currentTime = System.currentTimeMillis()
                val expirationTime = refreshCooldowns[playerId] ?: 0L
                
                if (currentTime >= expirationTime) {
                    refreshFullView(player)
                    refreshCooldowns[playerId] = currentTime + refreshCooldownMillis
                    debugLog { "${player.name} transitioned to protected mode - refreshing view" }
                }
            }
        }
    }

    // Accessors
    fun getReplacementBlockState(): WrappedBlockState = replacementBlockState
    fun getReplacementBlockGlobalId(): Int = replacementBlockGlobalId
    fun getReplacementBlockType(): String = replacementBlockType
    fun getGeyserFloodgateSupport(): GeyserFloodgateSupport? = geyserFloodgateSupport
    fun isDebugMode(): Boolean = debugMode
    fun getConfigViewDistance(): Int = configViewDistance ?: Bukkit.getViewDistance()
    fun getMaxChunksPerTick(): Int = maxChunksPerTick
    fun getMaxEntitiesPerTick(): Int = maxEntitiesPerTick
    fun isSmoothTransitionEnabled(): Boolean = smoothTransitionEnabled
    fun getTransitionZoneSize(): Int = transitionZoneSize
    fun getTeleportDelayTicks(): Long = teleportDelayTicks
    fun shouldManageTeleportStates(): Boolean = manageTeleportStates
    fun isBedrockOptimizationsEnabled(): Boolean = bedrockOptimizationsEnabled

    // Extension for formatting doubles
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
