// src/main/java/com/lws/item/MetalPipe.java
package com.govnoslav.item;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MetalPipe implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey typeKey;
    /** UUID предмета → предыдущий onGround */
    private final Map<UUID, Boolean> trackedItems = new ConcurrentHashMap<>();

    public MetalPipe(JavaPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "lws:type");
        startMonitorTask();
    }

    /** Запускаем глобальный таск, который смотрит только за трекаемыми предметами */
    private void startMonitorTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<UUID, Boolean>> it = trackedItems.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Boolean> entry = it.next();
                    UUID id = entry.getKey();
                    boolean prevOnGround = entry.getValue();

                    Entity e = plugin.getServer().getEntity(id);
                    if (!(e instanceof Item) || !e.isValid()) {
                        it.remove();
                        continue;
                    }

                    Item bukkitItem = (Item) e;
                    try {
                        Object nmsItem = getNmsHandle(bukkitItem);

                        if (isExcludedDimension(nmsItem)) {
                            // в Nexus/Imprinted не обрабатываем и удаляем из трекинга
                            it.remove();
                            continue;
                        }

                        boolean nowOnGround = invokeIsOnGround(nmsItem);

                        // Первая смена Air→Ground
                        if (!prevOnGround && nowOnGround) {
                            handleLanding(bukkitItem, nmsItem);
                        }
                        // Ground→Air
                        else if (prevOnGround && !nowOnGround) {
                            bukkitItem.setPickupDelay(Integer.MAX_VALUE);
                        }

                        // Обновим состояние
                        entry.setValue(nowOnGround);

                    } catch (ReflectiveOperationException ex) {
                        plugin.getLogger().severe("MetalPipe reflection error: " + ex);
                        it.remove();
                    }
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    /** При спавне сразу ставим infinite-delay и стартуем трекинг */
    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof Item)) return;
        Item item = (Item) e.getEntity();

        PersistentDataContainer pdc = item.getItemStack()
            .getItemMeta().getPersistentDataContainer();
        String type = pdc.get(typeKey, PersistentDataType.STRING);
        if (!"metal_pipe".equals(type)) return;

        try {
            Object nmsItem = getNmsHandle(item);
            if (isExcludedDimension(nmsItem)) return;

            // делаем неподбираемым
            item.setPickupDelay(Integer.MAX_VALUE);
            // добавляем в трекинг, initial onGround = false (в воздухе)
            trackedItems.put(item.getUniqueId(), false);

        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().severe("MetalPipe onSpawn reflection error: " + ex);
        }
    }

    /** Логика при приземлении */
    private void handleLanding(Item bukkitItem, Object nmsItem) throws ReflectiveOperationException {
        // снова можно подбирать
        bukkitItem.setPickupDelay(0);

        // звук всем в 27 блоках
        bukkitItem.getWorld().playSound(
            bukkitItem.getLocation(),
            "lbcsounds.metal_pipe",  // кастомный звук из ресурс-пака
            4.0f, 1.0f
        );

        // дамажим всех в 2.5 блока
        for (Entity near : bukkitItem.getNearbyEntities(2.5, 2.5, 2.5)) {
            if (!(near instanceof LivingEntity)) continue;
            LivingEntity victim = (LivingEntity) near;

            Method getThrower = nmsItem.getClass().getMethod("getThrower");
            Object throwerId = getThrower.invoke(nmsItem);

            if (throwerId != null) {
                UUID uuid = (UUID) throwerId;
                Player shooter = Bukkit.getPlayer(uuid);
                if (shooter != null) {
                    victim.damage(25.0, shooter);
                } else {
                    victim.damage(25.0, bukkitItem);
                }
            } else {
                victim.damage(25.0, bukkitItem);
            }
        }
    }

    /** Reflection-утилиты **/
    private Object getNmsHandle(Item bukkitItem) throws ReflectiveOperationException {
        Method getHandle = bukkitItem.getClass().getMethod("getHandle");
        return getHandle.invoke(bukkitItem);
    }

    private boolean invokeIsOnGround(Object nmsItem) throws ReflectiveOperationException {
        Method m = nmsItem.getClass().getMethod("isOnGround");
        return (Boolean) m.invoke(nmsItem);
    }

    /** Проверяет, что предмет находится в мире Nexus или Imprinted */
    private boolean isExcludedDimension(Object nmsItem) {
        try {
            // Получаем поле level
            Field levelField = nmsItem.getClass().getDeclaredField("level");
            levelField.setAccessible(true);
            Object level = levelField.get(nmsItem);

            // Вызываем метод dimension() → ResourceKey<Level>
            Method dimensionKeyMethod = level.getClass().getMethod("dimension");
            Object resourceKey = dimensionKeyMethod.invoke(level);

            // ResourceKey.location() → ResourceLocation
            Method locationMethod = resourceKey.getClass().getMethod("location");
            Object resourceLocation = locationMethod.invoke(resourceKey);

            // Получаем строку namespace:path
            Method toString = resourceLocation.getClass().getMethod("toString");
            String dim = (String) toString.invoke(resourceLocation);

            return "minecraft:nexus".equals(dim) || "minecraft:imprinted".equals(dim);
        } catch (Exception ex) {
            // если не удалось определить — считаем, что обрабатывать можно
            return false;
        }
    }
}
