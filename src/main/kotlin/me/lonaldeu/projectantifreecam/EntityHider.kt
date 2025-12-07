/*
 * This project is a fork of TazAntixRAY
 * Original project: https://github.com/MinhTaz/TazAntixRAY
 * Original author: MinhTaz
 * Licensed under GNU General Public License v3.0
 */

package me.lonaldeu.projectantifreecam

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntitySpawnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles hiding entities in underground areas using Bukkit's native API.
 * This provides a second layer of protection alongside packet-level entity hiding.
 * Uses player.hideEntity() for reliable entity visibility control.
 */
class EntityHider(private val plugin: AntiFreeCam) : Listener {

    // Track hidden entities per player: Set of "playerUUID:entityUUID" tracking IDs
    private val hiddenEntitiesForPlayer = ConcurrentHashMap.newKeySet<UUID>()
    
    // Config values
    @Volatile private var entityHidingEnabled = true
    @Volatile private var hideBelow = 16
    @Volatile private var protectionLevel = 31.0
    
    // Default entity types to hide
    private var hiddenEntityTypes: Set<EntityType> = setOf(
        EntityType.ARMOR_STAND,
        EntityType.ITEM_FRAME,
        EntityType.GLOW_ITEM_FRAME,
        EntityType.PAINTING,
        EntityType.MINECART,
        EntityType.CHEST_MINECART,
        EntityType.HOPPER_MINECART,
        EntityType.TNT_MINECART,
        EntityType.FURNACE_MINECART,
        EntityType.SPAWNER_MINECART
    )
    
    init {
        loadSettings()
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }
    
    /**
     * Load settings from config
     */
    fun loadSettings() {
        entityHidingEnabled = plugin.config.getBoolean("entities.hide-entities", true)
        hideBelow = plugin.config.getInt("antixray.hide-below-y", 16)
        protectionLevel = plugin.config.getDouble("antixray.protection-y-level", 31.0)
        
        // Load hidden entity types from config
        val configEntityTypes = plugin.config.getStringList("entities.hidden-types")
        if (configEntityTypes.isNotEmpty()) {
            hiddenEntityTypes = configEntityTypes.mapNotNull { typeName ->
                runCatching { EntityType.valueOf(typeName.uppercase()) }.getOrNull()
            }.toSet()
        }
        
        debugLog { "EntityHider settings loaded: enabled=$entityHidingEnabled, hideBelow=$hideBelow, types=${hiddenEntityTypes.size}" }
    }
    
    private inline fun debugLog(message: () -> String) {
        if (plugin.isDebugMode()) {
            plugin.logger.info("[AntiFreeCam DEBUG][EntityHider] ${message()}")
        }
    }
    
    /**
     * Check if entity hiding is enabled
     */
    fun isEntityHidingEnabled(): Boolean = entityHidingEnabled
    
    /**
     * Check if entities should be hidden for a player at their current location
     */
    fun shouldHideEntities(player: Player): Boolean {
        if (!entityHidingEnabled) return false
        
        val playerY = player.location.y
        // Hide entities when player is above protection level
        return playerY > protectionLevel
    }
    
    /**
     * Check if a specific entity should be hidden from a player
     */
    fun shouldHideEntity(player: Player, entity: Entity): Boolean {
        if (!shouldHideEntities(player)) return false
        
        // Check entity type
        if (entity.type !in hiddenEntityTypes) return false
        
        val entityY = entity.location.y
        // Hide entities that are below the hide-below-y level
        return entityY <= hideBelow
    }
    
    /**
     * Hide entities for a player in their view area
     */
    fun hideEntitiesForPlayer(player: Player) {
        if (!shouldHideEntities(player)) return
        
        // Run on player's region for getting nearby entities
        PlatformCompatibility.runEntityTask(plugin, player, {
            val viewDistance = getViewDistance(player)
            val nearbyEntities = getNearbyEntities(player, viewDistance)
            
            for (entity in nearbyEntities) {
                if (shouldHideEntity(player, entity)) {
                    // Run hide on entity's region (Folia requirement)
                    hideEntityFromPlayerSafe(player, entity)
                }
            }
        })
    }
    
    /**
     * Show all hidden entities for a player
     */
    fun showAllEntitiesForPlayer(player: Player) {
        // Run on player's region for getting nearby entities
        PlatformCompatibility.runEntityTask(plugin, player, {
            val viewDistance = getViewDistance(player)
            val nearbyEntities = getNearbyEntities(player, viewDistance)
            
            for (entity in nearbyEntities) {
                // Run show on entity's region (Folia requirement)
                showEntityToPlayerSafe(player, entity)
            }
        })
    }
    
    /**
     * Update entity visibility for a player based on their current state
     */
    fun updateEntityVisibility(player: Player) {
        if (!entityHidingEnabled) return
        
        if (shouldHideEntities(player)) {
            hideEntitiesForPlayer(player)
        } else {
            showAllEntitiesForPlayer(player)
        }
    }
    
    /**
     * Hide a specific entity from a player using Bukkit API
     * Must be called from entity's owning region thread on Folia
     */
    private fun hideEntityFromPlayer(player: Player, entity: Entity) {
        runCatching {
            val trackingId = createTrackingId(player.uniqueId, entity.uniqueId)
            
            if (trackingId !in hiddenEntitiesForPlayer) {
                // Use Bukkit's built-in method to hide entity
                player.hideEntity(plugin, entity)
                hiddenEntitiesForPlayer.add(trackingId)
                
                debugLog { "Hidden ${entity.type} at Y=${entity.location.y.format(1)} from ${player.name}" }
            }
        }.onFailure { e ->
            plugin.logger.warning("Failed to hide entity ${entity.type} from ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Thread-safe wrapper to hide entity - schedules on entity's region for Folia
     */
    private fun hideEntityFromPlayerSafe(player: Player, entity: Entity) {
        if (PlatformCompatibility.isFolia()) {
            // On Folia, must run on entity's owning region
            PlatformCompatibility.runEntityTask(plugin, entity, {
                hideEntityFromPlayer(player, entity)
            })
        } else {
            // On Paper/Spigot, can run directly
            hideEntityFromPlayer(player, entity)
        }
    }
    
    /**
     * Show a specific entity to a player using Bukkit API
     * Must be called from entity's owning region thread on Folia
     */
    private fun showEntityToPlayer(player: Player, entity: Entity) {
        runCatching {
            val trackingId = createTrackingId(player.uniqueId, entity.uniqueId)
            
            if (trackingId in hiddenEntitiesForPlayer) {
                // Use Bukkit's built-in method to show entity
                player.showEntity(plugin, entity)
                hiddenEntitiesForPlayer.remove(trackingId)
                
                debugLog { "Showed ${entity.type} at Y=${entity.location.y.format(1)} to ${player.name}" }
            }
        }.onFailure { e ->
            plugin.logger.warning("Failed to show entity ${entity.type} to ${player.name}: ${e.message}")
        }
    }
    
    /**
     * Thread-safe wrapper to show entity - schedules on entity's region for Folia
     */
    private fun showEntityToPlayerSafe(player: Player, entity: Entity) {
        if (PlatformCompatibility.isFolia()) {
            // On Folia, must run on entity's owning region
            PlatformCompatibility.runEntityTask(plugin, entity, {
                showEntityToPlayer(player, entity)
            })
        } else {
            // On Paper/Spigot, can run directly
            showEntityToPlayer(player, entity)
        }
    }
    
    /**
     * Create a tracking ID for player-entity pair
     */
    private fun createTrackingId(playerId: UUID, entityId: UUID): UUID {
        val key = "$playerId:$entityId"
        return UUID.nameUUIDFromBytes(key.toByteArray())
    }
    
    /**
     * Get nearby entities for a player
     */
    private fun getNearbyEntities(player: Player, viewDistance: Int): List<Entity> {
        val chunkRadius = viewDistance.coerceAtMost(10) // Cap at 10 chunks for performance
        val blockRadius = chunkRadius * 16.0
        
        return runCatching {
            player.location.world?.getNearbyEntities(player.location, blockRadius, 256.0, blockRadius)
                ?.filter { it !is Player && it.type in hiddenEntityTypes }
                ?: emptyList()
        }.getOrElse { emptyList() }
    }
    
    /**
     * Get view distance for a player
     */
    private fun getViewDistance(player: Player): Int {
        return runCatching {
            player.clientViewDistance
        }.getOrElse {
            Bukkit.getViewDistance()
        }
    }
    
    /**
     * Handle player movement - update entity visibility if needed
     */
    fun handlePlayerMovement(player: Player, from: Location, to: Location) {
        if (!entityHidingEnabled) return
        
        val fromY = from.y
        val toY = to.y
        
        // Check if player crossed the protection threshold
        val wasAboveProtection = fromY > protectionLevel
        val isAboveProtection = toY > protectionLevel
        
        if (wasAboveProtection != isAboveProtection) {
            // Player crossed threshold, update entity visibility
            updateEntityVisibility(player)
        }
    }
    
    /**
     * Handle entity spawn events to immediately hide entities if needed
     */
    @EventHandler
    fun onEntitySpawn(event: EntitySpawnEvent) {
        if (!entityHidingEnabled) return
        
        val entity = event.entity
        val entityLoc = entity.location
        
        // Only process entities below hide level and of hideable type
        if (entityLoc.y > hideBelow) return
        if (entity.type !in hiddenEntityTypes) return
        
        // Hide entity from all players who should not see it
        // Use entity task to ensure we're on the correct region thread for Folia
        PlatformCompatibility.runEntityTaskLater(plugin, entity, {
            for (player in Bukkit.getOnlinePlayers()) {
                if (shouldHideEntity(player, entity)) {
                    // Already on entity's region, safe to call directly
                    hideEntityFromPlayer(player, entity)
                }
            }
        }, null, 1L) // Small delay to ensure entity is fully spawned
    }
    
    /**
     * Clean up hidden entities for a player when they leave
     */
    fun cleanupPlayer(playerId: UUID) {
        hiddenEntitiesForPlayer.removeIf { trackingId ->
            // Remove all tracking entries that start with this player's UUID
            // This is a simple cleanup - we regenerate tracking IDs on next interaction
            true // For now, just clear all - proper tracking would need more complex structure
        }
        debugLog { "Cleaned up entity tracking for player $playerId" }
    }
    
    /**
     * Get statistics about hidden entities
     */
    fun getStatistics(): String = "Hidden entity mappings: ${hiddenEntitiesForPlayer.size}"
    
    /**
     * Check if entity hiding is enabled in config
     */
    fun isConfigEnabled(): Boolean {
        return plugin.config.getBoolean("entities.hide-entities", true) &&
               plugin.config.getBoolean("performance.underground-protection.enabled", true)
    }
    
    /**
     * Refresh entity visibility for all online players
     */
    fun refreshAllPlayers() {
        for (player in Bukkit.getOnlinePlayers()) {
            updateEntityVisibility(player)
        }
    }
    
    /**
     * Periodic cleanup of stale data
     */
    fun performPeriodicCleanup() {
        val onlinePlayerIds = Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
        
        // This is a simplified cleanup - in production you'd want more sophisticated tracking
        if (onlinePlayerIds.isEmpty()) {
            hiddenEntitiesForPlayer.clear()
        }
    }
    
    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
