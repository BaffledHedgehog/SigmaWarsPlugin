// src/main/java/com/lws/item/Malevich.java
package me.luckywars.item;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
//import java.util.function.BiConsumer;
//import java.util.function.Consumer;

import org.bukkit.Bukkit;
//import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;
//import org.bukkit.event.player.PlayerItemConsumeEvent;
//import org.bukkit.event.EventPriority;

public class Malevich implements Listener {
    private final JavaPlugin plugin;

    private final NamespacedKey itemKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey lootTableKey = new NamespacedKey("lbc", "malevich_rnd");
    private final Random random = new Random();

    // Игроков, которых нельзя трогать этим тиком (и ещё один) после ItemConsume
    private final Set<UUID> postConsumeCooldown = ConcurrentHashMap.newKeySet();

    public Malevich(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        // 1) Каждые 5 тиков: если у игрока есть "квадрат" и он не в cooldown, заполняем
        // пустые слоты
        new BukkitRunnable() {
            @Override
            public void run() {
                LootTable table = Bukkit.getLootTable(lootTableKey);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (postConsumeCooldown.contains(player.getUniqueId()))
                        continue;

                    World world = player.getWorld();
                    if (isExcludedDimension(world))
                        continue;
                    if (hasMagicStopperMarkerNearby(player, 20.0)) {
                        continue;
                    }
                    Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
                    Objective obj = main.getObjective("lbc.skill");
                    int value = obj.getScore(player.getName()).getScore();
                    if (value == 2) {
                        continue;
                    }

                    PlayerInventory inv = player.getInventory();
                    if (!hasMalevichInInventory(inv))
                        continue;

                    LootContext ctx = new LootContext.Builder(player.getLocation()).build();

                    // Коммит на следующий тик — безопаснее относительно кликов и других
                    // плагинов/датапаков
                    Bukkit.getScheduler().runTask(plugin, () -> fillInventoryAtomic(player, table, ctx));
                }
            }

        }.runTaskTimer(plugin, 1L, 5L); // сдвиг на 1 тик, чтобы реже совпадать с серв-триггерами

        // 2) Каждую секунду: поведение предметов на земле возле малевичей
        new BukkitRunnable() {
            @Override
            public void run() {
                List<Item> males = gatherMalevichItems();
                if (males.isEmpty())
                    return;

                LootTable table = Bukkit.getLootTable(lootTableKey);
                for (Item male : males) {
                    World world = male.getWorld();
                    if (isExcludedDimension(world))
                        continue;
                    if (hasMagicStopperMarkerNearby(male, 20.0)) {
                        continue;
                    }

                    boolean convertedSomething = false;
                    for (Entity near : male.getNearbyEntities(3, 3, 3)) {
                        if (!(near instanceof Item))
                            continue;
                        Item other = (Item) near;
                        if (isMalevich(other))
                            continue;

                        convertedSomething = true;
                        other.remove();
                        LootContext ctx = new LootContext.Builder(other.getLocation()).build();
                        Collection<ItemStack> loots = safeLoot(table, ctx);
                        for (ItemStack loot : loots) {
                            if (isEmpty(loot))
                                continue;
                            other.getWorld().dropItem(other.getLocation(), loot);
                        }
                    }

                    if (!convertedSomething && males.size() > 100) {
                        explodeAll(males);
                        return;
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    /* ===================== INVENTORY ===================== */

    private boolean hasMagicStopperMarkerNearby(Item p, double radius) {
        World w = p.getWorld();
        for (Entity ent : w.getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (ent.getType() == EntityType.MARKER && ent.getScoreboardTags().contains("stopper_magic")) {
                return true;
            }
        }
        return false;
    }

    // Атомарное и «тихое» заполнение. Никаких updateInventory().
    private void fillInventoryAtomic(Player player, LootTable table, LootContext ctx) {
        if (postConsumeCooldown.contains(player.getUniqueId()))
            return; // на всякий пожарный
        PlayerInventory inv = player.getInventory();

        // boolean anyChanged = false;

        // storage (0..35) — пакетной записью
        ItemStack[] storage = inv.getStorageContents();
        if (storage != null) {
            ItemStack[] newStorage = storage.clone();
            boolean storageChanged = false;
            for (int i = 0; i < newStorage.length; i++) {
                if (isEmpty(newStorage[i])) {
                    ItemStack loot = firstNonEmptyLoot(table, ctx);
                    if (loot != null) {
                        newStorage[i] = loot;
                        storageChanged = true;
                    }
                }
            }
            if (storageChanged) {
                inv.setStorageContents(newStorage);
                // anyChanged = true;
            }
        }

        // armor — точечно
        if (isEmpty(inv.getHelmet())) {
            ItemStack loot = firstNonEmptyLoot(table, ctx);
            if (loot != null) {
                inv.setHelmet(loot);
                // anyChanged = true;
            }
        }
        if (isEmpty(inv.getChestplate())) {
            ItemStack loot = firstNonEmptyLoot(table, ctx);
            if (loot != null) {
                inv.setChestplate(loot);
                // anyChanged = true;
            }
        }
        if (isEmpty(inv.getLeggings())) {
            ItemStack loot = firstNonEmptyLoot(table, ctx);
            if (loot != null) {
                inv.setLeggings(loot);
                // anyChanged = true;
            }
        }
        if (isEmpty(inv.getBoots())) {
            ItemStack loot = firstNonEmptyLoot(table, ctx);
            if (loot != null) {
                inv.setBoots(loot);
                // anyChanged = true;
            }
        }

        // offhand — точечно
        if (isEmpty(inv.getItemInOffHand())) {
            ItemStack loot = firstNonEmptyLoot(table, ctx);
            if (loot != null) {
                inv.setItemInOffHand(loot);
                // anyChanged = true;
            }
        }

        // НИЧЕГО не вызываем дополнительно (никаких updateInventory)
        // Клиент получит нормальные SetSlot/WindowItems.
    }

    private ItemStack firstNonEmptyLoot(LootTable table, LootContext ctx) {
        for (ItemStack loot : safeLoot(table, ctx)) {
            if (!isEmpty(loot))
                return loot;
        }
        return null;
    }

    private Collection<ItemStack> safeLoot(LootTable table, LootContext ctx) {
        try {
            return table != null ? table.populateLoot(random, ctx) : Collections.emptyList();
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    /* ===================== DETECTION ===================== */

    private boolean hasMalevichInInventory(PlayerInventory inv) {
        ItemStack[] storage = inv.getStorageContents();
        if (storage != null) {
            for (ItemStack s : storage)
                if (isMalevichStack(s))
                    return true;
        }
        for (ItemStack s : inv.getArmorContents())
            if (isMalevichStack(s))
                return true;
        return isMalevichStack(inv.getItemInOffHand());
    }

    private boolean isMalevich(Item item) {
        return isMalevichStack(item.getItemStack());
    }

    private boolean isMalevichStack(ItemStack stack) {
        // важно: выкидываем AIR/0amount, чтобы мета от фантомов не детектилась
        if (isEmpty(stack))
            return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return false;
        PersistentDataContainer p = meta.getPersistentDataContainer();
        if (!p.has(itemKey, PersistentDataType.TAG_CONTAINER))
            return false;
        PersistentDataContainer inner = p.get(itemKey, PersistentDataType.TAG_CONTAINER);
        return inner != null && "malevich".equals(inner.get(typeKey, PersistentDataType.STRING));
    }

    private boolean isEmpty(ItemStack s) {
        return s == null || s.getType().isAir() || s.getAmount() <= 0;
    }

    private List<Item> gatherMalevichItems() {
        List<Item> list = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (isExcludedDimension(world))
                continue;

            for (Item ent : world.getEntitiesByClass(Item.class)) {
                if (isMalevich(ent))
                    list.add(ent);
            }
        }
        return list;
    }

    /* ===================== EXPLOSION ===================== */

    private void explodeAll(List<Item> males) {
        for (Item male : males) {
            if (hasMagicStopperMarkerNearby(male, 20.0)) {
                continue;
            }
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

    /* ===================== DIMENSION FILTER ===================== */

    /**
     * true, если dimension key == "minecraft:nexus" или "minecraft:imprinted"
     */
    public boolean isExcludedDimension(World world) {
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

    private boolean hasMagicStopperMarkerNearby(Player p, double radius) {
        World w = p.getWorld();
        for (Entity ent : w.getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (ent.getType() == EntityType.MARKER && ent.getScoreboardTags().contains("stopper_magic")) {
                return true;
            }
        }
        return false;
    }
    /* ===================== EVENTS ===================== */

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent e) {
        // no-op; лежачие предметы обрабатываются планировщиком
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        // no-op; лежачие предметы обрабатываются планировщиком
    }

    // ...

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        int hotbar = p.getInventory().getHeldItemSlot(); // еда почти всегда из main-hand
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ItemStack cur = p.getInventory().getItem(hotbar);
            p.getInventory().setItem(hotbar, cur); // триггерит SetSlot для этого слота
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ItemStack cur = p.getInventory().getItem(hotbar);
            p.getInventory().setItem(hotbar, cur);
        }, 2L);
    }

}
