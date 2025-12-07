package me.lonaldeu.projectantifreecam

import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Message formatter with Kotlin string templates and inline functions
 * for zero-overhead color formatting
 */
object MessageFormatter {

    // Color constants
    private const val PRIMARY = "§3"      // Dark Aqua
    private const val SECONDARY = "§b"    // Aqua
    private const val ACCENT = "§e"       // Yellow
    private const val SUCCESS = "§a"      // Green
    private const val WARNING = "§6"      // Gold
    private const val ERROR = "§c"        // Red
    private const val INFO = "§7"         // Gray
    private const val RESET = "§r"        // Reset
    private const val BOLD = "§l"         // Bold

    private const val PLUGIN_NAME = "${PRIMARY}${BOLD}AntiFreeCam${RESET}"
    private const val PREFIX = "[${PLUGIN_NAME}${PRIMARY}]${RESET} "
    private const val CONSOLE_PREFIX = "[AntiFreeCam] "

    // Lazy serializers
    private val legacySerializer by lazy { LegacyComponentSerializer.legacyAmpersand() }
    private val sectionSerializer by lazy { LegacyComponentSerializer.legacySection() }
    private val plainSerializer by lazy { PlainTextComponentSerializer.plainText() }

    @JvmStatic
    fun format(message: String): String {
        val component = legacySerializer.deserialize(message)
        return sectionSerializer.serialize(component)
    }

    @JvmStatic
    fun send(sender: CommandSender, message: String) {
        if (sender is ConsoleCommandSender) {
            sender.sendMessage(CONSOLE_PREFIX + stripColorCodes(message))
        } else {
            sender.sendMessage(PREFIX + translateColorCodes(message))
        }
    }

    @JvmStatic
    fun sendInfo(sender: CommandSender, message: String) = send(sender, "$INFO$message")

    @JvmStatic
    fun sendSuccess(sender: CommandSender, message: String) = send(sender, "$SUCCESS$message")

    @JvmStatic
    fun sendWarning(sender: CommandSender, message: String) = send(sender, "$WARNING$message")

    @JvmStatic
    fun sendError(sender: CommandSender, message: String) = send(sender, "$ERROR$message")

    @JvmStatic
    fun createStartupBanner(version: String, platform: String): Array<String> = arrayOf(
        "",
        "${SUCCESS}ProjectAntiFreeCam ${ACCENT}v$version${SUCCESS} - Anti-FreeCam Protection",
        "${INFO}Platform: ${SECONDARY}$platform${INFO} | Minecraft: ${SECONDARY}1.20-1.21.8+",
        "${SUCCESS}Multi-platform: ${SECONDARY}Folia, Paper, Spigot, Geyser",
        "${ACCENT}GitHub: ${SECONDARY}https://github.com/lonaldeu/ProjectAntiFreeCam",
        "${SUCCESS}Author: ${SECONDARY}lonaldeu",
        "${SUCCESS}Status: ${ACCENT}ACTIVE",
        ""
    )

    @JvmStatic
    fun createShutdownBanner(): Array<String> = arrayOf(
        "",
        "${WARNING}ProjectAntiFreeCam ${INFO}- Shutting down...",
        "${INFO}All protections safely disabled.",
        "${SUCCESS}Thanks for using ProjectAntiFreeCam!",
        ""
    )

    @JvmStatic
    fun createLoadingMessage(component: String, status: String): String {
        val statusColor = when (status) {
            "LOADING" -> ACCENT
            "SUCCESS" -> SUCCESS
            "FAILED" -> ERROR
            else -> INFO
        }
        return "$ACCENT  ⚡ $INFO$component: $statusColor$status"
    }

    @JvmStatic
    fun createPlatformMessage(platformInfo: String): String = 
        "${ACCENT}Platform Detection: ${SUCCESS}$platformInfo"

    @JvmStatic
    fun createWorldMessage(worlds: String): String = when {
        worlds.isEmpty() || worlds == "[]" -> "${WARNING}No worlds configured"
        else -> "${SUCCESS}Active in: ${SECONDARY}$worlds"
    }

    @JvmStatic
    fun createDebugMessage(enabled: Boolean): String {
        val status = if (enabled) "${SUCCESS}ENABLED" else "${INFO}DISABLED"
        return "${ACCENT}Debug Mode: $status"
    }

    private fun translateColorCodes(message: String): String {
        val component = legacySerializer.deserialize(message)
        return sectionSerializer.serialize(component)
    }

    @JvmStatic
    fun stripColorCodes(message: String): String {
        val component = sectionSerializer.deserialize(translateColorCodes(message))
        return plainSerializer.serialize(component)
    }

    @JvmStatic
    fun createSeparator(): String = "${PRIMARY}════════════════════════════════════════"

    @JvmStatic
    fun createUsage(command: String, usage: String, description: String): String =
        "${ACCENT}/$command $INFO$usage\n  $SECONDARY$description"

    @JvmStatic
    fun createPermissionDenied(): String = "${ERROR}You don't have permission!"

    @JvmStatic
    fun createReloadSuccess(): String = "${SUCCESS}Configuration reloaded!"

    @JvmStatic
    fun createPlayerNotFound(playerName: String): String = 
        "${ERROR}Player '$WARNING$playerName$ERROR' not found!"
}
