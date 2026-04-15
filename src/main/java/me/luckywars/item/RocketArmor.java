package me.luckywars.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
//import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
//import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

// + добавь импорты
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;

//import io.papermc.paper.event.entity.EntityEquipmentChangedEvent.EquipmentChange;
//import org.jetbrains.annotations.NotNull;
//import org.jetbrains.annotations.Nullable;
//import net.kyori.adventure.text.TextComponent;
//import me.luckywars.item.Malevich;

public class RocketArmor implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey pluginItemKey;
    private final NamespacedKey pluginTypeKey;
    private final NamespacedKey pluginAdditionalsKey;
    private final NamespacedKey pluginGliderKey;
    private final NamespacedKey lwsItemKey = new NamespacedKey("lws", "item");
    private final NamespacedKey lwsTypeKey = new NamespacedKey("lws", "type");
    private final NamespacedKey lwsAdditionalsKey = new NamespacedKey("lws", "additionals");
    private final NamespacedKey lwsGliderKey = new NamespacedKey("lws", "glider");
    private final NamespacedKey legacyItemKey = new NamespacedKey("lucky_wars", "item");
    private final NamespacedKey legacyTypeKey = new NamespacedKey("lucky_wars", "type");
    private final NamespacedKey legacyAdditionalsKey = new NamespacedKey("lucky_wars", "additionals");
    private final NamespacedKey legacyGliderKey = new NamespacedKey("lucky_wars", "glider");
    private final NamespacedKey plainAdditionalsKey = new NamespacedKey(NamespacedKey.MINECRAFT, "additionals");

    private static final String JUMPS_OBJ_NAME = "rocket_armor_jumps";
    private static final String CD_OBJ_NAME = "rocket_armor_jump_cd";
    private static final int MAX_JUMPS = 3;
    private static final int COOLDOWN_TICKS = 20 * 15;

    private final Map<UUID, Integer> jumps = new HashMap<>();
    private final Map<UUID, Integer> cooldown = new HashMap<>();

    public RocketArmor(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pluginItemKey = new NamespacedKey(plugin, "item");
        this.pluginTypeKey = new NamespacedKey(plugin, "type");
        this.pluginAdditionalsKey = new NamespacedKey(plugin, "additionals");
        this.pluginGliderKey = new NamespacedKey(plugin, "glider");

        // Periodic task updates stats and ensures objectives exist on each player's
        // scoreboard
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
                        continue;
                    }
                    tickRocketState(p);
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    private void tickRocketState(Player player) {
        UUID id = player.getUniqueId();
        if (!wearsRocketArmor(player)) {
            jumps.remove(id);
            cooldown.remove(id);
            updateScores(player, 0, 0);
            return;
        }

        int currentJumps = jumps.computeIfAbsent(id, ignored -> MAX_JUMPS);
        int currentCooldown = cooldown.computeIfAbsent(id, ignored -> 0);

        if (currentCooldown > 0) {
            currentCooldown--;
        } else if (currentJumps < MAX_JUMPS) {
            currentJumps++;
            currentCooldown = (currentJumps < MAX_JUMPS) ? COOLDOWN_TICKS : 0;
        }

        jumps.put(id, currentJumps);
        cooldown.put(id, currentCooldown);

        syncBootsGliderComponent(player, currentJumps > 0);
        updateScores(player, currentJumps, currentCooldown);
    }

    private void updateScores(Player player, int jumpsValue, int cooldownTicks) {
        Scoreboard sb = player.getScoreboard();
        Objective jumpsObj = getOrCreateObjective(sb, JUMPS_OBJ_NAME, "dummy");
        Objective cdObj = getOrCreateObjective(sb, CD_OBJ_NAME, "dummy");
        jumpsObj.getScore(player.getName()).setScore(jumpsValue);
        int secs = (cooldownTicks + 19) / 20;
        cdObj.getScore(player.getName()).setScore(secs);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> tickRocketState(e.getPlayer()));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        jumps.remove(id);
        cooldown.remove(id);
    }

    @EventHandler
    public void onEquip(PlayerArmorChangeEvent e) {
        Player player = e.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> tickRocketState(player));
    }

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getEntity();
        if (!e.isGliding()) {
            return;
        }
        boolean isElytra = hasElytra(p); // Cancel glide start if not using elytra to trigger rocket boost
        if (!isElytra) {
            e.setCancelled(true);
            p.setGliding(false);
        }
        World world = p.getWorld();
        if (isExcludedDimension(world)) {
            return;
        }
        if (hasStopperMarkerNearby(p, 20.0)) {
            return;
        }
        boolean isRocket = wearsRocketArmor(p);

        boolean isGlider = wearsGlider(p);
        if (isGlider && !isRocket && !isElytra) {
            return;
        }
        if (!isRocket) {
            return;
        }

        // Apply rocket boost
        UUID id = p.getUniqueId();
        int rem = jumps.computeIfAbsent(id, ignored -> MAX_JUMPS);
        if (rem <= 0) {
            return;
        }
        rem--;
        jumps.put(id, rem);

        int currentCooldown = cooldown.computeIfAbsent(id, ignored -> 0);
        if (rem < MAX_JUMPS && currentCooldown <= 0) {
            cooldown.put(id, COOLDOWN_TICKS);
        }
        syncBootsGliderComponent(p, rem > 0);
        updateScores(p, rem, cooldown.getOrDefault(id, 0));

        Vector vel = p.getVelocity();
        Vector dir = p.getLocation().getDirection();
        if (vel.getY() > 0) {
            vel.setY(vel.getY() + 0.5);
        } else if (dir.getY() < 0) {
            vel.setY(0.5);
        }
        vel.add(dir);
        p.setVelocity(vel);

        World w = p.getWorld();
        w.spawnParticle(Particle.EXPLOSION, p.getLocation(), 1);
        w.spawnParticle(Particle.FLAME, p.getLocation(), 10, 0.5, 0.5, 0.5);
        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 2f);
    }

    private boolean hasStopperMarkerNearby(Player p, double radius) {
        World w = p.getWorld();
        for (Entity ent : w.getNearbyEntities(p.getLocation(), radius, radius, radius)) {
            if (ent.getType() == EntityType.MARKER && ent.getScoreboardTags().contains("stopper")) {
                return true;
            }
        }
        return false;
    }

    public boolean isExcludedDimension(World world) {
        if (world == null) {
            return false;
        }
        String dim = world.getKey().toString();
        return "minecraft:nexus".equals(dim) || "minecraft:imprinted".equals(dim);
    }

    /**
     * Get existing objective or register a new one on given scoreboard.
     */
    private Objective getOrCreateObjective(Scoreboard sb, String name, String criteria) {
        Objective obj = sb.getObjective(name);
        if (obj == null) {
            obj = sb.registerNewObjective(name, criteria);
        }
        return obj;
    }

    private boolean wearsRocketArmor(Player p) {
        return isRocketBoots(p.getInventory().getBoots());
    }

    private boolean wearsGlider(Player p) {
        ItemStack boots = p.getInventory().getBoots();
        return boots != null && boots.hasData(DataComponentTypes.GLIDER);
    }

    private boolean isRocketBoots(ItemStack boots) {
        if (boots == null || boots.getType().isAir()) {
            return false;
        }
        ItemMeta meta = boots.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return hasRocketType(pdc, pluginItemKey, pluginTypeKey, pluginAdditionalsKey, plainAdditionalsKey)
                || hasRocketType(pdc, lwsItemKey, lwsTypeKey, lwsAdditionalsKey, plainAdditionalsKey)
                || hasRocketType(pdc, legacyItemKey, legacyTypeKey, legacyAdditionalsKey, plainAdditionalsKey)
                || hasTagContainer(pdc, pluginGliderKey)
                || hasTagContainer(pdc, lwsGliderKey)
                || hasTagContainer(pdc, legacyGliderKey);
    }

    private boolean hasRocketType(
            PersistentDataContainer root,
            NamespacedKey itemTagKey,
            NamespacedKey typeTagKey,
            NamespacedKey... additionalsKeys) {
        if (!root.has(itemTagKey, PersistentDataType.TAG_CONTAINER)) {
            return false;
        }
        PersistentDataContainer nested = root.get(itemTagKey, PersistentDataType.TAG_CONTAINER);
        if (nested == null) {
            return false;
        }

        String type = nested.get(typeTagKey, PersistentDataType.STRING);
        if (isRocketType(type)) {
            return true;
        }

        for (NamespacedKey additionalsKey : additionalsKeys) {
            if (nested.has(additionalsKey, PersistentDataType.LIST.strings())) {
                List<String> additionals = nested.get(additionalsKey, PersistentDataType.LIST.strings());
                if (additionals != null) {
                    for (String value : additionals) {
                        if (isRocketType(value)) {
                            return true;
                        }
                    }
                }
            }

            if (nested.has(additionalsKey, PersistentDataType.STRING)) {
                String single = nested.get(additionalsKey, PersistentDataType.STRING);
                if (isRocketType(single)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isRocketType(String rawType) {
        if (rawType == null) {
            return false;
        }
        String normalized = rawType.toLowerCase(java.util.Locale.ROOT);
        return "rocket_armor".equals(normalized) || "rocketarmor".equals(normalized) || "rocket".equals(normalized);
    }

    private boolean hasTagContainer(PersistentDataContainer root, NamespacedKey key) {
        return root.has(key, PersistentDataType.TAG_CONTAINER);
    }

    private void syncBootsGliderComponent(Player player, boolean jumpAvailable) {
        ItemStack boots = player.getInventory().getBoots();
        if (!isRocketBoots(boots)) {
            return;
        }
        boolean hasGlider = boots.hasData(DataComponentTypes.GLIDER);
        if (jumpAvailable == hasGlider) {
            return;
        }
        if (jumpAvailable) {
            boots.setData(DataComponentTypes.GLIDER);
        } else {
            boots.unsetData(DataComponentTypes.GLIDER);
        }
        player.getInventory().setBoots(boots);
    }

    private boolean hasElytra(Player p) {
        ItemStack chest = p.getInventory().getChestplate();
        return chest != null && chest.getType() == Material.ELYTRA;
    }

}
