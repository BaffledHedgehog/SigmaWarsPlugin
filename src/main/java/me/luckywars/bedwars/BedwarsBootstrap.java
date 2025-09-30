package me.luckywars.bedwars;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

public final class BedwarsBootstrap {

    public static void bootstrap(JavaPlugin plugin) {
        // 1) Менеджер зоны/снимка
        BedwarsRegionManager regionManager = new BedwarsRegionManager(plugin);

        // 2) Подключаемся к Ignite-моду с миксинами (рефлексией)
        hookBedwarsMixins(regionManager, true /* debug */);

        // 3) Команда /bedwars init|stop
        BedwarsCommand cmd = new BedwarsCommand(regionManager);
        if (plugin.getCommand("bedwars") != null) {
            plugin.getCommand("bedwars").setExecutor(cmd);
            plugin.getCommand("bedwars").setTabCompleter(cmd);
        }

        // Команда /teaminit
        if (plugin.getCommand("teaminit") != null) {
            plugin.getCommand("teaminit").setExecutor(new TeamInitCommand(plugin));
        }

        // 4) Листенеры
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new ProtectionListener(regionManager), plugin);
        pm.registerEvents(new BedwarsShopListener(plugin), plugin);
        pm.registerEvents(new TeamChestManager(), plugin);
        pm.registerEvents(new TeamZombieEggListener(plugin), plugin);
        pm.registerEvents(new ShopMobIgnoreListener(), plugin);
        pm.registerEvents(new BedwarsDropsListener(regionManager), plugin);

        // 5) Периодический менеджер баффов маяков
        BeaconEffectsManager.start(plugin);
    }

    private static void hookBedwarsMixins(BedwarsRegionManager region, boolean debug) {
        try {
            // Берём корневой CL (Ignite грузит моды туда)
            ClassLoader root = Bukkit.getServer().getClass().getClassLoader();

            // Класс BedwarsHooks ДОЛЖЕН быть в Ignite-моде (mods/), не в плагине.
            Class<?> hooks = Class.forName("me.luckywars.bedwars.mixinhook.BedwarsHooks", true, root);

            // Готовим коллбеки через JDK-интерфейсы (без завязки на классы плагина в моде)
            BooleanSupplier isEnabled = region::isEnabled;
            Predicate<Block> isInside = region::isInside;
            Function<Block, Material> originalTypeAt = region::originalTypeAt;

            // public static void register(BooleanSupplier, Predicate<Block>,
            // Function<Block,Material>)
            Method register = hooks.getMethod(
                    "register",
                    BooleanSupplier.class,
                    Predicate.class,
                    Function.class);
            register.invoke(null, isEnabled, isInside, originalTypeAt);

            // public static void setDebug(boolean)
            //Method setDebug = hooks.getMethod("setDebug", boolean.class);
            //setDebug.invoke(null, debug);

            Bukkit.getLogger().info("[lws] Bedwars mixins detected & initialized");
        } catch (ClassNotFoundException e) {
            Bukkit.getLogger().warning("[lws] Bedwars mixins mod not found; protection mixins are inactive.");
        } catch (ReflectiveOperationException e) {
            Bukkit.getLogger().severe("[lws] Failed to init Bedwars mixins: " + e);
        }
    }

    private BedwarsBootstrap() {
    }
}
