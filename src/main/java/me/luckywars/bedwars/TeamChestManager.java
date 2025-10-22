package me.luckywars.bedwars;

import me.luckywars.bedwars.util.DyeColorOfTeam;
import me.luckywars.bedwars.util.ItemTag;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TeamChestManager implements Listener {

    private final Map<String, String> chestAt = new HashMap<>(); // pos -> team
    private final Map<String, Inventory> teamInv = new HashMap<>(); // team -> inv
    private final Map<String, UUID> displayAt = new HashMap<>(); // pos -> BlockDisplay UUID

    private static String keyOf(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private Inventory invFor(Team team) {
        return teamInv.computeIfAbsent(team.getName(),
                k -> Bukkit.createInventory(null, 54, net.kyori.adventure.text.Component.translatable("team_chest")));
    }

    /* ===== helpers for BlockDisplay ===== */

    private void removeMarker(String posKey) {
        UUID id = displayAt.remove(posKey);
        if (id != null) {
            var ent = Bukkit.getEntity(id);
            if (ent != null && !ent.isDead())
                ent.remove();
        }
    }

    private void spawnMarker(Block b, DyeColor dc) {
        final String key = keyOf(b);
        removeMarker(key); // на всякий

        final Location loc = b.getLocation(); // align xyz; positioned ~.5 ~ ~.5
        final Material glass = DyeColorOfTeam.stainedGlassOf(dc);

        BlockDisplay bd = b.getWorld().spawn(loc, BlockDisplay.class, d -> {
            d.setBlock(glass.createBlockData());
            // размер ровно 1 без дополнительных трансформаций
            d.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new Quaternionf(),
                    new Vector3f(1, 1, 1),
                    new Quaternionf()));
        });
        displayAt.put(key, bd.getUniqueId());
    }

    /* ================= events ================= */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (hand == null || !ItemTag.isTeamChest(hand))
            return;
        if (e.getBlockPlaced().getType() != Material.ENDER_CHEST)
            return;

        Team team = DyeColorOfTeam.teamOf(e.getPlayer());
        if (team == null)
            return;

        final Block placed = e.getBlockPlaced();
        final String key = keyOf(placed);

        chestAt.put(key, team.getName());
        try {
            DyeColor dc = DyeColorOfTeam.dyeOf(e.getPlayer());
            spawnMarker(placed, dc);
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND)
            return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK)
            return; // только ПКМ открывает GUI
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.ENDER_CHEST)
            return;

        String teamName = chestAt.get(keyOf(b));
        if (teamName == null)
            return;

        Team t = DyeColorOfTeam.teamOf(e.getPlayer());
        if (t == null || !t.getName().equals(teamName)) {
            e.setCancelled(true);
            return;
        }

        e.setCancelled(true);
        e.getPlayer().openInventory(invFor(t));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onBreak(BlockBreakEvent e) {
        String key = keyOf(e.getBlock());
        String teamName = chestAt.get(key);
        if (teamName == null)
            return;

        // удалить маркер
        removeMarker(key);

        // выдать именно "team chest"
        e.setDropItems(false);
        ItemStack drop = new ItemStack(Material.ENDER_CHEST);
        ItemTag.markTeamChest(drop);
        drop.editMeta(m -> m.displayName(net.kyori.adventure.text.Component.translatable("team_chest")));
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), drop);

        chestAt.remove(key);
    }
}
