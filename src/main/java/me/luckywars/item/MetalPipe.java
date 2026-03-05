package me.luckywars.item;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
    private final NamespacedKey itemKey;
    private final NamespacedKey typeKey;
    private final Map<UUID, Boolean> trackedItems = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> throwers = new ConcurrentHashMap<>();

    public MetalPipe(JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        new BukkitRunnable() {
            @Override
            public void run() {
                if (trackedItems.isEmpty()) {
                    return;
                }

                for (Iterator<Map.Entry<UUID, Boolean>> it = trackedItems.entrySet().iterator(); it.hasNext();) {
                    Map.Entry<UUID, Boolean> entry = it.next();
                    UUID id = entry.getKey();
                    boolean wasOnGround = entry.getValue();

                    Entity entity = plugin.getServer().getEntity(id);
                    if (!(entity instanceof Item item) || !item.isValid()) {
                        it.remove();
                        throwers.remove(id);
                        continue;
                    }

                    boolean nowOnGround = item.isOnGround();
                    if (wasOnGround && !nowOnGround) {
                        // Re-arm when it becomes airborne again.
                        item.setPickupDelay(Integer.MAX_VALUE);
                    } else if (!wasOnGround && nowOnGround) {
                        handleLanding(item);
                    }

                    entry.setValue(nowOnGround);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent event) {
        Item drop = event.getItemDrop();

        PersistentDataContainer stackPdc = drop.getItemStack().getItemMeta().getPersistentDataContainer();
        if (!stackPdc.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
            return;
        }

        PersistentDataContainer nested = stackPdc.get(itemKey, PersistentDataType.TAG_CONTAINER);
        String type = nested.get(typeKey, PersistentDataType.STRING);
        if (!"metal_pipe".equals(type)) {
            return;
        }

        UUID id = drop.getUniqueId();
        drop.setPickupDelay(Integer.MAX_VALUE);
        trackedItems.put(id, false);
        throwers.put(id, event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }

        PersistentDataContainer stackPdc = item.getItemStack().getItemMeta().getPersistentDataContainer();
        if (!stackPdc.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
            return;
        }

        PersistentDataContainer nested = stackPdc.get(itemKey, PersistentDataType.TAG_CONTAINER);
        String type = nested.get(typeKey, PersistentDataType.STRING);
        if (!"metal_pipe".equals(type)) {
            return;
        }

        UUID id = item.getUniqueId();
        item.setPickupDelay(Integer.MAX_VALUE);
        trackedItems.put(id, false);
        throwers.remove(id);
    }

    private void handleLanding(Item item) {
        if (isExcludedDimension(item.getWorld())) {
            return;
        }

        item.setPickupDelay(20);
        item.getWorld().playSound(item.getLocation(), "lbcsounds.metal_pipe", 4.0f, 1.0f);

        UUID shooterId = throwers.get(item.getUniqueId());
        for (Entity near : item.getNearbyEntities(2.5, 2.5, 2.5)) {
            if (!(near instanceof LivingEntity victim)) {
                continue;
            }

            if (shooterId != null) {
                Player shooter = Bukkit.getPlayer(shooterId);
                victim.damage(25.0, shooter != null ? shooter : item);
            } else {
                victim.damage(25.0, item);
            }
        }
    }

    public boolean isExcludedDimension(World world) {
        try {
            Method getHandle = world.getClass().getMethod("getHandle");
            Object nmsWorld = getHandle.invoke(world);
            Method dimMethod = nmsWorld.getClass().getMethod("dimension");
            Object resourceKey = dimMethod.invoke(nmsWorld);
            Method locMethod = resourceKey.getClass().getMethod("location");
            Object resourceLoc = locMethod.invoke(resourceKey);
            Method toStringMethod = resourceLoc.getClass().getMethod("toString");
            String dim = (String) toStringMethod.invoke(resourceLoc);
            return "minecraft:nexus".equals(dim) || "minecraft:imprinted".equals(dim);
        } catch (Exception e) {
            return false;
        }
    }
}
