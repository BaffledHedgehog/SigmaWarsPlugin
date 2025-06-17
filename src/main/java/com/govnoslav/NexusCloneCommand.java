// src/main/java/com/govnoslav/command/NexusCloneCommand.java
package com.govnoslav;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class NexusCloneCommand implements CommandExecutor {
    private static final int RADIUS = 5;  // copy 11×11×11

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // Determine origin
        Location origin;
        World srcWorld;
        if (sender instanceof Player p) {
            origin = p.getLocation();
            srcWorld = p.getWorld();
        } else if (sender instanceof BlockCommandSender bcs) {
            origin = bcs.getBlock().getLocation();
            srcWorld = origin.getWorld();
        } else {
            // fallback to first world spawn
            if (Bukkit.getServer().getWorlds().isEmpty()) {
                sender.sendMessage("[CloneToNexus] No worlds loaded.");
                return true;
            }
            srcWorld = Bukkit.getServer().getWorlds().get(0);
            origin = srcWorld.getSpawnLocation();
            sender.sendMessage("[CloneToNexus] Using spawn of " + srcWorld.getName() + " as origin.");
        }
        if (srcWorld == null) {
            sender.sendMessage("[CloneToNexus] Source world undefined.");
            return true;
        }

        // Buffer blocks and containers
        int size = RADIUS * 2 + 1;
        Material[][][] buffer = new Material[size][size][size];
        Map<String, ItemStack[]> containerMap = new HashMap<>();
        int sx = origin.getBlockX() - RADIUS;
        int sy = origin.getBlockY() - RADIUS;
        int sz = origin.getBlockZ() - RADIUS;
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    Block b = srcWorld.getBlockAt(sx + dx, sy + dy, sz + dz);
                    buffer[dx][dy][dz] = b.getType();
                    BlockState st = b.getState();
                    if (st instanceof Container c) {
                        containerMap.put(dx+","+dy+","+dz, c.getSnapshotInventory().getContents().clone());
                    }
                }
            }
        }

        // Find nexus world (folder containing 'nexus')
        World nexus = null;
        for (World w : Bukkit.getServer().getWorlds()) {
            if (w.getName().toLowerCase().contains("nexus")) {
                nexus = w;
                break;
            }
        }
        if (nexus == null) {
            sender.sendMessage("[CloneToNexus] Nexus world not found (world folder must contain 'nexus').");
            return true;
        }

        // Random target coordinates
        Random rand = new Random(UUID.randomUUID().getLeastSignificantBits());
        int tx = rand.nextInt(10001) - 5000;
        int ty = rand.nextInt(1981) + 20;
        int tz = rand.nextInt(10001) - 5000;
        sender.sendMessage("[CloneToNexus] Pasting to Nexus at (" + tx + "," + ty + "," + tz + ")");

        // Paste blocks skipping air
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    Material mat = buffer[dx][dy][dz];
                    if (mat.isAir()) continue;
                    Block nb = nexus.getBlockAt(tx + dx - RADIUS, ty + dy - RADIUS, tz + dz - RADIUS);
                    nb.setType(mat, false);
                    String key = dx+","+dy+","+dz;
                    if (containerMap.containsKey(key)) {
                        BlockState nst = nb.getState();
                        if (nst instanceof Container nc) {
                            nc.getSnapshotInventory().setContents(containerMap.get(key));
                            nst.update(true, false);
                        }
                    }
                }
            }
        }

        // Clone entities except players, override flags
        double radius = RADIUS + 0.5;
        Collection<Entity> ents = srcWorld.getNearbyEntities(origin, radius, radius, radius);
        for (Entity e : ents) {
            if (e instanceof Player) continue;
            Location loc = e.getLocation();
            Location dest = new Location(nexus,
                tx + (loc.getX() - origin.getX()),
                ty + (loc.getY() - origin.getY()),
                tz + (loc.getZ() - origin.getZ()),
                loc.getYaw(), loc.getPitch());
            Entity spawned = nexus.spawnEntity(dest, e.getType());
            if (spawned instanceof LivingEntity le) le.setAI(false);
            spawned.setInvulnerable(false);
            spawned.setGravity(false);
            spawned.setVelocity(e.getVelocity());
        }

        // Place markers under center
        int cx = tx;
        int cy = ty - RADIUS - 1;
        int cz = tz;
        // Ensure chunk containing center is loaded
        nexus.getChunkAt(cx >> 4, cz >> 4).load();
        // Make sure below center is solid
        Block below = nexus.getBlockAt(cx, cy, cz);
        if (!below.getType().isSolid()) below.setType(Material.STONE, false);
        // Spawn markers at center of block
        Location markerLoc = new Location(nexus, cx + 0.5, cy + 1, cz + 0.5);
        // Center marker
        Entity center = nexus.spawnEntity(markerLoc, EntityType.MARKER);
        center.addScoreboardTag("center_cube");
        center.addScoreboardTag("nexus_rc");
        // Tree markers (8 of same position)
        for (int i = 0; i < 8; i++) {
            Entity tree = nexus.spawnEntity(markerLoc, EntityType.MARKER);
            tree.addScoreboardTag("nexus_tree");
            tree.addScoreboardTag("nexus_rc");
        }

        return true;
    }
}