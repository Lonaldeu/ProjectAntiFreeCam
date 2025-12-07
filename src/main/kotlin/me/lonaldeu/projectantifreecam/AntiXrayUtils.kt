/*
 * This project is a fork of TazAntixRAY
 * Original project: https://github.com/MinhTaz/TazAntixRAY
 * Original author: MinhTaz
 * Licensed under GNU General Public License v3.0
 */

package me.lonaldeu.projectantifreecam

import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player

/**
 * Utility functions for Anti-FreeCam functionality
 * Uses Kotlin's inline functions for zero-overhead abstractions
 */
object AntiXrayUtils {

    // Immutable set of entity types to hide
    private val DEFAULT_HIDDEN_ENTITIES: Set<EntityType> = setOf(
        EntityType.ARMOR_STAND,
        EntityType.ITEM_FRAME,
        EntityType.GLOW_ITEM_FRAME,
        EntityType.PAINTING,
        EntityType.MINECART
    )

    /**
     * Check if a chunk is within the limited area around a player
     */
    @JvmStatic
    fun isChunkInLimitedArea(player: Player, chunkX: Int, chunkZ: Int, chunkRadius: Int): Boolean {
        val playerLoc = player.location
        val playerChunkX = playerLoc.blockX shr 4
        val playerChunkZ = playerLoc.blockZ shr 4
        
        return kotlin.math.abs(chunkX - playerChunkX) <= chunkRadius &&
               kotlin.math.abs(chunkZ - playerChunkZ) <= chunkRadius
    }

    /**
     * Check if a block is within the limited area
     */
    @JvmStatic
    fun isBlockInLimitedArea(player: Player, blockX: Int, blockZ: Int, chunkRadius: Int): Boolean {
        val playerLoc = player.location
        val playerChunkX = playerLoc.blockX shr 4
        val playerChunkZ = playerLoc.blockZ shr 4
        val blockChunkX = blockX shr 4
        val blockChunkZ = blockZ shr 4
        
        return kotlin.math.abs(blockChunkX - playerChunkX) <= chunkRadius &&
               kotlin.math.abs(blockChunkZ - playerChunkZ) <= chunkRadius
    }

    /**
     * Check if an entity type should be hidden
     */
    @JvmStatic
    fun shouldHideEntity(entityType: EntityType): Boolean = entityType in DEFAULT_HIDDEN_ENTITIES

    /**
     * Check if entities should be hidden for a player
     */
    @JvmStatic
    fun shouldHideEntitiesForPlayer(plugin: AntiFreeCam, player: Player): Boolean {
        val hideEntities = plugin.config.getBoolean("antixray.entities.hide-entities", true)
        if (!hideEntities) return false
        return plugin.playerHiddenState[player.uniqueId] ?: false
    }

    /**
     * Check if limited area hiding is enabled
     */
    @JvmStatic
    fun isLimitedAreaEnabled(plugin: AntiFreeCam): Boolean =
        plugin.config.getBoolean("antixray.limited-area.enabled", false)

    /**
     * Get the chunk radius for limited area hiding
     */
    @JvmStatic
    fun getLimitedAreaChunkRadius(plugin: AntiFreeCam): Int =
        plugin.config.getInt("antixray.limited-area.chunk-radius", 3)

    /**
     * Check if limited area should only apply near the player
     */
    @JvmStatic
    fun shouldApplyLimitedAreaOnlyNearPlayer(plugin: AntiFreeCam): Boolean =
        plugin.config.getBoolean("antixray.limited-area.apply-only-near-player", true)

    /**
     * Calculate chunk distance
     */
    @JvmStatic
    fun getChunkDistance(chunkX1: Int, chunkZ1: Int, chunkX2: Int, chunkZ2: Int): Double {
        val deltaX = chunkX1 - chunkX2
        val deltaZ = chunkZ1 - chunkZ2
        return kotlin.math.sqrt((deltaX * deltaX + deltaZ * deltaZ).toDouble())
    }

    /**
     * Check if a block should be hidden based on all criteria
     */
    @JvmStatic
    fun shouldHideBlock(plugin: AntiFreeCam, player: Player, worldX: Int, worldY: Int, worldZ: Int): Boolean {
        val hideBelow = plugin.config.getInt("antixray.hide-below-y", 16)
        if (worldY > hideBelow) return false

        val playerShouldHide = plugin.playerHiddenState[player.uniqueId] ?: false
        if (!playerShouldHide) return false

        if (isLimitedAreaEnabled(plugin)) {
            val chunkRadius = getLimitedAreaChunkRadius(plugin)
            val applyOnlyNearPlayer = shouldApplyLimitedAreaOnlyNearPlayer(plugin)
            
            if (applyOnlyNearPlayer) {
                return isBlockInLimitedArea(player, worldX, worldZ, chunkRadius)
            }
        }

        return true
    }

    /**
     * Check if underground protection is enabled
     */
    @JvmStatic
    fun isUndergroundProtectionEnabled(plugin: AntiFreeCam): Boolean =
        plugin.config.getBoolean("antixray.underground-protection.enabled", true)

    /**
     * Simple check: hide block if player is above ground and block is underground
     */
    @JvmStatic
    fun shouldHideBlockSimple(plugin: AntiFreeCam, player: Player, worldY: Int): Boolean {
        val playerShouldHide = plugin.playerHiddenState[player.uniqueId] ?: false
        if (!playerShouldHide) return false
        if (!isUndergroundProtectionEnabled(plugin)) return false

        val hideBelow = plugin.config.getInt("antixray.hide-below-y", 16)
        return worldY <= hideBelow
    }
}
