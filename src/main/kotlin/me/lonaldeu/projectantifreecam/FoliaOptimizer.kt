package me.lonaldeu.projectantifreecam

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Folia-specific optimizations for region-based threading
 * Uses Kotlin sequences for lazy evaluation and coroutine-friendly design
 */
class FoliaOptimizer(private val plugin: AntiFreeCam) {

    private val regionQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ChunkRefreshTask>>()
    private val lastRegionSwitch = ConcurrentHashMap<UUID, Long>()
    
    companion object {
        private const val REGION_SWITCH_COOLDOWN = 100L // ms
        
        @JvmStatic
        fun shouldUseFoliaOptimizations(): Boolean = 
            PlatformCompatibility.isFolia() && 
            PlatformCompatibility.hasRegionScheduler() && 
            PlatformCompatibility.hasGlobalRegionScheduler()
    }
    
    // Get max batch size from config
    private fun getMaxBatchSize(): Int = plugin.getMaxChunksPerTick()

    /**
     * Data class for chunk refresh tasks - uses value semantics for efficiency
     */
    private data class ChunkRefreshTask(
        val world: World,
        val chunkX: Int,
        val chunkZ: Int,
        val location: Location
    )

    /**
     * Optimized chunk refresh using Kotlin sequences for lazy evaluation
     */
    fun refreshChunksOptimized(player: Player, radiusChunks: Int) {
        val loc = player.location
        val world = loc.world ?: return
        val playerId = player.uniqueId

        // Check cooldown
        val currentTime = System.currentTimeMillis()
        lastRegionSwitch[playerId]?.let { lastSwitch ->
            if (currentTime - lastSwitch < REGION_SWITCH_COOLDOWN) return
        }

        val playerChunkX = loc.blockX shr 4
        val playerChunkZ = loc.blockZ shr 4

        // Use sequence for lazy evaluation - only compute what's needed
        val regionTasks = generateChunkSequence(playerChunkX, playerChunkZ, radiusChunks)
            .map { (cx, cz) ->
                val chunkLoc = Location(world, (cx * 16).toDouble(), loc.y, (cz * 16).toDouble())
                getRegionKey(chunkLoc) to ChunkRefreshTask(world, cx, cz, chunkLoc)
            }
            .groupByTo(ConcurrentHashMap()) { it.first }
            .mapValues { (_, pairs) -> 
                ConcurrentLinkedQueue(pairs.map { it.second })
            }

        // Execute grouped by region
        regionTasks.forEach { (_, tasks) ->
            tasks.firstOrNull()?.let { first ->
                scheduleRegionTasks(first.location, tasks)
            }
        }

        lastRegionSwitch[playerId] = currentTime
    }

    /**
     * Generate chunk coordinates as a sequence (lazy)
     */
    private fun generateChunkSequence(centerX: Int, centerZ: Int, radius: Int) = sequence {
        for (cx in (centerX - radius)..(centerX + radius)) {
            for (cz in (centerZ - radius)..(centerZ + radius)) {
                yield(cx to cz)
            }
        }
    }

    /**
     * Schedule tasks for a specific region
     */
    private fun scheduleRegionTasks(regionLocation: Location, tasks: ConcurrentLinkedQueue<ChunkRefreshTask>) {
        PlatformCompatibility.runTask(plugin, regionLocation) {
            var processed = 0
            val maxBatch = getMaxBatchSize()
            
            while (tasks.isNotEmpty() && processed < maxBatch) {
                val task = tasks.poll() ?: break
                
                runCatching {
                    if (PlatformCompatibility.isOwnedByCurrentRegion(task.location)) {
                        task.world.refreshChunk(task.chunkX, task.chunkZ)
                        processed++
                    } else {
                        // Re-queue if region ownership changed
                        tasks.offer(task)
                        return@runTask
                    }
                }.onFailure { e ->
                    plugin.logger.warning("Chunk refresh failed at ${task.chunkX},${task.chunkZ}: ${e.message}")
                }
            }

            // Schedule remaining tasks for next tick
            if (tasks.isNotEmpty()) {
                PlatformCompatibility.runTaskLater(plugin, Runnable {
                    scheduleRegionTasks(regionLocation, tasks)
                }, 1L)
            }
        }
    }

    /**
     * Get region key for grouping (Folia uses 32x32 chunk regions)
     */
    private fun getRegionKey(location: Location): String {
        val regionX = location.blockX shr 9  // Divide by 512
        val regionZ = location.blockZ shr 9
        return "${location.world?.name}:$regionX:$regionZ"
    }

    /**
     * Handle player state with proper region scheduling
     */
    @Suppress("UNUSED_PARAMETER")
    fun handlePlayerStateOptimized(player: Player, newHiddenState: Boolean, callback: () -> Unit) {
        val playerLoc = player.location
        
        if (!PlatformCompatibility.isOwnedByCurrentRegion(playerLoc)) {
            PlatformCompatibility.runTask(plugin, playerLoc) {
                handlePlayerStateOptimized(player, newHiddenState, callback)
            }
            return
        }

        runCatching(callback).onFailure { e ->
            plugin.logger.warning("Error handling player state for ${player.name}: ${e.message}")
        }
    }

    /**
     * Handle teleports that may cross regions
     */
    @Suppress("UNUSED_PARAMETER")
    fun handleTeleportOptimized(player: Player, from: Location, to: Location, teleportAction: () -> Unit) {
        val sameRegion = getRegionKey(from) == getRegionKey(to)

        if (sameRegion) {
            if (PlatformCompatibility.isOwnedByCurrentRegion(to)) {
                teleportAction()
            } else {
                PlatformCompatibility.runTask(plugin, to, Runnable(teleportAction))
            }
        } else {
            // Cross-region teleport needs delay
            PlatformCompatibility.runTaskLater(plugin, Runnable {
                PlatformCompatibility.runTask(plugin, to, Runnable(teleportAction))
            }, 1L)
        }
    }

    fun cleanupPlayer(playerId: UUID) {
        lastRegionSwitch.remove(playerId)
    }

    val optimizationStats: String
        get() = "Regions: ${regionQueues.size}, Players: ${lastRegionSwitch.size}"

    fun performPeriodicCleanup() {
        val threshold = System.currentTimeMillis() - (5 * 60 * 1000)
        lastRegionSwitch.entries.removeIf { it.value < threshold }
        regionQueues.entries.removeIf { it.value.isEmpty() }
    }
}
