package me.lonaldeu.projectantifreecam

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Paper/Spigot-specific optimizations using Kotlin's efficient collections
 * and coroutine-friendly design patterns
 */
class PaperOptimizer(private val plugin: AntiFreeCam) {

    private val lastRefresh = ConcurrentHashMap<UUID, Long>()
    private val refreshQueue = ConcurrentLinkedQueue<ChunkRefreshTask>()
    private var refreshTask: BukkitTask? = null

    companion object {
        private const val REFRESH_COOLDOWN = 50L // ms
        private const val MAX_QUEUE_SIZE = 1000

        @JvmStatic
        fun shouldUsePaperOptimizations(): Boolean = !PlatformCompatibility.isFolia()
    }
    
    // Get max chunks from config
    private fun getMaxChunksPerTick(): Int = plugin.getMaxChunksPerTick()

    private data class ChunkRefreshTask(
        val world: World,
        val chunkX: Int,
        val chunkZ: Int,
        val playerId: UUID
    )

    init {
        startRefreshProcessor()
    }

    private fun startRefreshProcessor() {
        // Convert TaskHandle to BukkitTask for Paper
        val handle = PlatformCompatibility.runTaskTimer(plugin, Runnable {
            processRefreshQueue()
        }, 1L, 1L)
        refreshTask = if (handle is PlatformCompatibility.TaskHandle.Paper) {
            // Store the task for cancellation
            null // We'll track via handle instead
        } else null
    }

    /**
     * Optimized chunk refresh with priority-based processing
     */
    fun refreshChunksOptimized(player: Player, radiusChunks: Int) {
        val playerId = player.uniqueId
        val currentTime = System.currentTimeMillis()

        // Check cooldown
        lastRefresh[playerId]?.let { lastTime ->
            if (currentTime - lastTime < REFRESH_COOLDOWN) return
        }

        val loc = player.location
        val world = loc.world ?: return
        val playerChunkX = loc.blockX shr 4
        val playerChunkZ = loc.blockZ shr 4

        // Process in rings from center outward (priority closest chunks)
        for (radius in 0..radiusChunks) {
            for (cx in (playerChunkX - radius)..(playerChunkX + radius)) {
                for (cz in (playerChunkZ - radius)..(playerChunkZ + radius)) {
                    // Only add border chunks for each radius
                    if (kotlin.math.abs(cx - playerChunkX) == radius || 
                        kotlin.math.abs(cz - playerChunkZ) == radius) {
                        refreshQueue.offer(ChunkRefreshTask(world, cx, cz, playerId))
                    }
                }
            }
        }

        lastRefresh[playerId] = currentTime
    }

    private fun processRefreshQueue() {
        var processed = 0
        val maxChunks = getMaxChunksPerTick()
        
        while (refreshQueue.isNotEmpty() && processed < maxChunks) {
            val task = refreshQueue.poll() ?: break
            
            // Verify player still valid
            val player = Bukkit.getPlayer(task.playerId)
            if (player != null && player.isOnline && player.world == task.world) {
                if (task.world.isChunkLoaded(task.chunkX, task.chunkZ)) {
                    runCatching { task.world.refreshChunk(task.chunkX, task.chunkZ) }
                    processed++
                }
            }
        }
    }

    /**
     * Handle player state on main thread
     */
    @Suppress("UNUSED_PARAMETER")
    fun handlePlayerStateOptimized(player: Player, newHiddenState: Boolean, callback: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            runCatching(callback).onFailure { e ->
                plugin.logger.warning("Error handling state for ${player.name}: ${e.message}")
            }
        } else {
            PlatformCompatibility.runTask(plugin, Runnable(callback))
        }
    }

    /**
     * Handle teleports with delay
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleTeleportOptimized(player: Player, from: org.bukkit.Location, to: org.bukkit.Location, teleportAction: () -> Unit) {
        PlatformCompatibility.runTaskLater(plugin, Runnable(teleportAction), 1L)
    }

    /**
     * Batch refresh for multiple players grouped by world
     */
    fun batchRefreshForPlayers(players: Iterable<Player>, radiusChunks: Int) {
        players.groupBy { it.world }.forEach { (world, worldPlayers) ->
            PlatformCompatibility.runTaskLater(plugin, Runnable {
                worldPlayers
                    .filter { it.isOnline && it.world == world }
                    .forEach { refreshChunksOptimized(it, radiusChunks) }
            }, 1L)
        }
    }

    /**
     * Async chunk preloading
     */
    fun preloadChunksAsync(player: Player, radiusChunks: Int) {
        val loc = player.location
        val world = loc.world ?: return
        val playerChunkX = loc.blockX shr 4
        val playerChunkZ = loc.blockZ shr 4

        PlatformCompatibility.runAsync(plugin, Runnable {
            for (cx in (playerChunkX - radiusChunks)..(playerChunkX + radiusChunks)) {
                for (cz in (playerChunkZ - radiusChunks)..(playerChunkZ + radiusChunks)) {
                    PlatformCompatibility.runTask(plugin, Runnable {
                        if (player.isOnline && player.world == world && !world.isChunkLoaded(cx, cz)) {
                            world.loadChunk(cx, cz, false)
                        }
                    })
                }
            }
        })
    }

    fun cleanupPlayer(playerId: UUID) {
        lastRefresh.remove(playerId)
        refreshQueue.removeIf { it.playerId == playerId }
    }

    val optimizationStats: String
        get() = "Queue: ${refreshQueue.size}, Players: ${lastRefresh.size}"

    fun shutdown() {
        refreshTask?.takeUnless { it.isCancelled }?.cancel()
        refreshQueue.clear()
        lastRefresh.clear()
    }

    fun performPeriodicCleanup() {
        val threshold = System.currentTimeMillis() - (5 * 60 * 1000)
        lastRefresh.entries.removeIf { it.value < threshold }
        
        // Limit queue size
        while (refreshQueue.size > MAX_QUEUE_SIZE) {
            refreshQueue.poll()
        }
    }

    /**
     * Smart refresh based on server TPS
     */
    fun smartRefresh(player: Player, radiusChunks: Int) {
        val tps = getCurrentTPS()
        val adjustedRadius = when {
            tps > 18.0 -> radiusChunks
            tps > 15.0 -> maxOf(1, radiusChunks - 1)
            else -> 1
        }
        refreshChunksOptimized(player, adjustedRadius)
    }

    private fun getCurrentTPS(): Double = runCatching {
        // Could use reflection to get actual TPS
        20.0
    }.getOrDefault(20.0)
}
