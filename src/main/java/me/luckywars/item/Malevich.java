// src/main/java/com/lws/item/Malevich.java
package me.luckywars.item;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class Malevich implements Listener {
    private final NamespacedKey itemKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey lootTableKey = new NamespacedKey("lbc", "malevich_rnd");
    private final Random random = new Random();

    public Malevich(JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        // 1) Every 5 ticks: fill empty slots only for players in allowed dims who have
        // a malevich
        new BukkitRunnable() {
            @Override
            public void run() {
                LootTable table = Bukkit.getLootTable(lootTableKey);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    World world = player.getWorld();
                    if (isExcludedDimension(world))
                        continue;

                    PlayerInventory inv = player.getInventory();
                    if (!hasMalevichInInventory(inv))
                        continue;

                    LootContext ctx = new LootContext.Builder(player.getLocation()).build();
                    fillInventory(inv, table, ctx);
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);

        // 2) Every second: process malevich items on the ground, except in excluded
        // dims
        new BukkitRunnable() {
            @Override
            public void run() {
                List<Item> males = gatherMalevichItems();
                if (males.isEmpty())
                    return;

                // If too many, explode all
                if (males.size() > 100) {
                    explodeAll(males);
                    return;
                }

                LootTable table = Bukkit.getLootTable(lootTableKey);
                for (Item male : males) {
                    World world = male.getWorld();
                    if (isExcludedDimension(world))
                        continue;

                    for (Entity near : male.getNearbyEntities(3, 3, 3)) {
                        if (!(near instanceof Item))
                            continue;
                        Item other = (Item) near;
                        if (isMalevich(other))
                            continue;

                        // convert non-malevich -> loot at its own location
                        other.remove();
                        LootContext ctx = new LootContext.Builder(other.getLocation()).build();
                        Collection<ItemStack> loots;
                        try {
                            loots = table != null ? table.populateLoot(random, ctx) : Collections.emptyList();
                        } catch (Exception ex) {
                            loots = Collections.emptyList();
                        }
                        for (ItemStack loot : loots) {
                            other.getWorld().dropItem(other.getLocation(), loot);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent e) {
        // no-op; ground logic handled by scheduled task
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        // no-op; ground logic handled by scheduled task
    }

    private void fillInventory(PlayerInventory inv, LootTable table, LootContext ctx) {
        ItemStack[] contents = inv.getContents();
        if (contents != null) {
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] == null) {
                    populateAndSet(inv::setItem, i, table, ctx);
                }
            }
        }
        if (inv.getHelmet() == null)
            populateAndSet(inv::setHelmet, table, ctx);
        if (inv.getChestplate() == null)
            populateAndSet(inv::setChestplate, table, ctx);
        if (inv.getLeggings() == null)
            populateAndSet(inv::setLeggings, table, ctx);
        if (inv.getBoots() == null)
            populateAndSet(inv::setBoots, table, ctx);
        if (inv.getItemInOffHand() == null) {
            populateAndSet(inv::setItemInOffHand, table, ctx);
        }
    }

    private void populateAndSet(BiConsumer<Integer, ItemStack> setter,
            int slot,
            LootTable table,
            LootContext ctx) {
        Collection<ItemStack> loots;
        try {
            loots = table != null ? table.populateLoot(random, ctx) : Collections.emptyList();
        } catch (Exception ex) {
            loots = Collections.emptyList();
        }
        for (ItemStack loot : loots) {
            setter.accept(slot, loot);
            break;
        }
    }

    private void populateAndSet(Consumer<ItemStack> setter,
            LootTable table,
            LootContext ctx) {
        Collection<ItemStack> loots;
        try {
            loots = table != null ? table.populateLoot(random, ctx) : Collections.emptyList();
        } catch (Exception ex) {
            loots = Collections.emptyList();
        }
        for (ItemStack loot : loots) {
            setter.accept(loot);
            break;
        }
    }

    private boolean hasMalevichInInventory(PlayerInventory inv) {
        ItemStack[] contents = inv.getContents();
        if (contents != null) {
            for (ItemStack stack : contents) {
                if (isMalevichStack(stack))
                    return true;
            }
        }
        if (isMalevichStack(inv.getItemInOffHand()))
            return true;
        if (isMalevichStack(inv.getHelmet()))
            return true;
        if (isMalevichStack(inv.getChestplate()))
            return true;
        if (isMalevichStack(inv.getLeggings()))
            return true;
        return isMalevichStack(inv.getBoots());
    }

    private boolean isMalevichStack(ItemStack stack) {
        if (stack == null)
            return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return false;
        PersistentDataContainer p = meta.getPersistentDataContainer();
        if (!p.has(itemKey, PersistentDataType.TAG_CONTAINER))
            return false;
        PersistentDataContainer inner = p.get(itemKey, PersistentDataType.TAG_CONTAINER);
        return inner != null
                && "malevich".equals(inner.get(typeKey, PersistentDataType.STRING));
    }

    private List<Item> gatherMalevichItems() {
        List<Item> list = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (isExcludedDimension(world))
                continue;
            for (Entity ent : world.getEntities()) {
                if (ent instanceof Item && isMalevich((Item) ent)) {
                    list.add((Item) ent);
                }
            }
        }
        return list;
    }

    private boolean isMalevich(Item item) {
        return isMalevichStack(item.getItemStack());
    }

    private void explodeAll(List<Item> males) {
        for (Item male : males) {
            World w = male.getWorld();
            male.remove();
            int count = random.nextInt(2) + 1;
            for (int i = 0; i < count; i++) {
                TNTPrimed tnt = w.spawn(male.getLocation(), TNTPrimed.class);
                Vector vel = new Vector(
                        random.nextDouble() - 0.5,
                        random.nextDouble(),
                        random.nextDouble() - 0.5).multiply(0.2);
                tnt.setVelocity(vel);
                tnt.setFuseTicks(random.nextInt(11));
            }
        }
    }

    /**
     * Returns true if the world's dimension key is "minecraft:nexus" or
     * "minecraft:imprinted".
     */
    private boolean isExcludedDimension(World world) {
        try {
            Method getHandle = world.getClass().getMethod("getHandle");
            Object nmsWorld = getHandle.invoke(world);
            Method dimMethod = nmsWorld.getClass().getMethod("dimension");
            Object resourceKey = dimMethod.invoke(nmsWorld);
            Method locMethod = resourceKey.getClass().getMethod("location");
            Object resourceLoc = locMethod.invoke(resourceKey);
            Method toString = resourceLoc.getClass().getMethod("toString");
            String dim = (String) toString.invoke(resourceLoc);
            return "minecraft:nexus".equals(dim) || "minecraft:imprinted".equals(dim);
        } catch (Exception e) {
            return false;
        }
    }
}
