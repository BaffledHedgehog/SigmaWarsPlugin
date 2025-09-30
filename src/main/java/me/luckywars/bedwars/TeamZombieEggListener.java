package me.luckywars.bedwars;

import me.luckywars.bedwars.util.DyeColorOfTeam;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

public final class TeamZombieEggListener implements Listener {
    public TeamZombieEggListener(JavaPlugin plugin) {
        // ничего, просто держим ссылку если понадобится
    }

    @EventHandler(ignoreCancelled = true)
    public void onUseSpawnEgg(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK)
            return;

        ItemStack it = e.getItem();
        if (!isTeamZombieEgg(it))
            return;

        e.setCancelled(true); // гасим ванильный спавн

        Player p = e.getPlayer();
        Location loc = spawnLocation(e, p);

        // спавним настроенного зомби
        Zombie z = p.getWorld().spawn(loc, Zombie.class, ent -> {
            EntityEquipment eq = ent.getEquipment();
            if (eq != null) {
                eq.setHelmet(unbreakable(new ItemStack(Material.IRON_HELMET)));
                eq.setChestplate(unbreakable(new ItemStack(Material.IRON_CHESTPLATE)));
                eq.setLeggings(unbreakable(new ItemStack(Material.IRON_LEGGINGS)));
                eq.setBoots(unbreakable(new ItemStack(Material.IRON_BOOTS)));
                eq.setItemInMainHand(unbreakable(new ItemStack(Material.IRON_SWORD)));

                eq.setHelmetDropChance(0f);
                eq.setChestplateDropChance(0f);
                eq.setLeggingsDropChance(0f);
                eq.setBootsDropChance(0f);
                eq.setItemInMainHandDropChance(0f);
            }

            ent.setRemoveWhenFarAway(false); // чтоб не пропадал
        });

        // добавляем в команду игрока + цвет имени команды
        joinEntityToPlayersTeam(z, p);
        z.customName(Component.translatable("entity.minecraft.zombie").color(teamNamedColorOf(p)));
        z.setCustomNameVisible(true);

        // списываем яйцо
        if (it.getAmount() > 1) {
            it.setAmount(it.getAmount() - 1);
        } else {
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                p.getInventory().setItemInMainHand(null);
            } else {
                p.getInventory().setItemInOffHand(null);
            }
        }
    }

    private static boolean isTeamZombieEgg(@Nullable ItemStack it) {
        if (it == null || it.getType() != Material.ZOMBIE_SPAWN_EGG)
            return false;
        ItemMeta m = it.getItemMeta();
        if (m == null || !m.hasItemName())
            return false;
        Component name = m.itemName();
        if (name instanceof TranslatableComponent tc) {
            // мы задавали в GUI переводимый itemName("team_zombie")
            return "team_zombie".equals(tc.key());
        }
        return false;
    }

    private static ItemStack unbreakable(ItemStack it) {
        it.editMeta(meta -> meta.setUnbreakable(true));
        return it;
    }

    private static Location spawnLocation(PlayerInteractEvent e, Player p) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            return e.getClickedBlock().getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        }
        // чуть впереди игрока
        Location base = p.getLocation();
        return base.add(base.getDirection().normalize().multiply(1.5));
    }

    private static void joinEntityToPlayersTeam(org.bukkit.entity.Entity entity, Player p) {
        Scoreboard sb = p.getScoreboard();
        Team team = sb.getEntryTeam(p.getName());
        if (team == null) {
            Scoreboard main = Bukkit.getScoreboardManager() != null ? Bukkit.getScoreboardManager().getMainScoreboard()
                    : null;
            if (main != null)
                team = main.getEntryTeam(p.getName());
        }
        if (team != null) {
            team.addEntry(entity.getUniqueId().toString());
        }
        entity.addScoreboardTag("bw-owner:" + p.getUniqueId());
    }

    private static NamedTextColor teamNamedColorOf(Player p) {
        return DyeColorOfTeam.textColorOf(DyeColorOfTeam.dyeOf(p));
    }

}
