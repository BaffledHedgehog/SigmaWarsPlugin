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
import org.bukkit.persistence.PersistentDataContainer;
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
    private static final int COOLDOWN_TICKS = 20 * 5; // 5 seconds

    private final Map<UUID, Integer> jumps = new HashMap<>();
    private final Map<UUID, Integer> cooldown = new HashMap<>();

    public RocketArmor(JavaPlugin plugin) {
        //this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        Scoreboard board = mgr.getMainScoreboard();
        this.jumpsObj = board.getObjective("rocket_armor_jumps");
        this.cdObj = board.getObjective("rocket_armor_jump_cd");

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE)
                        continue;
                    if (!wearsRocketArmor(p))
                        continue;
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
        if (!(e.getEntity() instanceof Player))
            return;
        Player p = (Player) e.getEntity();
        if (!wearsRocketArmor(p))
            return;
        if (!e.isGliding())
            return;

        e.setCancelled(true);
        p.setGliding(false);
        UUID id = p.getUniqueId();
        int rem = jumps.getOrDefault(id, MAX_JUMPS);
        if (rem <= 0)
            return;

        //jumpedInAir.put(id, true);
        jumps.put(id, rem - 1);
        if (cooldown.get(id) == 0) {
            cooldown.put(id, COOLDOWN_TICKS);
        }

        Vector vel = p.getVelocity();
        Vector dir = p.getLocation().getDirection();
        if (vel.getY() > 0)
            vel.setY(vel.getY() + 0.5);
        else if (dir.getY() < 0)
            vel.setY(0.25);
        vel.add(dir.multiply(1.0));
        p.setVelocity(vel);

        World w = p.getWorld();
        w.spawnParticle(Particle.EXPLOSION, p.getLocation(), 1);
        w.spawnParticle(Particle.FLAME, p.getLocation(), 10, 0.5, 0.5, 0.5);
        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 2f);

        if (jumpsObj != null)
            jumpsObj.getScore(p.getName()).setScore(jumps.get(id));
        if (cdObj != null)
            cdObj.getScore(p.getName()).setScore((cooldown.get(id) + 19) / 20);
    }

    private boolean wearsRocketArmor(Player p) {
        PlayerInventory inv = p.getInventory();
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET
        }) {
            ItemStack s;
            try {
                s = inv.getItem(slot);
            } catch (IllegalArgumentException ex) {
                //plugin.getLogger().warning("Cannot get slot " + slot + ": " + ex.getMessage());
                continue;
            }
            if (s == null || s.getItemMeta() == null)
                continue;
            PersistentDataContainer pdc = s.getItemMeta().getPersistentDataContainer();
            // выводим все ключи
            //pdc.getKeys().forEach(k -> plugin.getLogger().info("PDC key in " + slot + ": " + k));
            // проверяем контейнер lws:item
            if (pdc.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
                //plugin.getLogger().info("Found PDC lws:item in " + slot);
                //PersistentDataContainer sub = pdc.get(itemKey, PersistentDataType.TAG_CONTAINER);
                //if (sub != null) {
                //    sub.getKeys().forEach(sk -> plugin.getLogger().info(" subkey: " + sk));
                //}
                return true;
            }
        }
        return false;
    }
}
