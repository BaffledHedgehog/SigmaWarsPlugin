package me.luckywars.bedwars;

import org.bukkit.plugin.java.JavaPlugin;

public final class BedwarsProtectorPlugin extends JavaPlugin {
    private static BedwarsRegionManager regionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        regionManager = new BedwarsRegionManager(this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(regionManager), this);
        if (getCommand("bedwars") != null) {
            getCommand("bedwars").setExecutor(new BedwarsCommand(regionManager));
        } else {
            getLogger().severe("Command 'bedwars' not found in plugin.yml");
        }
        getLogger().info("BedwarsProtector enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("BedwarsProtector disabled");
    }

    public static BedwarsRegionManager region() {
        return regionManager;
    }
}
