package afctest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

/** Added to the e2e test servers: does what a vanish plugin and a per-player hologram plugin would do. */
public final class TestPlugin extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : null;
        if (target == null) return false;
        switch (command.getName()) {
            case "afcvanish" -> { // /afcvanish <player> on|off: hide the player from everyone else
                boolean on = args.length > 1 && args[1].equals("on");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p == target) continue;
                    if (on) p.hideEntity(this, target);
                    else p.showEntity(this, target);
                }
            }
            case "afcprivate" -> { // /afcprivate <player> <x> <y> <z>: a text display only that player sees
                if (args.length < 4) return false;
                Location at = new Location(target.getWorld(), Double.parseDouble(args[1]), Double.parseDouble(args[2]),
                        Double.parseDouble(args[3]));
                TextDisplay display = target.getWorld().spawn(at, TextDisplay.class, d -> d.setVisibleByDefault(false));
                target.showEntity(this, display);
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
