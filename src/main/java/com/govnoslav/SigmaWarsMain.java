package com.govnoslav;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class SigmaWarsMain extends JavaPlugin implements Listener {

    private Regeneration regenHandler;
    private static final int MAX_ITEMS = 1000;

    private static class TrackingItem {
        final Item item;

        TrackingItem(Item item, long spawnTick) {
            this.item = item;

        }
    }

    private final Map<World, Deque<TrackingItem>> itemQueues = new HashMap<>();

    @Override
    public void onEnable() {
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

            TrackingItem old = queue.pollFirst();
            if (old != null && old.item.isValid()) {
                old.item.remove();
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

}
