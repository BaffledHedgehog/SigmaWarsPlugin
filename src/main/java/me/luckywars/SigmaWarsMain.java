package me.luckywars;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import me.luckywars.badapple.BadAppleService;
import me.luckywars.bedwars.BedwarsBootstrap;
import me.luckywars.bedwars.BedwarsRegionManager;
import me.luckywars.bedwars.MapClearCommand;
import me.luckywars.item.Malevich;
import me.luckywars.item.MetalPipe;
import me.luckywars.item.NexusCompass;
import me.luckywars.item.RocketArmor;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class SigmaWarsMain extends JavaPlugin implements Listener {

    private Regeneration regenHandler;
    private static final int MAX_ITEMS = 1000;
    private BuildManagerImpl buildManager;

    private static class TrackingItem {
        final Item item;

        TrackingItem(Item item, long spawnTick) {
            this.item = item;

        }
    }

    private final Map<World, Deque<TrackingItem>> itemQueues = new HashMap<>();

    @Override
    public void onEnable() {

        getCommand("mapclear").setExecutor(new MapClearCommand(this));

        BedwarsBootstrap.bootstrap(this);

        buildManager = new BuildManagerImpl(this);

        // Регистрируем Brigadier-команду "buildmanager" как BasicCommand
        this.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                evt -> evt.registrar().register("buildmanager", new io.papermc.paper.command.brigadier.BasicCommand() {

                    @Override
                    public void execute(io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                        // 1) Пароль обязателен
                        if (args.length < 1) {
                            source.getSender().sendMessage(net.kyori.adventure.text.Component
                                    .translatable(BuildManagerImpl.TL_WRONG_PASSWORD));
                            return;
                        }

                        final int passArg;
                        try {
                            passArg = Integer.parseInt(args[0]);
                        } catch (NumberFormatException ex) {
                            source.getSender().sendMessage(net.kyori.adventure.text.Component
                                    .translatable(BuildManagerImpl.TL_WRONG_PASSWORD));
                            return;
                        }

                        // 2) Берём исполнителя из /execute as ...
                        org.bukkit.entity.Player p = null;
                        var exec = source.getExecutor(); // <- это entity из `execute as`
                        if (exec instanceof org.bukkit.entity.Player pl)
                            p = pl;

                        // 3) Фолбэк: если хочется разрешить явную цель вторым аргументом
                        if (p == null && args.length >= 2) {
                            try {
                                // разрешаем селекторы (@s/@p/@a[...]) или ник
                                java.util.List<org.bukkit.entity.Entity> found = org.bukkit.Bukkit
                                        .selectEntities(source.getSender(), args[1]);
                                for (var e : found)
                                    if (e instanceof org.bukkit.entity.Player) {
                                        p = (org.bukkit.entity.Player) e;
                                        break;
                                    }
                            } catch (IllegalArgumentException ignored) {
                            }
                            if (p == null) {
                                org.bukkit.entity.Player byName = org.bukkit.Bukkit.getPlayerExact(args[1]);
                                if (byName != null)
                                    p = byName;
                            }
                        }

                        if (p == null) {
                            // Это сообщение увидишь, если команду запустили без /execute as <player>
                            source.getSender().sendMessage(net.kyori.adventure.text.Component.text("Only players."));
                            return;
                        }

                        // 4) Проверяем пароль по глобальному scoreboard’у (ticker:lbc.math)
                        Integer current = BuildManagerImpl.getScoreValue(
                                org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard(),
                                BuildManagerImpl.PASSWORD_OBJECTIVE,
                                BuildManagerImpl.PASSWORD_ENTRY,
                                0);
                        if (current == null || current != passArg) {
                            p.sendMessage(net.kyori.adventure.text.Component
                                    .translatable(BuildManagerImpl.TL_WRONG_PASSWORD));
                            return;
                        }

                        // 5) Открываем диалог
                        buildManager.openMainDialog(p);
                    }

                    @Override
                    public java.util.Collection<String> suggest(
                            io.papermc.paper.command.brigadier.CommandSourceStack source, String[] args) {
                        // простенький таб-комплитер: подсказываем целевой селектор
                        if (args.length == 2)
                            return java.util.List.of("@s", "@p");
                        return java.util.List.of();
                    }

                    @Override
                    public boolean canUse(org.bukkit.command.CommandSender sender) {
                        return sender.hasPermission("lws.buildmanager");
                    }

                    @Override
                    public @org.jetbrains.annotations.Nullable String permission() {
                        return "lws.buildmanager";
                    }
                }));

        getServer().getPluginManager().registerEvents(new ChatOnlyPermissionGate(), this);

        getServer().getPluginManager().registerEvents(new NexusCompass(this), this);

        BadAppleService.preloadBAF(this);

        final me.luckywars.badapple.BadAppleService badApple = new me.luckywars.badapple.BadAppleService(this);
        this.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register("badapple", badApple.command()));

        this.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register(
                        "clonetonexus",
                        new NexusCloneCommand(this)));

        this.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register(
                        "antigravity",
                        new AntigravityCommand(this)));

        this.getLifecycleManager().registerEventHandler(
                io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                evt -> evt.registrar().register("pulsar", new PulsarCommand(this)));

        getServer().getPluginManager().registerEvents(
                new RocketArmor(this),
                this);

        // Malevich
        Malevich malevich = new Malevich(this);
        getServer().getPluginManager().registerEvents(malevich, this);
        // register MetalPipe
        MetalPipe metalPipe = new MetalPipe(this);
        getServer().getPluginManager().registerEvents(metalPipe, this);

        this.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register("motion", new MotionBrigadier()));

        // regeneration
        // 1) создаём экземпляр Regeneration, передаём this (JavaPlugin)
        regenHandler = new Regeneration(this);
        getCommand("regen").setExecutor(regenHandler);
        getCommand("regen").setTabCompleter(regenHandler);
        getServer().getPluginManager().registerEvents(regenHandler, this);

        /////////////////////////////////////////////

        for (World world : getServer().getWorlds()) {
            Deque<TrackingItem> queue = new LinkedList<>();
            long initTick = world.getFullTime() - 1;
            for (Item it : world.getEntitiesByClass(Item.class)) {
                queue.addLast(new TrackingItem(it, initTick));

            }

            itemQueues.put(world, queue);
        }

        getServer().getPluginManager().registerEvents(this, this);
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<World, Deque<TrackingItem>> entry : itemQueues.entrySet()) {
                    Deque<TrackingItem> q = entry.getValue();

                    while (!q.isEmpty() && (!q.peekFirst().item.isValid() || q.peekFirst().item.isDead())) {
                        q.pollFirst();
                    }
                }
            }
        }.runTaskTimer(this, 1L, 5L);

        RotateMapService.register(getLifecycleManager(), this);

    }

    @Override
    public void onDisable() {
        itemQueues.clear();
        regenHandler.disable();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntitySpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof Item))
            return;

        Item item = (Item) e.getEntity();
        World world = item.getWorld();
        long currentTick = world.getFullTime();

        ItemStack stack = item.getItemStack();
        if (stack.getAmount() > 99) {
            stack.setAmount(99);
            item.setItemStack(stack);
        }

        Deque<TrackingItem> queue = itemQueues
                .computeIfAbsent(world, w -> new LinkedList<>());
        queue.addLast(new TrackingItem(item, currentTick));

        if (queue.size() > MAX_ITEMS) {
            int items = world.getEntityCount();
            if (items > 2000) {
                queue.pollFirst().item.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockDropItems(BlockDropItemEvent e) {

        List<Item> original = new ArrayList<>(e.getItems());
        Map<ItemStack, Integer> merged = new HashMap<>();
        for (Item it : original) {
            ItemStack base = it.getItemStack().clone();
            base.setAmount(1);
            merged.merge(base, it.getItemStack().getAmount(), Integer::sum);
        }

        e.getItems().clear();

        World world = e.getBlock().getWorld();
        Location loc = e.getBlock().getLocation().toCenterLocation();
        for (Map.Entry<ItemStack, Integer> entry : merged.entrySet()) {
            ItemStack template = entry.getKey();
            int total = entry.getValue();

            while (total > 0) {
                int chunk = Math.min(total, 99);
                total -= chunk;

                ItemStack spawnStack = template.clone();
                spawnStack.setAmount(chunk);

                world.dropItemNaturally(loc, spawnStack);
            }
        }
    }

    public class ChatOnlyPermissionGate implements Listener {
        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
            // Сообщение вида "/regen add 1 @s 200"
            String raw = e.getMessage();
            String cmd = raw.startsWith("/") ? raw.substring(1) : raw; // "regen add 1..."
            String label = cmd.split("\\s+", 2)[0].toLowerCase(Locale.ROOT); // "regen" или "bukkit:regen" и т.п.

            if (label.equals("regen") || label.equals("bukkit:regen") || label.equals("lws:regen")
                    || label.equals("antigravity") || label.equals("lws:antigravity") || label.equals("clonetonexus")
                    || label.equals("lws:clonetonexus") || label.equals("lws:badapple") || label.equals("badapple")
                    || label.equals("bedwars") || label.equals("lws:bedwars") || label.equals("teaminit")
                    || label.equals("lws:teaminit") || label.equals("lws:mapclear") || label.equals("mapclear")
                    || label.equals("lws:pulsar") || label.equals("pulsar")) {
                if (!e.getPlayer().hasPermission("lws.regen")) {
                    e.setCancelled(true);
                    e.getPlayer().sendMessage("§cУ вас нет прав на эту команду.");
                }
            }
        }

    }
}
