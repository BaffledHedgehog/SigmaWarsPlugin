// src/main/java/com/govnoslav/item/NexusCompass.java
package me.luckywars.item;

import java.io.File;
import java.io.IOException;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
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
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import net.kyori.adventure.text.Component;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Right-clicking with a compass tagged as "nexus_compass" teleports you to a
 * random Nexus island.
 */
public class NexusCompass implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey lwsItemKey = new NamespacedKey("lws", "item");
    private final NamespacedKey lwsTypeKey = new NamespacedKey("lws", "type");
    private final NamespacedKey legacyItemKey = new NamespacedKey("lucky_wars", "item");
    private final NamespacedKey legacyTypeKey = new NamespacedKey("lucky_wars", "type");

    private static final String CD_OBJECTIVE = "nexus_compass_cd";
    private static final int MAX_CD = 30;

    private final Gson gson = new Gson();
    private final Random random = new Random();
    private final ConcurrentHashMap<UUID, Integer> cooldowns = new ConcurrentHashMap<>();

    public NexusCompass(JavaPlugin plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        ensureObjectiveOn(Bukkit.getScoreboardManager().getMainScoreboard());

        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID id : cooldowns.keySet()) {
                    int cd = cooldowns.get(id);
                    if (cd > 0) {
                        int next = cd - 1;
                        cooldowns.put(id, next);
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            setCooldownScore(p, next); // <-- см. ниже
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20, 20);
    }

    private Objective ensureObjectiveOn(Scoreboard sb) {
        Objective obj = sb.getObjective(CD_OBJECTIVE);
        if (obj == null) {
            try {
                obj = sb.registerNewObjective(CD_OBJECTIVE, Criteria.DUMMY, Component.text(CD_OBJECTIVE));
            } catch (IllegalArgumentException ignored) {
                // если кто-то успел создать параллельно
                obj = sb.getObjective(CD_OBJECTIVE);
            }
        }
        return obj;
    }

    private void setCooldownScore(Player p, int value) {
        // 1) всегда обновляем MainScoreboard (его видит /scoreboard ...)
        var mgr = Bukkit.getScoreboardManager();
        if (mgr == null)
            return; // сервер ещё не полностью инициализирован
        Scoreboard mainSb = mgr.getMainScoreboard();
        Objective main = ensureObjectiveOn(mainSb);
        main.getScore(p.getName()).setScore(value);

        // 2) если у игрока свой скорборд (арена), синхронизируем и туда
        Scoreboard psb = p.getScoreboard();
        if (psb != null && psb != mainSb) {
            Objective obj = psb.getObjective(CD_OBJECTIVE);
            if (obj == null) {
                try {
                    obj = psb.registerNewObjective(CD_OBJECTIVE, Criteria.DUMMY, Component.text(CD_OBJECTIVE));
                } catch (IllegalArgumentException ignored) {
                    obj = psb.getObjective(CD_OBJECTIVE);
                }
            }
            if (obj != null) {
                obj.getScore(p.getName()).setScore(value);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        // plugin.getLogger().info("[NexusCompass] onInteract triggered: action=" +
        // e.getAction() + ", hand=" + e.getHand());
        // only main hand right-click
        if (e.getHand() != EquipmentSlot.HAND)
            return;
        Action act = e.getAction();
        if (act != Action.RIGHT_CLICK_AIR && act != Action.RIGHT_CLICK_BLOCK)
            return;

        ItemStack stack = e.getItem();
        if (stack == null)
            return;
        if (!hasCompassNBT(stack))
            return;

        Player player = e.getPlayer();
        if (isExcludedDimension(player.getWorld()) == true) {
            return;
        }

        if (hasMagicStopperMarkerNearby(player, 20.0)) {
            return;
        }

        UUID id = player.getUniqueId();
        int cd = cooldowns.getOrDefault(id, 0);
        if (cd > 0) {
            // plugin.getLogger().info("[NexusCompass] On cooldown for " + cd + "s");
            e.setCancelled(true);
            return;
        }
        e.setCancelled(true);

        // load saved islands
        File file = new File(plugin.getDataFolder(), "nexus_coords.json");
        List<Map<String, Double>> coords;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<Map<String, Double>>>() {
            }.getType();
            coords = gson.fromJson(json, listType);
        } catch (IOException ex) {
            // plugin.getLogger().warning("[NexusCompass] Failed to load coordinates: " +
            // ex);
            // player.sendMessage("[Compass] Failed to load coordinates.");
            return;
        }
        if (coords == null || coords.isEmpty()) {
            // plugin.getLogger().info("[NexusCompass] No islands saved.");
            // player.sendMessage("[Compass] No islands saved.");
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
            // plugin.getLogger().warning("[NexusCompass] Nexus world not loaded.");
            // player.sendMessage("[Compass] Nexus world not loaded.");
            return;
        }
        Location dest = new Location(nexus, x, y, z,
                player.getLocation().getYaw(), player.getLocation().getPitch());
        player.teleport(dest);
        // plugin.getLogger().info("[NexusCompass] Teleported " + player.getName() + "
        // to (" + x + "," + y + "," + z + ")");

        // set cooldown
        cooldowns.put(id, MAX_CD);
        setCooldownScore(player, MAX_CD);
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

    /**
     * Checks item tag via PDC.
     */
    private boolean hasCompassNBT(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer root = meta.getPersistentDataContainer();
        return hasType(root, itemKey, typeKey)
                || hasType(root, lwsItemKey, lwsTypeKey)
                || hasType(root, legacyItemKey, legacyTypeKey);
    }

    private boolean hasType(PersistentDataContainer root, NamespacedKey itemTagKey, NamespacedKey typeTagKey) {
        if (!root.has(itemTagKey, PersistentDataType.TAG_CONTAINER)) {
            return false;
        }
        PersistentDataContainer nested = root.get(itemTagKey, PersistentDataType.TAG_CONTAINER);
        if (nested == null) {
            return false;
        }
        return "nexus_compass".equals(nested.get(typeTagKey, PersistentDataType.STRING));
    }

    public boolean isExcludedDimension(World world) {
        if (world == null) {
            return false;
        }
        String dim = world.getKey().toString();
        return "minecraft:imprinted".equals(dim);
    }
}
