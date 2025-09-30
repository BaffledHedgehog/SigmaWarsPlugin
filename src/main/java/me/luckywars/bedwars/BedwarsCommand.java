package me.luckywars.bedwars;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;

import java.util.List;
import java.util.Locale;

public final class BedwarsCommand implements CommandExecutor, TabCompleter {
    private final BedwarsRegionManager mgr;

    public BedwarsCommand(BedwarsRegionManager mgr) {
        this.mgr = mgr;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/bedwars <init|stop>");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("init")) {
            if (!sender.hasPermission("lws.bedwars.admin")) {
                sender.sendMessage("§cНет прав.");
                return true;
            }

            // Берём первый загруженный мир
            World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (world == null) {
                sender.sendMessage("§cНет загруженных миров.");
                return true;
            }

            // Ровно на нулевых мировых X/Z, Y — как у spawn мира (удобная высота)
            double y = world.getSpawnLocation().getY();
            Location origin = new Location(world, 0.0, y, 0.0);

            // Полный сброс покупных «маяков» и их эффектов
            BeaconEffectsManager.resetAll();

            mgr.initAt(origin);
            sender.sendMessage("§aЗащита включена: центр (0, " + origin.getBlockY() + ", 0), радиус ±192 по X/Z.");
            return true;

        } else if (sub.equals("stop")) {
            if (!sender.hasPermission("lws.bedwars.admin")) {
                sender.sendMessage("§cНет прав.");
                return true;
            }

            // Полный сброс покупных «маяков» и их эффектов
            BeaconEffectsManager.resetAll();

            mgr.stop();
            sender.sendMessage("§eЗащита выключена, снимок очищен.");
            return true;

        } else {
            sender.sendMessage("§7/bedwars <init|stop>");
            return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1)
            return List.of("init", "stop");
        return List.of();
    }
}
