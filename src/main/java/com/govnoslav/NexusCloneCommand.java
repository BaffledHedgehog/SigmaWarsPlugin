package com.govnoslav;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.fastasyncworldedit.core.extent.clipboard.WorldCopyClipboard;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.entity.TeleportFlag.EntityState;

/**
 * Clones the entire chunk around the command origin (including /execute at) to Nexus.
 */
public class NexusCloneCommand implements BasicCommand {
    private final JavaPlugin plugin;
    private static final int SIZE = 16;
    private static final int HALF = SIZE / 2;
    private final Random random = new Random();
    private final Set<String> pendingChunks = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> tasks = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor;
    private final ExecutorCompletionService<Void> completionService;
    private final Object jsonLock = new Object();
    private final Gson gson = new Gson();

    public NexusCloneCommand(JavaPlugin plugin) {
        this.plugin = plugin;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(threads);
        this.completionService = new ExecutorCompletionService<>(executor);

        // Cleanup completed tasks periodically
        new BukkitRunnable() {
            @Override
            public void run() {
                tasks.removeIf(Future::isDone);
            }
        }.runTaskTimer(plugin, 20 * 60, 20 * 60);
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        // stop command
        if (args.length > 0 && "stop".equalsIgnoreCase(args[0])) {
            for (Future<?> f : tasks) f.cancel(true);
            tasks.clear();
            pendingChunks.clear();
            return;
        }

        final Location origin = src.getLocation();
        final World srcWorld = origin.getWorld();
        if (srcWorld == null) return;

        final int cx = origin.getBlockX() >> 4;
        final int cz = origin.getBlockZ() >> 4;
        final String key = cx + "," + cz;
        if (!pendingChunks.add(key)) return;

        final World nexus = Bukkit.getWorlds().stream()
            .filter(w -> w.getName().toLowerCase().contains("nexus"))
            .findFirst().orElse(null);
        if (nexus == null) {
            pendingChunks.remove(key);
            return;
        }

        final int bound = 5000 / SIZE;
        final int dx = random.nextInt(bound * 2 + 1) - bound;
        final int dz2 = random.nextInt(bound * 2 + 1) - bound;
        final int tx = (dx << 4) + HALF;
        final int tz = (dz2 << 4) + HALF;
        final int ty = random.nextInt(srcWorld.getMaxHeight() - srcWorld.getMinHeight()) + srcWorld.getMinHeight();

        Future<?> future = completionService.submit(() -> {
            try {
                // Phase 1: copy
                var weSrc = BukkitAdapter.adapt(srcWorld);
                var weDst = BukkitAdapter.adapt(nexus);
                BlockVector3 min = BlockVector3.at(cx << 4, srcWorld.getMinHeight(), cz << 4);
                BlockVector3 max = BlockVector3.at((cx << 4) + SIZE - 1, srcWorld.getMaxHeight() - 1, (cz << 4) + SIZE - 1);
                var region = new CuboidRegion(weSrc, min, max);
                var clipboard = WorldCopyClipboard.of(() -> weSrc, region);
                clipboard.setOrigin(min);
                ClipboardHolder holder = new ClipboardHolder(clipboard);
                try (EditSession session = WorldEdit.getInstance()
                        .newEditSessionBuilder().world(weDst).maxBlocks(Integer.MAX_VALUE).build()) {
                    Operations.complete(holder.createPaste(session)
                        .to(BlockVector3.at(tx - HALF, ty - HALF, tz - HALF))
                        .ignoreAirBlocks(false).build());
                }

                // Phase 2: clear source
                try (EditSession clear = WorldEdit.getInstance()
                        .newEditSessionBuilder().world(weSrc).maxBlocks(Integer.MAX_VALUE).build()) {
                    clear.setBlocks(region, com.sk89q.worldedit.world.block.BlockTypes.AIR.getDefaultState());
                }

                // Phase 3: finalize on main thread
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            Chunk srcC = srcWorld.getChunkAt(cx, cz);
                            for (var e : srcC.getEntities()) {
                                if (e instanceof Player || e.getType() == EntityType.MARKER) continue;
                                Location loc = e.getLocation();
                                e.teleport(new Location(nexus,
                                    tx + (loc.getX() - origin.getX()),
                                    ty + (loc.getY() - origin.getY()),
                                    tz + (loc.getZ() - origin.getZ()),
                                    loc.getYaw(), loc.getPitch()),
                                    EntityState.RETAIN_PASSENGERS, EntityState.RETAIN_VEHICLE);
                            }
                            // load destination chunk
                            Chunk dstC = nexus.getChunkAt(tx >> 4, tz >> 4);
                            dstC.load();

                            // place 3 center markers
                            for (int i = 0; i < 3; i++) {
                                int ax = tx - HALF + random.nextInt(SIZE);
                                int az = tz - HALF + random.nextInt(SIZE);
                                int ay = nexus.getHighestBlockYAt(ax, az);
                                if (!nexus.getBlockAt(ax, ay - 1, az).getType().isSolid()) {
                                    nexus.getBlockAt(ax, ay - 1, az).setType(Material.PURPLE_CONCRETE, false);
                                }
                                Location markerLoc = new Location(nexus, ax + 0.5, ay + 0.5, az + 0.5);
                                Entity centerMarker = nexus.spawnEntity(markerLoc, EntityType.MARKER);
                                centerMarker.addScoreboardTag("center_cube");
                                centerMarker.addScoreboardTag("nexus_rc");
                            }

                            // place 16 vertical tree markers
                            int minY = nexus.getMinHeight();
                            int maxY = nexus.getMaxHeight() - 1;
                            double mx = tx + 0.5;
                            double mz = tz + 0.5;
                            for (int i = 0; i < 16; i++) {
                                double yy = minY + (maxY - minY) * i / 15.0 + 0.5;
                                Location treeLoc = new Location(nexus, mx, yy, mz);
                                Entity treeMarker = nexus.spawnEntity(treeLoc, EntityType.MARKER);
                                treeMarker.addScoreboardTag("nexus_tree");
                                treeMarker.addScoreboardTag("nexus_rc");
                            }
                            dstC.unload(true);
                            srcC.unload(true);
                        } finally {
                            pendingChunks.remove(key);
                        }
                    }
                }.runTask(plugin);

                // Phase 4: save coords safely
                String entry = String.format("{\"x\":%d,\"y\":%d,\"z\":%d}", tx + 5, ty + 321, tz + 5);
                synchronized (jsonLock) {
                    File file = new File(plugin.getDataFolder(), "nexus_coords.json");
                    if (!file.exists()) {
                        plugin.getDataFolder().mkdirs();
                        try (var w = new java.io.FileWriter(file)) { w.write("[]"); }
                    }
                    Type listType = new TypeToken<List<Map<String, Number>>>() {}.getType();
                    List<Map<String, Number>> list;
                    try {
                        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                        list = gson.fromJson(content, listType);
                        if (list == null) list = new ArrayList<>();
                    } catch (IOException ex) {
                        list = new ArrayList<>();
                    }
                    Map<String, Number> map = gson.fromJson(entry, Map.class);
                    list.add(map);
                    try (var w = new java.io.FileWriter(file)) {
                        w.write(gson.toJson(list));
                    }
                }

            } catch (Throwable ex) {
                pendingChunks.remove(key);
            }
            return null;
        });
        tasks.add(future);
    }

    /**
     * Call this on plugin disable to clean up executor threads.
     */
    public void disable() {
        executor.shutdownNow();
    }
}
