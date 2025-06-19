// src/main/java/com/govnoslav/item/NexusCompass.java
package com.govnoslav.item;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Right-clicking with a compass tagged as "nexus_compass" teleports you to a random Nexus island.
 */
public class NexusCompass implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey typeKey;
    private final Objective cdObj;
    private final Gson gson = new Gson();
    private final Random random = new Random();
    private final ConcurrentHashMap<UUID, Integer> cooldowns = new ConcurrentHashMap<>();
    private static final int MAX_CD = 30; // seconds

    public NexusCompass(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        Scoreboard sc = mgr.getMainScoreboard();
        this.cdObj = sc.getObjective("nexus_compass_cd");

        // schedule cooldown decrement every second
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID id : cooldowns.keySet()) {
                    int cd = cooldowns.get(id);
                    if (cd > 0) {
                        int next = cd - 1;
                        cooldowns.put(id, next);
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && cdObj != null) {
                            cdObj.getScore(p.getName()).setScore(next);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20, 20);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        //plugin.getLogger().info("[NexusCompass] onInteract triggered: action=" + e.getAction() + ", hand=" + e.getHand());
        // only main hand right-click
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack stack = e.getItem();
        if (stack == null) return;
        if (!hasCompassNBT(stack)) return;

        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        int cd = cooldowns.getOrDefault(id, 0);
        if (cd > 0) {
            //plugin.getLogger().info("[NexusCompass] On cooldown for " + cd + "s");
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);

        // load saved islands
        File file = new File(plugin.getDataFolder(), "nexus_coords.json");
        List<Map<String, Double>> coords;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<Map<String, Double>>>() {}.getType();
            coords = gson.fromJson(json, listType);
        } catch (IOException ex) {
            //plugin.getLogger().warning("[NexusCompass] Failed to load coordinates: " + ex);
            //player.sendMessage("[Compass] Failed to load coordinates.");
            return;
        }
        if (coords == null || coords.isEmpty()) {
            //plugin.getLogger().info("[NexusCompass] No islands saved.");
            //player.sendMessage("[Compass] No islands saved.");
            return;
        }

        // pick random island
        Map<String, Double> entry = coords.get(random.nextInt(coords.size()));
        double x = entry.getOrDefault("x", 0.0);
        double y = entry.getOrDefault("y", 0.0);
        double z = entry.getOrDefault("z", 0.0);

        World nexus = Bukkit.getWorlds().stream()
            .filter(w -> w.getName().toLowerCase().contains("nexus"))
            .findFirst().orElse(null);
        if (nexus == null) {
            //plugin.getLogger().warning("[NexusCompass] Nexus world not loaded.");
            //player.sendMessage("[Compass] Nexus world not loaded.");
            return;
        }
        Location dest = new Location(nexus, x, y, z,
            player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(dest);
        //plugin.getLogger().info("[NexusCompass] Teleported " + player.getName() + " to (" + x + "," + y + "," + z + ")");

        // set cooldown
        cooldowns.put(id, MAX_CD);
        if (cdObj != null) cdObj.getScore(player.getName()).setScore(MAX_CD);
    }

    /**
     * Checks PDC and, if absent, NBT for nexus_compass type.
     */
    private boolean hasCompassNBT(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            PersistentDataContainer root = meta.getPersistentDataContainer();
            //plugin.getLogger().info("[NexusCompass] PDC root keys: " + root.getKeys());
            if (root.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
                PersistentDataContainer lws = root.get(itemKey, PersistentDataType.TAG_CONTAINER);
                //plugin.getLogger().info("[NexusCompass] lws-item keys: " + (lws != null ? lws.getKeys() : "null"));
                if (lws != null && lws.has(typeKey, PersistentDataType.STRING)) {
                    String type = lws.get(typeKey, PersistentDataType.STRING);
                    //plugin.getLogger().info("[NexusCompass] Found PDC type: " + type);
                    if ("nexus_compass".equals(type)) return true;
                }
            }
        }
        // reflection fallback
        //plugin.getLogger().info("[NexusCompass] Falling back to NBT reflection");
        try {
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craft = Class.forName(pkg + ".inventory.CraftItemStack");
            Method asNMS = craft.getMethod("asNMSCopy", ItemStack.class);
            Object nms = asNMS.invoke(null, stack);
            Method getTag = nms.getClass().getMethod("getTag");
            Object maybeTag = getTag.invoke(nms);
            Object tag = maybeTag instanceof java.util.Optional<?> opt ? opt.orElse(null) : maybeTag;
            if (tag == null) {
                //plugin.getLogger().info("[NexusCompass] NBT tag is null");
                return false;
            }
            Method getComp = tag.getClass().getMethod("getCompound", String.class);
            Object cd = getComp.invoke(tag, "custom_data");
            Object pbv = getComp.invoke(cd, "PublicBukkitValues");
            Object lwsTag = getComp.invoke(pbv, "lws:item");
            //plugin.getLogger().info("[NexusCompass] NBT has lws:item tag");

            Method getString = lwsTag.getClass().getMethod("getString", String.class);
            String type = (String) getString.invoke(lwsTag, "lws:type");
            //plugin.getLogger().info("[NexusCompass] Reflection NBT type: " + type);
            return "nexus_compass".equals(type);
        } catch (Exception ex) {
            //plugin.getLogger().warning("[NexusCompass] NBT reflection failed: " + ex);
            return false;
        }
    }
}
