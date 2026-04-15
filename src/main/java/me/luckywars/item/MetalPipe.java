package me.luckywars.item;

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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class MetalPipe implements Listener {
    private final NamespacedKey pluginItemKey;
    private final NamespacedKey pluginTypeKey;
    private final NamespacedKey lwsItemKey = new NamespacedKey("lws", "item");
    private final NamespacedKey lwsTypeKey = new NamespacedKey("lws", "type");
    private final NamespacedKey legacyItemKey = new NamespacedKey("lucky_wars", "item");
    private final NamespacedKey legacyTypeKey = new NamespacedKey("lucky_wars", "type");
    private final Map<UUID, Boolean> trackedItems = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> throwers = new ConcurrentHashMap<>();

    public MetalPipe(JavaPlugin plugin) {
        this.pluginItemKey = new NamespacedKey(plugin, "item");
        this.pluginTypeKey = new NamespacedKey(plugin, "type");

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
        if (!isMetalPipe(drop.getItemStack())) {
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
        if (!isMetalPipe(item.getItemStack())) {
            return;
        }

        UUID id = item.getUniqueId();
        item.setPickupDelay(Integer.MAX_VALUE);
        trackedItems.put(id, false);
        throwers.remove(id);
    }

    private boolean isMetalPipe(ItemStack stack) {
        if (stack == null) {
            return false;
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            PersistentDataContainer root = meta.getPersistentDataContainer();
            if (isMetalPipeViaPdc(root, pluginItemKey, pluginTypeKey)
                    || isMetalPipeViaPdc(root, lwsItemKey, lwsTypeKey)
                    || isMetalPipeViaPdc(root, legacyItemKey, legacyTypeKey)) {
                return true;
            }
        }

        return false;
    }

    private boolean isMetalPipeViaPdc(PersistentDataContainer root, NamespacedKey itemKey, NamespacedKey typeKey) {
        if (root == null || !root.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
            return false;
        }

        PersistentDataContainer nested = root.get(itemKey, PersistentDataType.TAG_CONTAINER);
        if (nested == null) {
            return false;
        }

        return "metal_pipe".equals(nested.get(typeKey, PersistentDataType.STRING));
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
        if (world == null) {
            return false;
        }
        String dim = world.getKey().toString();
        return "minecraft:nexus".equals(dim) || "minecraft:imprinted".equals(dim);
    }
}
