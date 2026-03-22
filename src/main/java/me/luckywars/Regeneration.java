package me.luckywars;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Класс Regeneration читает вложенный NBT-тег "regen",
 * внутри которого лежат параметры:
 * - value (PersistentDataType.DOUBLE)
 * - operation (PersistentDataType.STRING) : "add"/"set"/"multiple"
 * - slot (PersistentDataType.STRING) :
 * "head"/"chest"/"legs"/"feet"/"mainhand"/"offhand"/"armor"/"inventory"
 * - stackable (PersistentDataType.BYTE) : 1 или 0
 * - id (PersistentDataType.STRING) : строковый идентификатор группы
 */
public class Regeneration implements Listener, CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    // Ключ для вложенного контейнера "regen"
    private final NamespacedKey keyRegen;
    private final NamespacedKey keyRegenLws;
    private final NamespacedKey keyRegenLegacy;

    private final NamespacedKey keyValue; // regen.value > DOUBLE ("lws:value")
    private final NamespacedKey keyOperation; // regen.operation > STRING ("lws:operation")
    private final NamespacedKey keySlot; // regen.slot > STRING ("lws:slot")
    private final NamespacedKey keyStackable; // regen.stackable > BYTE ("lws:stackable")
    private final NamespacedKey keyId; // regen.id > STRING ("lws:id")
    private final NamespacedKey keyValueLws;
    private final NamespacedKey keyOperationLws;
    private final NamespacedKey keySlotLws;
    private final NamespacedKey keyStackableLws;
    private final NamespacedKey keyIdLws;
    private final NamespacedKey keyValueLegacy;
    private final NamespacedKey keyOperationLegacy;
    private final NamespacedKey keySlotLegacy;
    private final NamespacedKey keyStackableLegacy;
    private final NamespacedKey keyIdLegacy;

    // Plain-ключи без namespace, если Paper кладёт поля просто как "value","slot" и
    // т.п.
    private final NamespacedKey plainSlot; // Minecraft namespace "slot"
    private final NamespacedKey plainId; // Minecraft namespace "id"
    private final NamespacedKey plainStackable; // Minecraft namespace "stackable"
    private final NamespacedKey plainValue; // Minecraft namespace "value"
    private final NamespacedKey plainOperation; // Minecraft namespace "operation"

    // Эффекты команды /regen (UUID → список RegenEffect)
    private final Map<UUID, List<RegenEffect>> commandEffects = new ConcurrentHashMap<>();

    // Кеш реальной скорости регена
    private final Map<UUID, Double> playerRegenCache = new ConcurrentHashMap<>();
    private final Map<UUID, Double> entityRegenCache = new ConcurrentHashMap<>();

    private BukkitRunnable regenTickTask;

    public Regeneration(JavaPlugin plugin) {
        this.plugin = plugin;

        // Инициализируем ключи с namespace вашего плагина
        keyRegen = new NamespacedKey(plugin, "regen");
        keyRegenLws = new NamespacedKey("lws", "regen");
        keyRegenLegacy = new NamespacedKey("lucky_wars", "regen");
        keyValue = new NamespacedKey(plugin, "value");
        keyOperation = new NamespacedKey(plugin, "operation");
        keySlot = new NamespacedKey(plugin, "slot");
        keyStackable = new NamespacedKey(plugin, "stackable");
        keyId = new NamespacedKey(plugin, "id");
        keyValueLws = new NamespacedKey("lws", "value");
        keyOperationLws = new NamespacedKey("lws", "operation");
        keySlotLws = new NamespacedKey("lws", "slot");
        keyStackableLws = new NamespacedKey("lws", "stackable");
        keyIdLws = new NamespacedKey("lws", "id");
        keyValueLegacy = new NamespacedKey("lucky_wars", "value");
        keyOperationLegacy = new NamespacedKey("lucky_wars", "operation");
        keySlotLegacy = new NamespacedKey("lucky_wars", "slot");
        keyStackableLegacy = new NamespacedKey("lucky_wars", "stackable");
        keyIdLegacy = new NamespacedKey("lucky_wars", "id");

        // Инициализируем «plain» ключи без namespace
        plainSlot = new NamespacedKey(NamespacedKey.MINECRAFT, "slot");
        plainId = new NamespacedKey(NamespacedKey.MINECRAFT, "id");
        plainStackable = new NamespacedKey(NamespacedKey.MINECRAFT, "stackable");
        plainValue = new NamespacedKey(NamespacedKey.MINECRAFT, "value");
        plainOperation = new NamespacedKey(NamespacedKey.MINECRAFT, "operation");

        startRegenTask();
    }

    /**
     * Задача, выполняющаяся каждый тик: прибавляем к здоровью игроков и мобов
     * (LivingEntity) из кеша playerRegenCache / entityRegenCache.
     */
    private void startRegenTask() {
        regenTickTask = new BukkitRunnable() {
            @Override
            public void run() {
                // --- Игроки ---
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (isExcludedDimension(player.getWorld()) == true) {
                        return;
                    }

                    UUID uuid = player.getUniqueId();

                    Double regen = playerRegenCache.get(uuid);
                    if (regen == null || regen <= 0)
                        continue;

                    AttributeInstance inst = player.getAttribute(Attribute.MAX_HEALTH);
                    if (inst == null)
                        continue;

                    if (hasMagicStopperMarkerNearby(player, 20)) {
                        continue;
                    }

                    double maxHealth = inst.getValue();
                    double newHealth = Math.min(player.getHealth() + regen, maxHealth);
                    player.setHealth(newHealth);
                }

                // --- Мобы (LivingEntity) ---
                for (Map.Entry<UUID, Double> entry : entityRegenCache.entrySet()) {
                    Entity ent = Bukkit.getEntity(entry.getKey());
                    if (!(ent instanceof LivingEntity))
                        continue;

                    LivingEntity living = (LivingEntity) ent;
                    Double regen = entry.getValue();
                    if (regen == null || regen <= 0)
                        continue;

                    AttributeInstance inst = living.getAttribute(Attribute.MAX_HEALTH);
                    if (inst == null)
                        continue;

                    double max = inst.getValue();
                    double curr = living.getHealth();
                    living.setHealth(Math.min(curr + regen, max));
                }
            }
        };
        regenTickTask.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Пересчитывает скорость регена игрока, комбинируя эффекты /regen и
     * модификаторы от предметов в инвентаре/наборе брони.
     */
    private boolean hasMagicStopperMarkerNearby(Player p, double radius) {
        World w = p.getWorld();
        for (Entity ent : w.getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (ent.getType() == EntityType.MARKER && ent.getScoreboardTags().contains("stopper_magic")) {
                return true;
            }
        }
        return false;
    }

    private void recalcPlayerRegen(Player player) {
        // plugin.getLogger().info("[Recalc] recalcPlayerRegen() called for player=" +
        // player.getName());

        UUID uuid = player.getUniqueId();
        double base = 0.0;

        // 1) Сначала убираем устаревшие эффекты /regen:
        List<RegenEffect> effects = commandEffects.getOrDefault(uuid, new ArrayList<>());
        long now = System.currentTimeMillis();
        Iterator<RegenEffect> it = effects.iterator();
        while (it.hasNext()) {
            if (it.next().hasExpired(now))
                it.remove();
        }
        effects.sort(Comparator.comparingLong(e -> e.createdAt));
        for (RegenEffect e : effects) {
            switch (e.type) {
                case SET -> base = e.value;
                case MULT -> base = base * e.value;
                case ADD -> base = base + e.value;
            }
        }

        // plugin.getLogger().info("[Recalc] Base regen from /regen-effects = " + base);

        // 2) Теперь просчитываем модификаторы от предметов
        double finalRegen = processItemModifiers(player, base);
        playerRegenCache.put(uuid, finalRegen);

        // plugin.getLogger().info("[Recalc] Final regenSpeed = " + finalRegen);
    }

    /**
     * Идём по всем предметам в инвентаре/наборе брони, собираем группы по regen_id
     * и считаем итоговый прирост здоровья.
     */
    private double processItemModifiers(Player player, double initial) {
        double result = initial;

        Map<String, GroupData> nonArmorGroups = new LinkedHashMap<>();
        Map<String, GroupData> armorGroups = new LinkedHashMap<>();

        PlayerInventory inv = player.getInventory();

        // 1) Non-armor: mainhand, offhand, остальные слоты инвентаря
        gatherItem(inv.getItemInMainHand(), "mainhand", nonArmorGroups);
        gatherItem(inv.getItemInOffHand(), "offhand", nonArmorGroups);
        for (int idx = 0; idx < inv.getSize(); idx++) {
            if (idx == inv.getHeldItemSlot())
                continue; // hotbar-слот уже учтён
            gatherItem(inv.getContents()[idx], "inventory", nonArmorGroups);
        }

        // 2) Armor: helmet, chestplate, leggings, boots
        gatherItem(inv.getHelmet(), "head", armorGroups);
        gatherItem(inv.getChestplate(), "chest", armorGroups);
        gatherItem(inv.getLeggings(), "legs", armorGroups);
        gatherItem(inv.getBoots(), "feet", armorGroups);

        // 3) Применяем non-armor-группы
        for (GroupData gd : nonArmorGroups.values()) {
            switch (gd.type) {
                case SET -> result = gd.value;
                case MULT -> result = result * (gd.stackable ? Math.pow(gd.value, gd.count) : gd.value);
                case ADD -> result = result + (gd.stackable ? gd.value * gd.count : gd.value);
            }
        }
        // 4) Применяем armor-группы (последними)
        for (GroupData gd : armorGroups.values()) {
            switch (gd.type) {
                case SET -> result = gd.value;
                case MULT -> result = result * (gd.stackable ? Math.pow(gd.value, gd.count) : gd.value);
                case ADD -> result = result + (gd.stackable ? gd.value * gd.count : gd.value);
            }
        }

        return result;
    }

    /**
     * Считывает из единственного ItemStack вложенный контейнер "regen:{...}".
     * Если его нет — сразу return. Иначе из контейнера вытаскиваем:
     * 1) value (DOUBLE)
     * 2) operation (STRING)
     * 3) slot (STRING)
     * 4) stackable (BYTE)
     * 5) id (STRING)
     *
     * Будем поддерживать оба варианта ключей:
     * - с namespace плагина (lws:value, lws:slot, ...)
     * - plain (value, slot, ...)
     *
     * Плюс: если stackable=true, в count пойдёт ItemStack.getAmount(), а не 1.
     * если stackable=false, дополнительных «count++» вообще не делаем.
     */
    private void gatherItem(ItemStack stack, String slotKey, Map<String, GroupData> groupMap) {
        if (stack == null || stack.getType().isAir())
            return;

        ItemMeta itemMeta = stack.getItemMeta();
        if (itemMeta == null)
            return;

        PersistentDataContainer tag = itemMeta.getPersistentDataContainer();
        // plugin.getLogger().info("--- gatherItem called for slotKey=" + slotKey + ";
        // item=" + stack.getType());
        // for (NamespacedKey k : tag.getKeys()) {
        // plugin.getLogger().info("→ Найден PDC-ключ: " + k.getNamespace() + ":" +
        // k.getKey());
        // }
        // 1) Проверяем: есть ли вложенный контейнер “lws:regen”
        PersistentDataContainer regenTag = firstContainer(tag, keyRegen, keyRegenLws, keyRegenLegacy);
        if (regenTag == null)
            return;

        // 2) Читаем value (DOUBLE)
        Double valueRaw = firstDouble(regenTag, keyValue, keyValueLws, keyValueLegacy, plainValue);
        if (valueRaw == null)
            return;
        double value = valueRaw;

        String op = firstString(regenTag, keyOperation, keyOperationLws, keyOperationLegacy, plainOperation);
        if (op == null)
            return;
        op = op.toLowerCase(Locale.ROOT);

        String regenSlot = firstString(regenTag, keySlot, keySlotLws, keySlotLegacy, plainSlot);
        if (regenSlot == null)
            return;
        regenSlot = regenSlot.toLowerCase(Locale.ROOT);

        Byte stackableRaw = firstByte(regenTag, keyStackable, keyStackableLws, keyStackableLegacy, plainStackable);
        boolean stackable = stackableRaw != null && stackableRaw != 0;

        String regenId = firstString(regenTag, keyId, keyIdLws, keyIdLegacy, plainId);
        if (regenId == null || regenId.isEmpty())
            return;

        // Проверяем, «активен» ли предмет в данном слоте пользователя
        boolean active = switch (regenSlot) {
            case "inventory" -> true;
            case "armor" -> slotKey.equals("head") || slotKey.equals("chest")
                    || slotKey.equals("legs") || slotKey.equals("feet");
            case "head" -> slotKey.equals("head");
            case "chest" -> slotKey.equals("chest");
            case "legs" -> slotKey.equals("legs");
            case "feet" -> slotKey.equals("feet");
            case "mainhand" -> slotKey.equals("mainhand");
            case "offhand" -> slotKey.equals("offhand");
            default -> false;
        };
        if (!active)
            return;

        // Тип операции
        RegenType type = switch (op) {
            case "set" -> RegenType.SET;
            case "multiple", "mult" -> RegenType.MULT;
            default -> RegenType.ADD;
        };

        // Группируем по regenId
        GroupData gd = groupMap.get(regenId);
        if (gd == null) {
            // Впервые встретили этот regen_id:
            int initialCount = stackable ? stack.getAmount() : 1;
            gd = new GroupData(type, value, stackable, initialCount);
            groupMap.put(regenId, gd);
        } else {
            // Уже была такая группа:
            if (stackable) {
                gd.count += stack.getAmount();
            }
            // если stackable == false => не меняем gd.count дальше (игнорируем дубликаты)
        }
    }
    private PersistentDataContainer firstContainer(PersistentDataContainer root, NamespacedKey... keys) {
        for (NamespacedKey key : keys) {
            if (!root.has(key, PersistentDataType.TAG_CONTAINER))
                continue;
            PersistentDataContainer nested = root.get(key, PersistentDataType.TAG_CONTAINER);
            if (nested != null)
                return nested;
        }
        return null;
    }

    private Double firstDouble(PersistentDataContainer root, NamespacedKey... keys) {
        for (NamespacedKey key : keys) {
            if (root.has(key, PersistentDataType.DOUBLE)) {
                return root.get(key, PersistentDataType.DOUBLE);
            }
        }
        return null;
    }

    private String firstString(PersistentDataContainer root, NamespacedKey... keys) {
        for (NamespacedKey key : keys) {
            if (root.has(key, PersistentDataType.STRING)) {
                return root.get(key, PersistentDataType.STRING);
            }
        }
        return null;
    }

    private Byte firstByte(PersistentDataContainer root, NamespacedKey... keys) {
        for (NamespacedKey key : keys) {
            if (root.has(key, PersistentDataType.BYTE)) {
                return root.get(key, PersistentDataType.BYTE);
            }
        }
        return null;
    }
    private void recalcEntityRegen(LivingEntity ent) {
        UUID uuid = ent.getUniqueId();
        double base = 0.0;

        List<RegenEffect> effects = commandEffects.getOrDefault(uuid, new ArrayList<>());
        long now = System.currentTimeMillis();
        Iterator<RegenEffect> it = effects.iterator();
        while (it.hasNext()) {
            if (it.next().hasExpired(now))
                it.remove();
        }
        effects.sort(Comparator.comparingLong(e -> e.createdAt));
        for (RegenEffect e : effects) {
            switch (e.type) {
                case SET -> base = e.value;
                case MULT -> base = base * e.value;
                case ADD -> base = base + e.value;
            }
        }
        entityRegenCache.put(uuid, base);
    }

    /* ========== СЛУШАТЕЛИ ========== */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        recalcPlayerRegen(e.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(player));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(player));
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(e.getPlayer()));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(player));
        }
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(e.getPlayer()));
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent e) {
        UUID uuid = e.getEntity().getUniqueId();
        playerRegenCache.remove(uuid);
        entityRegenCache.remove(uuid);
        commandEffects.remove(uuid);
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent e) {
        UUID uuid = e.getEntity().getUniqueId();
        playerRegenCache.remove(uuid);
        entityRegenCache.remove(uuid);
        commandEffects.remove(uuid);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        // При дропе предмета пересчитываем реген
        Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(e.getPlayer()));
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent e) {
        // При поднятии предмета пересчитываем реген
        if (e.getEntity() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(player));
        }
    }

    @EventHandler
    public void onPlayerArmorChange(com.destroystokyo.paper.event.player.PlayerArmorChangeEvent e) {
        // При надевании/снятии брони пересчитываем реген
        Player player = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> recalcPlayerRegen(player));
    }

    /* ========== КОМАНДА /regen ========== */

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!command.getName().equalsIgnoreCase("regen"))
            return false;
        if (args.length < 3) {
            sender.sendMessage("§cИспользование: /regen <add|set|multiple> <число> <цель> [длительность]");
            return true;
        }
        String typeStr = args[0].toLowerCase();
        RegenType type = switch (typeStr) {
            case "add" -> RegenType.ADD;
            case "set" -> RegenType.SET;
            case "multiple", "mult" -> RegenType.MULT;
            default -> {
                sender.sendMessage("§cНеверный тип: выберите add, set или multiple.");
                yield null;
            }
        };
        if (type == null)
            return true;

        double value;
        try {
            value = Double.parseDouble(args[1]);
        } catch (NumberFormatException ex) {
            sender.sendMessage("§cНеверное число: " + args[1]);
            return true;
        }
        String targetArg = args[2];
        int duration = 0;
        if (args.length >= 4) {
            try {
                duration = Integer.parseInt(args[3]);
                if (duration < 0)
                    duration = 0;
            } catch (NumberFormatException ex) {
                return true;
            }
        }

        Collection<Entity> targets;
        try {
            targets = Bukkit.selectEntities(sender, targetArg);
        } catch (org.bukkit.command.CommandException | IllegalArgumentException ex) {
            return true;
        }
        if (targets.isEmpty()) {
            return true;
        }

        long now = System.currentTimeMillis();
        long expireAt = (duration > 0) ? now + duration * 50L : 0L;

        for (Entity ent : targets) {
            if (!(ent instanceof LivingEntity))
                continue;
            UUID uuid = ent.getUniqueId();
            List<RegenEffect> list = commandEffects.computeIfAbsent(uuid, k -> new ArrayList<>());

            if (type == RegenType.SET) {
                list.clear();
                if (ent instanceof Player) {
                    playerRegenCache.remove(uuid);
                } else {
                    entityRegenCache.remove(uuid);
                }
            }
            RegenEffect effect = new RegenEffect(type, value, now, expireAt);
            list.add(effect);

            if (duration > 0) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        List<RegenEffect> effs = commandEffects.get(uuid);
                        if (effs != null) {
                            effs.remove(effect);
                            if (ent instanceof Player player) {
                                recalcPlayerRegen(player);
                            } else if (ent instanceof LivingEntity living) {
                                recalcEntityRegen(living);
                            }
                        }
                    }
                }.runTaskLater(plugin, duration);
            }

            if (ent instanceof Player player) {
                recalcPlayerRegen(player);
            } else {
                recalcEntityRegen((LivingEntity) ent);
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("regen"))
            return Collections.emptyList();
        if (args.length == 1) {
            return Arrays.asList("add", "set", "multiple");
        } else if (args.length == 3) {
            return Arrays.asList("@a", "@p", "@r", "@e");
        }
        return Collections.emptyList();
    }

    /* ================= ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ================= */

    private static class RegenEffect {
        final RegenType type;
        final double value;
        final long createdAt;
        final long expiresAt;

        RegenEffect(RegenType type, double value, long createdAt, long expiresAt) {
            this.type = type;
            this.value = value;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        boolean hasExpired(long now) {
            return (expiresAt > 0 && now >= expiresAt);
        }
    }

    private static class GroupData {
        final RegenType type;
        final double value;
        final boolean stackable;
        int count;

        GroupData(RegenType type, double value, boolean stackable, int count) {
            this.type = type;
            this.value = value;
            this.stackable = stackable;
            this.count = count;
        }
    }

    private enum RegenType {
        ADD,
        SET,
        MULT
    }

    /** Останавливает тик-задачу при выключении плагина */
    public void disable() {
        if (regenTickTask != null) {
            regenTickTask.cancel();
        }
    }

    public boolean isExcludedDimension(World world) {
        if (world == null) {
            return false;
        }
        String dim = world.getKey().toString();
        return "minecraft:imprinted".equals(dim);
    }
}

