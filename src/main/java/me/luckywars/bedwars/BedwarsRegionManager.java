package me.luckywars.bedwars;

//import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class BedwarsRegionManager {
    private final JavaPlugin plugin;

    private UUID worldId = null;
    private int minX, maxX, minZ, maxZ;
    private boolean enabled = false;

    // Снимок типов блоков (только НЕ-воздух)
    private final Map<Long, Material> snapshot = new HashMap<>();

    public BedwarsRegionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void initAt(Location center) {
        World w = center.getWorld();
        if (w == null)
            return;
        worldId = w.getUID();
        int cx = center.getBlockX(), cz = center.getBlockZ();
        minX = cx - 384;
        maxX = cx + 384;
        minZ = cz - 384;
        maxZ = cz + 384;

        snapshot.clear();

        int minChunkX = floorDiv(minX, 16), maxChunkX = floorDiv(maxX, 16);
        int minChunkZ = floorDiv(minZ, 16), maxChunkZ = floorDiv(maxZ, 16);
        int wyMin = w.getMinHeight();
        int wyMax = w.getMaxHeight() - 1;

        for (int cX = minChunkX; cX <= maxChunkX; cX++) {
            for (int cZ = minChunkZ; cZ <= maxChunkZ; cZ++) {
                // Chunk ch = w.getChunkAt(cX, cZ);
                int bx0 = Math.max(minX, cX * 16), bx1 = Math.min(maxX, cX * 16 + 15);
                int bz0 = Math.max(minZ, cZ * 16), bz1 = Math.min(maxZ, cZ * 16 + 15);

                for (int x = bx0; x <= bx1; x++) {
                    for (int z = bz0; z <= bz1; z++) {
                        for (int y = wyMin; y <= wyMax; y++) {
                            Block b = w.getBlockAt(x, y, z);
                            Material m = b.getType();
                            if (!m.isAir())
                                snapshot.put(pack(x, y, z), m);
                        }
                    }
                }
            }
        }

        enabled = true;
        save();
        plugin.getLogger().info("[Bedwars] Snapshot captured: " + snapshot.size() + " blocks.");
    }

    public void stop() {
        enabled = false;
        snapshot.clear();
        save();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInside(Block b) {
        if (!enabled || b == null || worldId == null)
            return false;
        if (!b.getWorld().getUID().equals(worldId))
            return false;
        int x = b.getX(), z = b.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean isSnapshottedCoord(int x, int y, int z) {
        return enabled && snapshot.containsKey(pack(x, y, z));
    }

    public Material originalTypeAt(Block b) {
        return snapshot.get(pack(b.getX(), b.getY(), b.getZ()));
    }

    public boolean isOriginalTypeBlock(Block b) {
        Material orig = originalTypeAt(b);
        return orig != null && b.getType() == orig;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a))
            r--;
        return r;
    }

    private void save() {
        FileConfiguration c = plugin.getConfig();
        c.set("bedwars.enabled", enabled);
        c.set("bedwars.world", worldId == null ? null : worldId.toString());
        c.set("bedwars.minX", minX);
        c.set("bedwars.maxX", maxX);
        c.set("bedwars.minZ", minZ);
        c.set("bedwars.maxZ", maxZ);
        plugin.saveConfig();
    }

    private void load() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("bedwars.enabled", false);
        String w = c.getString("bedwars.world", null);
        worldId = (w == null ? null : UUID.fromString(w));
        minX = c.getInt("bedwars.minX", 0);
        maxX = c.getInt("bedwars.maxX", -1);
        minZ = c.getInt("bedwars.minZ", 0);
        maxZ = c.getInt("bedwars.maxZ", -1);
        snapshot.clear();
    }

    // === BedwarsRegionManager.java ===

    // Снять защиту со снимка для конкретной координаты (используется при сломе
    // чужой кровати вручную)
    public void forgetSnapshotAt(int x, int y, int z) {
        if (!enabled)
            return;
        snapshot.remove(pack(x, y, z));
    }

    // Удобный оверлоад для Block
    public void forgetSnapshotAt(Block b) {
        if (b == null)
            return;
        forgetSnapshotAt(b.getX(), b.getY(), b.getZ());
    }

}
