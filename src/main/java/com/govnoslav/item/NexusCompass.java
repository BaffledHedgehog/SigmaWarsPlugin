// src/main/java/com/govnoslav/item/NexusCompass.java
package com.govnoslav.item;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class NexusCompass implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey typeKey;
    private final Scoreboard scoreboard;
    private final Objective cdObj;
    private final Gson gson = new Gson();
    private final Random random = new Random();

    private static final int MAX_CD = 30; // seconds
    private final Map<UUID, Integer> cooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    public NexusCompass(JavaPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "type");

        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        this.scoreboard = mgr.getMainScoreboard();
        this.cdObj = scoreboard.getObjective("nexus_compass_cd");

        // cooldown tick every second
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    int cd = cooldowns.getOrDefault(id, 0);
                    if (cd > 0) {
                        cd--;
                        cooldowns.put(id, cd);
                        if (cdObj != null) {
                            cdObj.getScore(p.getName()).setScore(cd);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20, 20);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.COMPASS) return;
        var meta = item.getItemMeta();
        if (meta == null) return;
        String type = meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (!"nexus_compass".equals(type)) return;

        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        e.setCancelled(true);

        // Load coordinates list
        File file = new File(plugin.getDataFolder(), "nexus_coords.json");
        List<Map<String, Double>> coords;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Type listType = new TypeToken<ArrayList<Map<String, Double>>>(){}.getType();
            coords = gson.fromJson(json, listType);
        } catch (Exception ex) {
            player.sendMessage("[Compass] Failed to load coords.");
            return;
        }
        if (coords.isEmpty()) {
            player.sendMessage("[Compass] No saved islands.");
            return;
        }
        // pick random
        Map<String, Double> entry = coords.get(random.nextInt(coords.size()));
        double x = entry.get("x");
        double y = entry.get("y");
        double z = entry.get("z");
        World nexus = Bukkit.getWorlds().stream()
            .filter(w -> w.getName().toLowerCase().contains("nexus"))
            .findFirst().orElse(null);
        if (nexus == null) {
            player.sendMessage("[Compass] Nexus world not loaded.");
            return;
        }
        Location dest = new Location(nexus, x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(dest);

        // set cooldown
        cooldowns.put(id, MAX_CD);
        if (cdObj != null) cdObj.getScore(player.getName()).setScore(MAX_CD);
    }
}
