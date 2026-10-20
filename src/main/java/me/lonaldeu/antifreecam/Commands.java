package me.lonaldeu.antifreecam;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

final class Commands implements TabExecutor {

    private static final String PREFIX = "§8[§3§lAntiFreeCam§8]§r ";
    private static final List<String> SUBCOMMANDS = List.of("debug", "reload", "world", "stats", "test");
    private static final List<String> WORLD_ACTIONS = List.of("list", "add", "remove");
    private static final List<String> TESTS = List.of("block", "state", "refresh");

    private final AntiFreeCam plugin;

    Commands(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        switch (cmd.getName()) {
            case "afcdebug" -> debug(s);
            case "afcreload" -> reload(s);
            case "afcworld" -> world(s, args);
            default -> {
                if (args.length == 0) {
                    send(s, "§7Usage: " + cmd.getUsage().replace("<command>", label));
                    return true;
                }
                String[] rest = Arrays.copyOfRange(args, 1, args.length);
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "debug" -> debug(s);
                    case "reload" -> reload(s);
                    case "world" -> world(s, rest);
                    case "stats" -> stats(s);
                    case "test" -> test(s, rest);
                    default -> send(s, "§cNo such subcommand: " + args[0]);
                }
            }
        }
        return true;
    }

    private static boolean denied(CommandSender s, String permission) {
        if (s.hasPermission(permission)) return false;
        send(s, "§cYou don't have permission to do that.");
        return true;
    }

    private static void send(CommandSender s, String message) {
        s.sendMessage(PREFIX + message);
    }

    private void debug(CommandSender s) {
        if (denied(s, "antifreecam.debug")) return;
        plugin.debug = !plugin.debug;
        plugin.getConfig().set("settings.debug-mode", plugin.debug);
        plugin.saveConfig();
        send(s, "§aDebug logging " + (plugin.debug ? "§aon" : "§7off") + "§a.");
    }

    private void reload(CommandSender s) {
        if (denied(s, "antifreecam.reload")) return;
        plugin.loadSettings();
        plugin.reevaluateAll(true);
        send(s, "§aSettings reloaded.");
        send(s, "§7Protected worlds: §b" + new TreeSet<>(plugin.worlds));
        send(s, "§7Bedrock players online: §d" + plugin.bedrock.onlineCount());
    }

    private void world(CommandSender s, String[] args) {
        if (denied(s, "antifreecam.world")) return;
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (action.equals("list")) {
            send(s, plugin.worlds.isEmpty() ? "§7No protected worlds." : "§aProtected worlds: §f" + String.join(", ", new TreeSet<>(plugin.worlds)));
            return;
        }
        if (!action.equals("add") && !action.equals("remove")) {
            send(s, "§7Usage: /afcworld <list|add|remove> [world]");
            return;
        }
        if (args.length < 2) {
            send(s, "§cName a world.");
            return;
        }
        String name = args[1];
        var updated = new TreeSet<>(plugin.worlds);
        if (action.equals("add")) {
            if (Bukkit.getWorld(name) == null) {
                send(s, "§cWorld not found: " + name);
                return;
            }
            if (!updated.add(name)) {
                send(s, "§6" + name + " is already protected.");
                return;
            }
        } else if (!updated.remove(name)) {
            send(s, "§6" + name + " is not protected.");
            return;
        }
        plugin.getConfig().set("worlds.whitelist", new ArrayList<>(updated));
        plugin.saveConfig();
        plugin.worlds = java.util.Set.copyOf(updated);
        plugin.reevaluateAll(true);
        send(s, "§a" + (action.equals("add") ? "Now protecting " : "No longer protecting ") + name + ".");
    }

    private void stats(CommandSender s) {
        if (denied(s, "antifreecam.admin")) return;
        Bedrock b = plugin.bedrock;
        send(s, "§3§lStatus");
        s.sendMessage("§eVersion: §a" + plugin.getPluginMeta().getVersion());
        s.sendMessage("§ePlatform: §a" + plugin.platform());
        long surface = plugin.views.values().stream().filter(AntiFreeCam.View::surface).count();
        s.sendMessage("§ePlayers with filtered view: §a" + plugin.views.size() + " §7(" + surface + " above ground, "
                + (plugin.views.size() - surface) + " depth-limited)");
        s.sendMessage("§eProtected worlds: §a" + plugin.worlds.size());
        s.sendMessage("§eDebug: §a" + (plugin.debug ? "on" : "off"));
        if (!b.enabled) {
            s.sendMessage("§eBedrock detection: §7off");
            return;
        }
        s.sendMessage("§eBedrock detection: §aon §7(Geyser " + mark(b.useGeyser && b.geyserPresent())
                + "§7, Floodgate " + mark(b.useFloodgate && b.floodgatePresent())
                + "§7, UUID " + mark(b.useUuid)
                + "§7, prefix " + mark(b.useNames) + " §7" + b.prefixes + ")");
        s.sendMessage("§eBedrock players online: §a" + b.onlineCount());
    }

    private static String mark(boolean on) {
        return on ? "§a✓" : "§c✗";
    }

    private void test(CommandSender s, String[] args) {
        if (denied(s, "antifreecam.admin")) return;
        if (!(s instanceof Player p)) {
            send(s, "§cOnly players can run tests.");
            return;
        }
        switch (args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "") {
            case "block" -> send(s, "§7Replacement block: §f" + plugin.replacementName + " §7(state id " + plugin.replacementId + ")");
            case "state" -> {
                AntiFreeCam.View view = plugin.views.get(p.getUniqueId());
                send(s, String.format("§7Your Y: §f%.1f §7| protection Y: §f%.1f §7| hidden: §f%s", p.getY(), plugin.protectionY,
                        view == null ? "nothing" : "Y <= " + view.maxHiddenY() + (view.surface() ? " (above ground)" : " (depth limit)")));
            }
            case "refresh" -> {
                if (!plugin.worlds.contains(p.getWorld().getName())) {
                    send(s, "§6This world is not protected.");
                    return;
                }
                plugin.refreshView(p);
                send(s, "§aView refreshed.");
            }
            default -> send(s, "§7Tests: " + String.join(", ", TESTS));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] args) {
        List<String> options = switch (cmd.getName()) {
            case "afcworld" -> worldOptions(args);
            case "antifreecam" -> {
                if (args.length == 1) yield SUBCOMMANDS;
                if (args[0].equalsIgnoreCase("world")) yield worldOptions(Arrays.copyOfRange(args, 1, args.length));
                if (args.length == 2 && args[0].equalsIgnoreCase("test")) yield TESTS;
                yield List.of();
            }
            default -> List.of();
        };
        return StringUtil.copyPartialMatches(args[args.length - 1], options, new ArrayList<>());
    }

    private List<String> worldOptions(String[] args) {
        if (args.length == 1) return WORLD_ACTIONS;
        if (args.length != 2) return List.of();
        if (args[0].equalsIgnoreCase("add")) return Bukkit.getWorlds().stream().map(World::getName).toList();
        if (args[0].equalsIgnoreCase("remove")) return List.copyOf(plugin.worlds);
        return List.of();
    }
}
