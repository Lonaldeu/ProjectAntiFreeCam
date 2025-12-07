package me.lonaldeu.projectantifreecam

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import me.lonaldeu.projectantifreecam.cache.MassiveCache
import org.bukkit.Bukkit
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Packet listener for hiding entities in protected underground areas.
 * Hides mobs, players, and other entities below Y16 when viewer is above Y31.
 * 
 * IMPORTANT: Once entities are rendered (player went below Y31 and saw them),
 * they stay visible even when going back above Y31. This is intentional -
 * we only block NEW spawn packets, we don't destroy already-rendered entities.
 * 
 * When RAM caching is enabled:
 * - Uses MassiveCache for entity position lookups (~90% CPU reduction)
 * - Much larger cache (50k entities vs 10k)
 * - Better hit rate for large servers
 */
class EntityPacketListener(private val plugin: AntiFreeCam) : PacketListener {

    // Entity types that should be hidden in protected areas (loaded from config)
    private var hiddenEntityTypes: Set<EntityType> = emptySet()
    
    // ========== FALLBACK CACHE (used when MassiveCache is disabled) ==========
    // Cache for entity positions (entityId -> y position)
    private val entityPositionCache = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(30, TimeUnit.SECONDS)
        .build<Int, Double>()
    
    // Config values cached for performance
    @Volatile private var entityHidingEnabled = true
    @Volatile private var hideBelow = 16
    @Volatile private var whitelistedWorldsCache: Set<String> = emptySet()
    
    init {
        refreshCaches()
    }
    
    /**
     * Refresh cached config values - call on config reload
     */
    fun refreshCaches() {
        entityHidingEnabled = plugin.config.getBoolean("entities.hide-entities", true)
        hideBelow = plugin.config.getInt("antixray.hide-below-y", 16)
        whitelistedWorldsCache = plugin.config.getStringList("worlds.whitelist").toHashSet()
        
        // Load hidden entity types from config
        val configEntityTypes = plugin.config.getStringList("entities.hidden-types")
        hiddenEntityTypes = if (configEntityTypes.isNotEmpty()) {
            configEntityTypes.mapNotNull { typeName ->
                runCatching { EntityType.valueOf(typeName.uppercase()) }.getOrNull()
            }.toSet()
        } else {
            // Default entity types if not configured
            setOf(
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
        }
        
        entityPositionCache.invalidateAll()
        
        debugLog { "Entity caches refreshed: hiding=$entityHidingEnabled, hideBelow=$hideBelow, types=${hiddenEntityTypes.size}" }
    }
    
    private inline fun debugLog(message: () -> String) {
        if (plugin.isDebugMode()) {
            plugin.logger.info("[AntiFreeCam DEBUG][Entity] ${message()}")
        }
    }
    
    override fun onPacketSend(event: PacketSendEvent) {
        if (!entityHidingEnabled) return
        
        val user = event.user ?: return
        val userUUID = user.uuid ?: return
        
        val player = Bukkit.getPlayer(userUUID) ?: return
        if (!player.isOnline) return
        
        // Check if world is whitelisted
        if (player.world.name !in whitelistedWorldsCache) return
        
        // Check if player should have entities hidden
        if (!shouldHideEntitiesForPlayer(player)) return
        
        // Handle different entity packets
        when (event.packetType) {
            PacketType.Play.Server.SPAWN_ENTITY -> handleSpawnEntity(event, player)
            PacketType.Play.Server.SPAWN_PLAYER -> handleSpawnPlayer(event, player)
            PacketType.Play.Server.ENTITY_METADATA -> handleEntityMetadata(event, player)
            PacketType.Play.Server.ENTITY_TELEPORT -> handleEntityTeleport(event, player)
            else -> {}
        }
    }
    
    /**
     * Check if entities should be hidden for this player based on their state
     */
    private fun shouldHideEntitiesForPlayer(player: Player): Boolean {
        return plugin.playerHiddenState[player.uniqueId] ?: false
    }
    
    // Cache entity ID -> entity type for later METADATA/TELEPORT packets
    private val entityTypeCache = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build<Int, EntityType>()
    
    /**
     * Handle SPAWN_ENTITY packets - cancel if entity is in hidden area
     */
    private fun handleSpawnEntity(event: PacketSendEvent, player: Player) {
        runCatching {
            val wrapper = WrapperPlayServerSpawnEntity(event)
            val position = wrapper.position
            val entityY = position.y
            val entityId = wrapper.entityId
            
            // Cache the entity position for later packets
            if (MassiveCache.ramCachingEnabled) {
                MassiveCache.cacheEntityPosition(entityId, entityY)
            } else {
                entityPositionCache.put(entityId, entityY)
            }
            
            // Try to determine entity type
            val entityType = getEntityTypeFromWrapper(wrapper)
            
            // Cache the entity type for METADATA/TELEPORT packets
            if (entityType != null) {
                entityTypeCache.put(entityId, entityType)
            }
            
            // Check if entity is in protected area AND is a hidden type (including players)
            if (shouldHideAtY(entityY)) {
                if (entityType != null && shouldHideEntityType(entityType)) {
                    debugLog { "SPAWN_ENTITY: Hiding ${entityType.name} (ID=$entityId) at Y=$entityY for ${player.name}" }
                    event.isCancelled = true
                } else {
                    debugLog { "SPAWN_ENTITY: NOT hiding ${entityType?.name ?: "UNKNOWN"} (ID=$entityId) at Y=$entityY - not in hidden types" }
                }
            }
        }.onFailure { e ->
            debugLog { "Error handling SPAWN_ENTITY: ${e.message}" }
        }
    }
    
    /**
     * Handle SPAWN_PLAYER packets - cancel if player is in hidden area
     */
    private fun handleSpawnPlayer(event: PacketSendEvent, player: Player) {
        runCatching {
            val wrapper = WrapperPlayServerSpawnPlayer(event)
            val position = wrapper.position
            val entityY = position.y
            val entityId = wrapper.entityId
            
            // Cache the player position for later packets
            if (MassiveCache.ramCachingEnabled) {
                MassiveCache.cacheEntityPosition(entityId, entityY)
            } else {
                entityPositionCache.put(entityId, entityY)
            }
            
            // Cache entity type as PLAYER
            entityTypeCache.put(entityId, EntityType.PLAYER)
            
            // Check if player is in protected area (below Y16)
            if (shouldHideAtY(entityY)) {
                debugLog { "SPAWN_PLAYER: Hiding PLAYER (ID=$entityId) at Y=$entityY for ${player.name}" }
                event.isCancelled = true
            } else {
                debugLog { "SPAWN_PLAYER: NOT hiding PLAYER (ID=$entityId) at Y=$entityY - above hideBelow" }
            }
        }.onFailure { e ->
            debugLog { "Error handling SPAWN_PLAYER: ${e.message}" }
        }
    }
    
    /**
     * Handle ENTITY_METADATA packets
     */
    private fun handleEntityMetadata(event: PacketSendEvent, player: Player) {
        runCatching {
            val wrapper = WrapperPlayServerEntityMetadata(event)
            val entityId = wrapper.entityId
            
            // Check if we know this entity type and if it should be hidden (including players)
            val entityType = entityTypeCache.getIfPresent(entityId)
            if (entityType == null || !shouldHideEntityType(entityType)) {
                return // Don't hide unknown entities or non-hidden types
            }
            
            // Check cached position (use MassiveCache if enabled)
            val entityY = if (MassiveCache.ramCachingEnabled) {
                MassiveCache.getEntityPosition(entityId)
            } else {
                entityPositionCache.getIfPresent(entityId)
            } ?: return
            
            if (shouldHideAtY(entityY)) {
                debugLog { "ENTITY_METADATA: Hiding ${entityType.name} (ID=$entityId) at Y=$entityY for ${player.name}" }
                event.isCancelled = true
            }
        }.onFailure { e ->
            debugLog { "Error handling ENTITY_METADATA: ${e.message}" }
        }
    }
    
    /**
     * Handle ENTITY_TELEPORT packets - update cache and hide if needed
     */
    private fun handleEntityTeleport(event: PacketSendEvent, player: Player) {
        runCatching {
            val wrapper = WrapperPlayServerEntityTeleport(event)
            val entityId = wrapper.entityId
            val position = wrapper.position
            val entityY = position.y
            
            // Update cached position
            if (MassiveCache.ramCachingEnabled) {
                MassiveCache.cacheEntityPosition(entityId, entityY)
            } else {
                entityPositionCache.put(entityId, entityY)
            }
            
            // Check if we know this entity type and if it should be hidden (including players)
            val entityType = entityTypeCache.getIfPresent(entityId)
            if (entityType == null || !shouldHideEntityType(entityType)) {
                return // Don't hide unknown entities or non-hidden types
            }
            
            if (shouldHideAtY(entityY)) {
                debugLog { "ENTITY_TELEPORT: Hiding ${entityType.name} (ID=$entityId) at Y=$entityY for ${player.name}" }
                event.isCancelled = true
            }
        }.onFailure { e ->
            debugLog { "Error handling ENTITY_TELEPORT: ${e.message}" }
        }
    }
    
    /**
     * Check if an entity at the given Y level should be hidden
     */
    private fun shouldHideAtY(entityY: Double): Boolean {
        return entityY <= hideBelow
    }
    
    /**
     * Try to determine entity type from spawn packet
     */
    private fun getEntityTypeFromWrapper(wrapper: WrapperPlayServerSpawnEntity): EntityType? {
        return runCatching {
            // PacketEvents provides entity type info
            val peEntityType = wrapper.entityType
            
            // Map PacketEvents entity type to Bukkit EntityType
            // PacketEvents uses names like "minecraft:zombie" or just the type name
            val typeName = peEntityType.name.toString()
                .removePrefix("minecraft:")
                .uppercase()
                .replace(" ", "_")
            
            debugLog { "Entity type mapping: PE='${peEntityType.name}' -> Bukkit='$typeName'" }
            
            EntityType.entries.find { it.name == typeName }
        }.getOrNull()
    }
    
    /**
     * Check if a specific entity type should be hidden (includes PLAYER now)
     */
    fun shouldHideEntityType(entityType: EntityType): Boolean {
        // Always hide configured entity types
        if (entityType in hiddenEntityTypes) return true
        // Also hide players
        if (entityType == EntityType.PLAYER) return true
        return false
    }
    
    /**
     * Clean up entity cache for a player
     */
    fun cleanupPlayer(uuid: UUID) {
        // Clean MassiveCache player data if enabled
        if (MassiveCache.ramCachingEnabled) {
            MassiveCache.cleanupPlayer(uuid)
        }
        debugLog { "Player cleanup: $uuid" }
    }
    
    /**
     * Clear all cached entity positions (called on world change, etc.)
     */
    fun clearEntityCache() {
        entityPositionCache.invalidateAll()
        entityTypeCache.invalidateAll()
        if (MassiveCache.ramCachingEnabled) {
            MassiveCache.clearEntityPositions()
        }
    }
    
    override fun onPacketReceive(event: PacketReceiveEvent) {
        // Required by interface - no-op
    }
}
