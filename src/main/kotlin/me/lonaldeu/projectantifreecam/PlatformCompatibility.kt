package me.lonaldeu.projectantifreecam

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * Platform compatibility layer for Folia/Paper/Spigot
 * Uses direct API calls when available, with runtime detection
 */
object PlatformCompatibility {

    // ========== Version Info ==========
    
    /**
     * Parsed Minecraft version info
     */
    data class MinecraftVersion(
        val major: Int,
        val minor: Int,
        val patch: Int
    ) : Comparable<MinecraftVersion> {
        override fun compareTo(other: MinecraftVersion): Int {
            val majorCmp = major.compareTo(other.major)
            if (majorCmp != 0) return majorCmp
            val minorCmp = minor.compareTo(other.minor)
            if (minorCmp != 0) return minorCmp
            return patch.compareTo(other.patch)
        }
        
        override fun toString(): String = "$major.$minor.$patch"
        
        fun isAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean {
            return this >= MinecraftVersion(major, minor, patch)
        }
    }
    
    // Common version constants
    val VERSION_1_21_4 = MinecraftVersion(1, 21, 4)
    val VERSION_1_21_5 = MinecraftVersion(1, 21, 5)

    // Lazy detection with thread-safe initialization
    private val isFoliaServer: Boolean by lazy {
        runCatching {
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler")
            true
        }.getOrDefault(false)
    }
    
    private val isPaperServer: Boolean by lazy {
        runCatching {
            Class.forName("io.papermc.paper.configuration.Configuration")
            true
        }.getOrDefault(false)
    }
    
    /**
     * Detect if Moonrise chunk system is available (Paper 1.21.5+)
     */
    private val hasMoonriseApi: Boolean by lazy {
        runCatching {
            // Check for Moonrise's PlayerChunkLoader class
            Class.forName("ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader")
            true
        }.getOrDefault(false)
    }
    
    /**
     * Parse Minecraft version from Bukkit.getBukkitVersion()
     * Format: "1.21.4-R0.1-SNAPSHOT" or "1.21.5-R0.1-SNAPSHOT"
     */
    private val mcVersion: MinecraftVersion by lazy {
        runCatching {
            val versionString = Bukkit.getBukkitVersion()
            // Parse "1.21.4-R0.1-SNAPSHOT" -> "1.21.4"
            val versionPart = versionString.split("-").first()
            val parts = versionPart.split(".")
            MinecraftVersion(
                major = parts.getOrNull(0)?.toIntOrNull() ?: 1,
                minor = parts.getOrNull(1)?.toIntOrNull() ?: 21,
                patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            )
        }.getOrElse { MinecraftVersion(1, 21, 0) }
    }

    @JvmStatic
    fun isFolia(): Boolean = isFoliaServer
    
    @JvmStatic
    fun isPaper(): Boolean = isPaperServer || isFoliaServer

    @JvmStatic
    fun hasGlobalRegionScheduler(): Boolean = isFoliaServer

    @JvmStatic
    fun hasRegionScheduler(): Boolean = isFoliaServer
    
    // ========== Version Checks ==========
    
    /**
     * Get the current Minecraft version
     */
    @JvmStatic
    fun getMinecraftVersion(): MinecraftVersion = mcVersion
    
    /**
     * Check if Moonrise chunk system is available (Paper 1.21.5+)
     */
    @JvmStatic
    fun hasMoonrise(): Boolean = hasMoonriseApi
    
    /**
     * Check if running on Minecraft 1.21.5 or later
     */
    @JvmStatic
    fun isMinecraft1215OrLater(): Boolean = mcVersion >= VERSION_1_21_5
    
    /**
     * Check if running on Minecraft 1.21.4 exactly
     */
    @JvmStatic
    fun isMinecraft1214(): Boolean = mcVersion == VERSION_1_21_4
    
    /**
     * Check if running on at least the specified version
     */
    @JvmStatic
    fun isMinecraftAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean {
        return mcVersion.isAtLeast(major, minor, patch)
    }

    // ========== Global Operations ==========

    /**
     * Run a task on the global/main thread immediately
     */
    @JvmStatic
    fun runTask(plugin: Plugin, task: Runnable): TaskHandle {
        return if (isFoliaServer) {
            val scheduled = Bukkit.getGlobalRegionScheduler().run(plugin) { task.run() }
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
            TaskHandle.Paper(bukkitTask)
        }
    }

    /**
     * Run a task on the global/main thread with delay
     */
    @JvmStatic
    fun runTaskLater(plugin: Plugin, task: Runnable, delayTicks: Long): TaskHandle {
        return if (isFoliaServer) {
            val scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task.run() }, delayTicks)
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks)
            TaskHandle.Paper(bukkitTask)
        }
    }

    /**
     * Run a repeating task on the global/main thread
     */
    @JvmStatic
    fun runTaskTimer(plugin: Plugin, task: Runnable, initialDelay: Long, period: Long): TaskHandle {
        return if (isFoliaServer) {
            val safeInitialDelay = if (initialDelay <= 0L) 1L else initialDelay
            val scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task.run() }, safeInitialDelay, period)
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, period)
            TaskHandle.Paper(bukkitTask)
        }
    }

    // ========== Region-Specific Operations ==========

    /**
     * Run a task at a specific location (region-aware for Folia)
     */
    @JvmStatic
    fun runTask(plugin: Plugin, location: Location, task: Runnable): TaskHandle {
        return if (isFoliaServer) {
            val world = location.world ?: return runTask(plugin, task)
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            val scheduled = Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ) { task.run() }
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
            TaskHandle.Paper(bukkitTask)
        }
    }

    /**
     * Run a task at a specific location with delay
     */
    @JvmStatic
    fun runTaskLater(plugin: Plugin, location: Location, task: Runnable, delayTicks: Long): TaskHandle {
        return if (isFoliaServer) {
            val world = location.world ?: return runTaskLater(plugin, task, delayTicks)
            val chunkX = location.blockX shr 4
            val chunkZ = location.blockZ shr 4
            val scheduled = Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, { task.run() }, delayTicks)
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks)
            TaskHandle.Paper(bukkitTask)
        }
    }

    /**
     * Run a task at a specific block location
     */
    @JvmStatic
    fun runAtBlock(plugin: Plugin, world: World, x: Int, z: Int, task: Runnable): TaskHandle {
        return if (isFoliaServer) {
            val chunkX = x shr 4
            val chunkZ = z shr 4
            val scheduled = Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ) { task.run() }
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
            TaskHandle.Paper(bukkitTask)
        }
    }

    // ========== Entity Operations ==========

    /**
     * Run a task on an entity's owning thread
     */
    @JvmStatic
    fun runEntityTask(plugin: Plugin, entity: Entity, task: Runnable, retired: Runnable? = null): TaskHandle {
        return if (isFoliaServer) {
            val scheduled = entity.scheduler.run(plugin, { task.run() }, retired)
            if (scheduled != null) TaskHandle.Folia(scheduled) else TaskHandle.Noop
        } else {
            val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
            TaskHandle.Paper(bukkitTask)
        }
    }

    /**
     * Run a task on an entity's owning thread with delay
     */
    @JvmStatic
    fun runEntityTaskLater(plugin: Plugin, entity: Entity, task: Runnable, retired: Runnable?, delayTicks: Long): TaskHandle {
        return if (isFoliaServer) {
            val scheduled = entity.scheduler.runDelayed(plugin, { task.run() }, retired, delayTicks)
            if (scheduled != null) TaskHandle.Folia(scheduled) else TaskHandle.Noop
        } else {
            val bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks)
            TaskHandle.Paper(bukkitTask)
        }
    }

    // ========== Async Operations ==========

    /**
     * Run task asynchronously off the main thread
     */
    @JvmStatic
    fun runAsync(plugin: Plugin, task: Runnable): TaskHandle {
        return if (isFoliaServer) {
            val scheduled = Bukkit.getAsyncScheduler().runNow(plugin) { task.run() }
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task)
            TaskHandle.Paper(bukkitTask)
        }
    }

    /**
     * Run task asynchronously with delay
     */
    @JvmStatic
    fun runAsyncLater(plugin: Plugin, task: Runnable, delayTicks: Long): TaskHandle {
        return if (isFoliaServer) {
            val scheduled = Bukkit.getAsyncScheduler().runDelayed(plugin, { task.run() }, delayTicks * 50, java.util.concurrent.TimeUnit.MILLISECONDS)
            TaskHandle.Folia(scheduled)
        } else {
            val bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks)
            TaskHandle.Paper(bukkitTask)
        }
    }

    // ========== Region Ownership Checks ==========

    /**
     * Check if current thread owns the region for the given location
     */
    @JvmStatic
    fun isOwnedByCurrentRegion(location: Location): Boolean {
        return if (isFoliaServer) {
            Bukkit.isOwnedByCurrentRegion(location)
        } else {
            true // Always true on Paper/Spigot (single-threaded)
        }
    }

    /**
     * Check if current thread owns the entity
     */
    @JvmStatic
    fun isOwnedByCurrentRegion(entity: Entity): Boolean {
        return if (isFoliaServer) {
            Bukkit.isOwnedByCurrentRegion(entity)
        } else {
            true // Always true on Paper/Spigot (single-threaded)
        }
    }

    // ========== Teleportation ==========

    /**
     * Teleport an entity safely (uses teleportAsync on Folia, teleport on Paper)
     * Returns a CompletableFuture that completes when teleportation finishes
     */
    @JvmStatic
    fun teleportAsync(entity: Entity, location: Location): java.util.concurrent.CompletableFuture<Boolean> {
        return if (isFoliaServer) {
            // Folia requires teleportAsync
            entity.teleportAsync(location)
        } else {
            // Paper/Spigot can use synchronous teleport, wrap in completed future
            val result = entity.teleport(location)
            java.util.concurrent.CompletableFuture.completedFuture(result)
        }
    }

    /**
     * Teleport an entity safely with callback (uses teleportAsync on Folia, teleport on Paper)
     */
    @JvmStatic
    fun teleportAsync(entity: Entity, location: Location, callback: (Boolean) -> Unit) {
        teleportAsync(entity, location).thenAccept(callback)
    }

    // ========== Utility ==========

    /**
     * Get platform info for debugging
     */
    @JvmStatic
    fun getPlatformInfo(): String = buildString {
        append("Platform: ${if (isFoliaServer) "Folia" else "Paper/Spigot"}")
        append(", Server: ${Bukkit.getVersion()}")
        append(", MC: $mcVersion")
        append(", Moonrise: ${if (hasMoonriseApi) "Yes" else "No"}")
    }

    // ========== Task Handle ==========

    /**
     * Unified task handle for both Folia and Paper
     */
    sealed interface TaskHandle {
        fun cancel()
        fun isCancelled(): Boolean

        class Paper(private val handle: BukkitTask) : TaskHandle {
            override fun cancel() { handle.cancel() }
            override fun isCancelled(): Boolean = handle.isCancelled
        }

        class Folia(private val handle: io.papermc.paper.threadedregions.scheduler.ScheduledTask) : TaskHandle {
            override fun cancel() { handle.cancel() }
            override fun isCancelled(): Boolean = handle.isCancelled
        }

        object Noop : TaskHandle {
            override fun cancel() {}
            override fun isCancelled(): Boolean = true
        }
    }
}
