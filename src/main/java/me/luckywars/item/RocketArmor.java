package me.luckywars.item;

import java.util.HashMap;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

public class RocketArmor implements Listener {
    private final NamespacedKey itemKey;
    private final NamespacedKey gliderKey;

    private static final String JUMPS_OBJ_NAME = "rocket_armor_jumps";
    private static final String CD_OBJ_NAME = "rocket_armor_jump_cd";
    private static final int MAX_JUMPS = 3;
    private static final int COOLDOWN_TICKS = 20 * 5;

    private final Map<UUID, Integer> jumps = new HashMap<>();
    private final Map<UUID, Integer> cooldown = new HashMap<>();

    public RocketArmor(JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        this.gliderKey = new NamespacedKey(plugin, "glider");

        // Periodic task updates stats and ensures objectives exist on each player's
        // scoreboard
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
                        continue;
                    }
                    if (!wearsRocketArmor(p)) {
                        continue;
                    }

                    UUID id = p.getUniqueId();
                    jumps.putIfAbsent(id, MAX_JUMPS);
                    cooldown.putIfAbsent(id, 0);

                    int cd = cooldown.get(id);
                    if (cd > 0) {
                        cooldown.put(id, cd - 1);
                    } else if (jumps.get(id) < MAX_JUMPS) {
                        jumps.put(id, jumps.get(id) + 1);
                        if (jumps.get(id) < MAX_JUMPS) {
                            cooldown.put(id, COOLDOWN_TICKS);
                        }
                    }

                    // Use player's own scoreboard to handle dynamic scoreboard changes
                    Scoreboard sb = p.getScoreboard();
                    Objective jumpsObj = getOrCreateObjective(sb, JUMPS_OBJ_NAME, "dummy");
                    Objective cdObj = getOrCreateObjective(sb, CD_OBJ_NAME, "dummy");

                    // Update scores
                    jumpsObj.getScore(p.getName()).setScore(jumps.get(id));
                    int secs = (cooldown.get(id) + 19) / 20;
                    cdObj.getScore(p.getName()).setScore(secs);
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && wearsRocketArmor(p)) {
            UUID id = p.getUniqueId();
            jumps.put(id, MAX_JUMPS);
            cooldown.put(id, 0);
        }
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

        boolean isRocket = wearsRocketArmor(p);
        boolean isElytra = hasElytra(p);
        boolean isGlider = wearsGlider(p);
        if (isGlider && !isRocket && !isElytra) {
            return;
        }
        if (!isRocket) {
            return;
        }

        // Cancel glide start if not using elytra to trigger rocket boost
        if (!isElytra) {
            e.setCancelled(true);
            p.setGliding(false);
        }

        // Apply rocket boost
        UUID id = p.getUniqueId();
        int rem = jumps.getOrDefault(id, MAX_JUMPS);
        if (rem <= 0) {
            return;
        }
        jumps.put(id, rem - 1);
        if (cooldown.get(id) == 0) {
            cooldown.put(id, COOLDOWN_TICKS);
        }

        Vector vel = p.getVelocity();
        Vector dir = p.getLocation().getDirection();
        if (vel.getY() > 0) {
            vel.setY(vel.getY() + 0.5);
        } else if (dir.getY() < 0) {
            vel.setY(0.25);
        }
        vel.add(dir);
        p.setVelocity(vel);

        World w = p.getWorld();
        w.spawnParticle(Particle.EXPLOSION, p.getLocation(), 1);
        w.spawnParticle(Particle.FLAME, p.getLocation(), 10, 0.5, 0.5, 0.5);
        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 2f);
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
        return checkPDC(p, itemKey);
    }

    private boolean wearsGlider(Player p) {
        return checkPDC(p, gliderKey);
    }

    private boolean checkPDC(Player p, NamespacedKey key) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s;
            try {
                s = p.getInventory().getItem(slot);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (s == null || s.getItemMeta() == null) {
                continue;
            }
            PersistentDataContainer pdc = s.getItemMeta().getPersistentDataContainer();
            if (pdc.has(key, PersistentDataType.TAG_CONTAINER)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasElytra(Player p) {
        ItemStack chest = p.getInventory().getChestplate();
        return chest != null && chest.getType() == Material.ELYTRA;
    }
}
