// src/main/java/com/govnoslav/item/MetalPipe.java
package com.govnoslav.item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class MetalPipe implements Listener {
    private final NamespacedKey itemKey, typeKey;
    private final Map<UUID, Boolean> trackedItems = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> throwers     = new ConcurrentHashMap<>();

    public MetalPipe(JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        // таск трекинга onGround
        new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<UUID, Boolean>> it = trackedItems.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Boolean> entry = it.next();
                    Entity ent = plugin.getServer().getEntity(entry.getKey());
                    if (!(ent instanceof Item) || !ent.isValid()) {
                        it.remove();
                        throwers.remove(entry.getKey());
                        continue;
                    }
                    Item bItem = (Item) ent;
                    try {
                        Object nms = getHandle(bItem);
                        boolean now  = readOnGround(nms);
                        boolean prev = entry.getValue();

                        if (!prev && now) {
                            handleLanding(bItem, nms);
                            it.remove();
                            throwers.remove(entry.getKey());
                        } else if (prev && !now) {
                            bItem.setPickupDelay(Integer.MAX_VALUE);
                            entry.setValue(false);
                        } else {
                            entry.setValue(now);
                        }
                    } catch (ReflectiveOperationException ex) {
                        //plugin.getLogger().severe("MetalPipe reflection error: " + ex);
                        it.remove();
                        throwers.remove(entry.getKey());
                    }
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent e) {
        Item drop = e.getItemDrop();
        PersistentDataContainer root = drop.getItemStack()
            .getItemMeta()
            .getPersistentDataContainer();

        if (!root.has(itemKey, PersistentDataType.TAG_CONTAINER)) return;
        PersistentDataContainer lws = root.get(itemKey, PersistentDataType.TAG_CONTAINER);
        if (lws != null && "metal_pipe".equals(lws.get(typeKey, PersistentDataType.STRING))) {
            drop.setPickupDelay(Integer.MAX_VALUE);
            UUID id = drop.getUniqueId();
            trackedItems.put(id, false);
            throwers.put(id, e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof Item)) return;
        Item item = (Item) e.getEntity();
        PersistentDataContainer root = item.getItemStack()
            .getItemMeta()
            .getPersistentDataContainer();

        if (!root.has(itemKey, PersistentDataType.TAG_CONTAINER)) return;
        PersistentDataContainer lws = root.get(itemKey, PersistentDataType.TAG_CONTAINER);
        if (lws != null && "metal_pipe".equals(lws.get(typeKey, PersistentDataType.STRING))) {
            item.setPickupDelay(Integer.MAX_VALUE);
            trackedItems.put(item.getUniqueId(), false);
            throwers.remove(item.getUniqueId());
        }
    }

    private void handleLanding(Item bukkitItem, Object nms) throws ReflectiveOperationException {
        bukkitItem.setPickupDelay(20);
        bukkitItem.getWorld().playSound(
            bukkitItem.getLocation(),
            "lbcsounds.metal_pipe", 4.0f, 1.0f
        );

        UUID shooterId = throwers.get(bukkitItem.getUniqueId());
        if (shooterId == null) {
            shooterId = readThrowerFromNbt(nms);
        }

        for (Entity near : bukkitItem.getNearbyEntities(2.5, 2.5, 2.5)) {
            if (!(near instanceof LivingEntity)) continue;
            LivingEntity victim = (LivingEntity) near;
            if (shooterId != null) {
                Player shooter = Bukkit.getPlayer(shooterId);
                victim.damage(25.0, shooter != null ? shooter : bukkitItem);
            } else {
                victim.damage(25.0, bukkitItem);
            }
        }
    }

    // --- NMS / reflection helpers ---

    private Object getHandle(Item bukkitItem) throws ReflectiveOperationException {
        Method m = bukkitItem.getClass().getMethod("getHandle");
        return m.invoke(bukkitItem);
    }

    private boolean readOnGround(Object nms) throws ReflectiveOperationException {
        Class<?> cls = nms.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField("onGround");
                f.setAccessible(true);
                return f.getBoolean(nms);
            } catch (NoSuchFieldException ex) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Поле onGround не найдено");
    }

    /**
     * Исправленный фолбэк: теперь getIntArray(...) возвращает Optional<int[]>,
     * мы безопасно вызываем Optional.orElse(null) и только потом конвертим.
     */
    private UUID readThrowerFromNbt(Object nms) throws ReflectiveOperationException {
        // 1) сериализуем entity в CompoundTag
        Class<?> tagClass = Class.forName("net.minecraft.nbt.CompoundTag");
        Constructor<?> ctor = tagClass.getConstructor();
        Object emptyTag = ctor.newInstance();

        Method save = nms.getClass().getMethod("saveWithoutId", tagClass);
        Object tag  = save.invoke(nms, emptyTag);

        // 2) вызываем getIntArray -> Optional<int[]>
        Method getIntArray = tagClass.getMethod("getIntArray", String.class);
        Object maybeArr = getIntArray.invoke(tag, "Thrower");

        int[] arr = null;
        if (maybeArr instanceof Optional<?>) {
            @SuppressWarnings("unchecked")
            Optional<int[]> opt = (Optional<int[]>) maybeArr;
            arr = opt.orElse(null);
        }
        if (arr == null || arr.length != 4) return null;

        long msb = ((long) arr[0] << 32) | (arr[1] & 0xFFFFFFFFL);
        long lsb = ((long) arr[2] << 32) | (arr[3] & 0xFFFFFFFFL);
        return new UUID(msb, lsb);
    }
}
