// src/main/java/com/govnoslav/NexusCloneCommand.java
package com.govnoslav;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.entity.TeleportFlag.EntityState;

public class NexusCloneCommand implements CommandExecutor {
    private static final int RADIUS = 5;  // copy 11×11×11

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // 1) Determine origin
        Location origin;
        World srcWorld;
        if (sender instanceof Player p) {
            origin = p.getLocation();
            srcWorld = p.getWorld();
        } else if (sender instanceof BlockCommandSender bcs) {
            origin = bcs.getBlock().getLocation();
            srcWorld = origin.getWorld();
        } else {
            if (Bukkit.getWorlds().isEmpty()) {
                //sender.sendMessage("[CloneToNexus] No worlds loaded.");
                return true;
            }
            srcWorld = Bukkit.getWorlds().get(0);
            origin = srcWorld.getSpawnLocation();
            //sender.sendMessage("[CloneToNexus] Using spawn of " + srcWorld.getName() + " as origin.");
        }
        if (srcWorld == null) {
            //sender.sendMessage("[CloneToNexus] Source world undefined.");
            return true;
        }

        // world height bounds
        int srcMinY = srcWorld.getMinHeight();
        int srcMaxY = srcWorld.getMaxHeight();

        // 2) Buffer blocks and containers
        int size = RADIUS * 2 + 1;
        Material[][][] buffer = new Material[size][size][size];
        Map<String, ItemStack[]> containerMap = new HashMap<>();
        int sx = origin.getBlockX() - RADIUS;
        int sy = origin.getBlockY() - RADIUS;
        int sz = origin.getBlockZ() - RADIUS;
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                int by = sy + dy;
                boolean inSrcY = (by >= srcMinY && by < srcMaxY);
                for (int dz = 0; dz < size; dz++) {
                    if (!inSrcY) {
                        buffer[dx][dy][dz] = Material.AIR;
                    } else {
                        Block b = srcWorld.getBlockAt(sx + dx, by, sz + dz);
                        buffer[dx][dy][dz] = b.getType();
                        BlockState st = b.getState();
                        if (st instanceof Container c) {
                            containerMap.put(dx + "," + dy + "," + dz,
                                    c.getSnapshotInventory().getContents().clone());
                        }
                    }
                }
            }
        }

        // 3) Find nexus world
        World nexus = null;
        for (World w : Bukkit.getWorlds()) {
            if (w.getName().toLowerCase().contains("nexus")) { nexus = w; break; }
        }
        if (nexus == null) {
            sender.sendMessage("[CloneToNexus] Nexus world not found (folder must contain 'nexus').");
            return true;
        }

        // 4) Choose random coords
        Random rand = new Random(UUID.randomUUID().getLeastSignificantBits());
        int tx = rand.nextInt(10001) - 5000;
        int ty = rand.nextInt(1981) + 20;
        int tz = rand.nextInt(10001) - 5000;
        sender.sendMessage("[CloneToNexus] Pasting to Nexus at (" + tx + "," + ty + "," + tz + ")");

        // 5) Paste blocks into nexus world
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                int by = ty + dy - RADIUS;
                for (int dz = 0; dz < size; dz++) {
                    Material mat = buffer[dx][dy][dz];
                    if (mat.isAir()) continue;
                    Block nb = nexus.getBlockAt(tx + dx - RADIUS, by, tz + dz - RADIUS);
                    nb.setType(mat, false);
                    String key = dx + "," + dy + "," + dz;
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

        // 6) Move entities via teleport
        double rad = RADIUS + 0.5;
        Collection<Entity> ents = srcWorld.getNearbyEntities(origin, rad, rad, rad);
        for (Entity e : ents) {
            if (e.getType() == EntityType.MARKER) continue;
            if (e instanceof Player) continue;
            Location loc = e.getLocation();
            double offsetX = loc.getX() - origin.getX();
            double offsetY = loc.getY() - origin.getY();
            double offsetZ = loc.getZ() - origin.getZ();
            double destY = ty + offsetY;
            Location dest = new Location(nexus,
                tx + offsetX, destY, tz + offsetZ,
                loc.getYaw(), loc.getPitch());
            nexus.getChunkAt((int)Math.floor(tx + offsetX) >> 4,
                             (int)Math.floor(tz + offsetZ) >> 4).load();
            e.teleport(dest, EntityState.RETAIN_PASSENGERS, EntityState.RETAIN_VEHICLE);
        }

        // 7) Clear source region blocks only
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                int by = sy + dy;
                if (by < srcMinY || by >= srcMaxY) continue;
                for (int dz = 0; dz < size; dz++) {
                    Block b = srcWorld.getBlockAt(sx + dx, by, sz + dz);
                    b.setType(Material.AIR, false);
                }
            }
        }


        // 8) Place markers and unload chunk
        int cx = tx;
        int cz = tz;
        int blockY = ty - 1;
        Chunk chunk = nexus.getChunkAt(cx >> 4, cz >> 4);
        chunk.load();
        Block ground = nexus.getBlockAt(cx, blockY, cz);
        if (!ground.getType().isSolid()) ground.setType(Material.STONE, false);
        Location markerLoc = new Location(nexus, cx + 0.5, blockY + 0.5, cz + 0.5);
        Entity center = nexus.spawnEntity(markerLoc, EntityType.MARKER);
        center.addScoreboardTag("center_cube");
        center.addScoreboardTag("nexus_rc");
        for (int i = 0; i < 8; i++) {
            Entity tree = nexus.spawnEntity(markerLoc, EntityType.MARKER);
            tree.addScoreboardTag("nexus_tree");
            tree.addScoreboardTag("nexus_rc");
        }

        // 9) Save island coords
        try {
            JavaPlugin plugin = JavaPlugin.getPlugin(SigmaWarsMain.class);
            File file = new File(plugin.getDataFolder(), "nexus_coords.json");
            if (!file.exists()) {
                plugin.getDataFolder().mkdirs();
                try (FileWriter w = new FileWriter(file)) { w.write("[]"); }
            }
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
            int ox = tx + 5, oy = ty + 20, oz = tz + 5;
            String entry = String.format("{\"x\":%d,\"y\":%d,\"z\":%d}", ox, oy, oz);
            String newContent = content.equals("[]") ? "[" + entry + "]"
                : content.substring(0, content.length()-1) + "," + entry + "]";
            Files.writeString(file.toPath(), newContent, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            //sender.sendMessage("[CloneToNexus] Failed to save coords: " + ex.getMessage());
        }

        chunk.unload(true);
        // Release references and suggest GC
        buffer = null;
        containerMap.clear();
        ents = null;
        center = null;
        markerLoc = null;
        chunk = null;
        ground = null;
        System.gc();
        

        return true;
    }
}
