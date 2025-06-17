// src/main/java/com/govnoslav/item/RocketArmor.java
package com.govnoslav.item;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.util.Vector;

public class RocketArmor implements Listener {
    private final NamespacedKey itemKey;
    private final Objective jumpsObj;
    private final Objective cdObj;

    private static final int MAX_JUMPS = 3;
    private static final int COOLDOWN_TICKS = 100; // ticks to recharge

    private final Map<UUID, Integer> jumps = new HashMap<>();
    private final Map<UUID, Integer> cooldown = new HashMap<>();
    private final Map<UUID, Boolean> jumpedInAir = new HashMap<>();

    public RocketArmor(JavaPlugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "item");
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        Scoreboard board = mgr.getMainScoreboard();
        this.jumpsObj = board.getObjective("rocket_armor_jumps");
        this.cdObj = board.getObjective("rocket_armor_jump_cd");

        // Tick task: recharge charges and update scoreboard
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                    if (!wearsRocketArmor(p)) continue;
                    UUID id = p.getUniqueId();

                    // Initialize if absent
                    jumps.putIfAbsent(id, MAX_JUMPS);
                    cooldown.putIfAbsent(id, 0);

                    boolean onGround = p.isOnGround();
                    if (onGround) {
                        // Reset on land
                        jumpedInAir.put(id, false);
                        jumps.put(id, MAX_JUMPS);
                        cooldown.put(id, 0);
                    } else if (!jumpedInAir.getOrDefault(id, false)) {
                        // Recharge mid-air before first jump
                        int cd = cooldown.get(id);
                        if (cd > 0) {
                            cd--;
                            cooldown.put(id, cd);
                        }
                        if (cd == 0 && jumps.get(id) < MAX_JUMPS) {
                            jumps.put(id, jumps.get(id) + 1);
                        }
                    }

                    // Update scoreboard
                    if (jumpsObj != null) {
                        jumpsObj.getScore(p.getName()).setScore(jumps.get(id));
                    }
                    if (cdObj != null) {
                        int secs = (cooldown.get(id) + 19) / 20;
                        cdObj.getScore(p.getName()).setScore(secs);
                    }
                }
            }
        }.runTaskTimer(plugin, 1, 1);

        // Event listener is registered in main plugin onEnable
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && wearsRocketArmor(p)) {
            UUID id = p.getUniqueId();
            jumps.put(id, MAX_JUMPS);
            cooldown.put(id, 0);
            jumpedInAir.put(id, false);
        }
    }

    @EventHandler
    public void onToggleGlide(EntityToggleGlideEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        if (!wearsRocketArmor(p)) return;
        if (!e.isGliding()) return;

        e.setCancelled(true);
        p.setGliding(false);
        UUID id = p.getUniqueId();
        int rem = jumps.getOrDefault(id, MAX_JUMPS);
        if (rem <= 0) return;

        // Consume jump and start cooldown
        jumpedInAir.put(id, true);
        jumps.put(id, rem - 1);
        cooldown.put(id, COOLDOWN_TICKS);

        // Apply rocket impulse
        Vector vel = p.getVelocity();
        Vector dir = p.getLocation().getDirection();
        if (vel.getY() > 0) {
            vel.setY(vel.getY() + 0.5);
        } else if (dir.getY() < 0) {
            vel.setY(0.25);
        }
        vel.add(dir.multiply(1.0));
        p.setVelocity(vel);

        World w = p.getWorld();
        w.spawnParticle(Particle.EXPLOSION, p.getLocation(), 1);
        w.spawnParticle(Particle.FLAME, p.getLocation(), 10, 0.5, 0.5, 0.5);
        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 2f);

        // Immediate scoreboard update
        if (jumpsObj != null) {
            jumpsObj.getScore(p.getName()).setScore(jumps.get(id));
        }
        if (cdObj != null) {
            int secs = (cooldown.get(id) + 19) / 20;
            cdObj.getScore(p.getName()).setScore(secs);
        }
    }

    private boolean wearsRocketArmor(Player p) {
        PlayerInventory inv = p.getInventory();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = inv.getItem(slot);
            if (s == null || s.getItemMeta() == null) continue;
            if (s.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.TAG_CONTAINER)) {
                return true;
            }
        }
        return false;
    }
}
