package me.luckywars;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;

import java.util.Objects;

/**
 * /pulsar — запускает цепной эффект поедания блоков 5-ю волнами:
 * 1) WHITE_CONCRETE
 * 2) LIME_CONCRETE
 * 3) RED_CONCRETE
 * 4) BLACK_CONCRETE
 * 5) AIR
 *
 * Каждая следующая волна начинается через 2 сек (40 тиков) после окончания
 * предыдущей.
 */
public final class PulsarCommand implements BasicCommand {

    private final Plugin plugin;

    public PulsarCommand(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        final Location loc = source.getLocation();
        final World world = loc.getWorld();
        if (world == null) {
            source.getSender().sendMessage(Component.text("§cМир неизвестен для источника команды."));
            return;
        }
        final BlockVector3 origin = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // Wave #1 (white) — сразу
        new WaveRunner(plugin, world, origin,
                (mat) -> !mat.isAir() && mat != Material.WHITE_CONCRETE
                        && mat != Material.LIME_CONCRETE
                        && mat != Material.RED_CONCRETE
                        && mat != Material.BLACK_CONCRETE,
                BlockTypes.WHITE_CONCRETE.getDefaultState(),
                (mat) -> !mat.isAir() && mat != Material.WHITE_CONCRETE
                        && mat != Material.LIME_CONCRETE
                        && mat != Material.RED_CONCRETE
                        && mat != Material.BLACK_CONCRETE,
                "Wave #1 (white)").runTaskTimer(plugin, 0L, 1L);

        // Wave #2 (lime) — через 2 сек
        new WaveRunner(plugin, world, origin,
                (mat) -> !mat.isAir() && mat != Material.LIME_CONCRETE
                        && mat != Material.RED_CONCRETE && mat != Material.BLACK_CONCRETE,
                BlockTypes.LIME_CONCRETE.getDefaultState(),
                (mat) -> !mat.isAir() && mat != Material.LIME_CONCRETE
                        && mat != Material.RED_CONCRETE && mat != Material.BLACK_CONCRETE,
                "Wave #2 (lime)").runTaskTimer(plugin, 40, 1L);

        // Wave #3 (red) — через 4 сек
        new WaveRunner(plugin, world, origin,
                (mat) -> !mat.isAir() && mat != Material.RED_CONCRETE && mat != Material.BLACK_CONCRETE,
                BlockTypes.RED_CONCRETE.getDefaultState(),
                (mat) -> !mat.isAir() && mat != Material.RED_CONCRETE && mat != Material.BLACK_CONCRETE,
                "Wave #3 (red)").runTaskTimer(plugin, 60, 1L);

        // Wave #4 (black) — через 6 сек
        new WaveRunner(plugin, world, origin,
                (mat) -> !mat.isAir() && mat != Material.BLACK_CONCRETE,
                BlockTypes.BLACK_CONCRETE.getDefaultState(),
                (mat) -> !mat.isAir() && mat != Material.BLACK_CONCRETE,
                "Wave #4 (black)").runTaskTimer(plugin, 70, 1L);

        // Wave #5 (air) — через 8 сек
        new WaveRunner(plugin, world, origin,
                (mat) -> !mat.isAir(),
                BlockTypes.AIR.getDefaultState(),
                (mat) -> !mat.isAir(),
                "Wave #5 (air)").runTaskTimer(plugin, 75, 1L);

        //source.getSender().sendMessage(Component.text(String.format(
        //        "§aЗапущен пульсар (5 волн) из [%s] (%.0f, %.0f, %.0f)",
        //        world.getName(), loc.getX(), loc.getY(), loc.getZ())));
    }

    // ---------- реализация волны ----------

    @FunctionalInterface
    private interface BlockCondition {
        boolean test(Material mat);
    }

    private static final class WaveRunner extends org.bukkit.scheduler.BukkitRunnable {
        private final Plugin plugin;
        private final World world;
        private final String debugName;
        private final BlockCondition shouldTransform;
        private final BlockState toState;
        private final BlockCondition shouldPropagate;
        private final Runnable onComplete;

        private java.util.LinkedHashSet<BlockVector3> currentLayer = new java.util.LinkedHashSet<>();
        private java.util.LinkedHashSet<BlockVector3> nextLayer = new java.util.LinkedHashSet<>();
        private final java.util.HashSet<BlockVector3> visited = new java.util.HashSet<>();

        WaveRunner(Plugin plugin,
                World world,
                BlockVector3 origin,
                BlockCondition shouldTransform,
                BlockState toState,
                BlockCondition shouldPropagate,
                String debugName) {
            this(plugin, world, origin, shouldTransform, toState, shouldPropagate, debugName, null);
        }

        WaveRunner(Plugin plugin,
                World world,
                BlockVector3 origin,
                BlockCondition shouldTransform,
                BlockState toState,
                BlockCondition shouldPropagate,
                String debugName,
                Runnable onComplete) {
            this.plugin = plugin;
            this.world = world;
            this.shouldTransform = shouldTransform;
            this.toState = toState;
            this.shouldPropagate = shouldPropagate;
            this.debugName = debugName;
            this.onComplete = onComplete;
            currentLayer.add(origin);
        }

        @Override
        public void run() {
            if (currentLayer.isEmpty()) {
                cancel();
                if (onComplete != null)
                    Bukkit.getScheduler().runTask(plugin, onComplete);
                return;
            }

            try (com.sk89q.worldedit.EditSession session = com.sk89q.worldedit.WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world))
                    .fastMode(true)
                    .build()) {

                var layer = new java.util.ArrayList<>(currentLayer);
                for (BlockVector3 pos : layer) {
                    applyAt(session, pos);
                    addNeighborIfEligible(pos.x() + 1, pos.y(), pos.z());
                    addNeighborIfEligible(pos.x() - 1, pos.y(), pos.z());
                    addNeighborIfEligible(pos.x(), pos.y() + 1, pos.z());
                    addNeighborIfEligible(pos.x(), pos.y() - 1, pos.z());
                    addNeighborIfEligible(pos.x(), pos.y(), pos.z() + 1);
                    addNeighborIfEligible(pos.x(), pos.y(), pos.z() - 1);
                }
                try {
                    session.flushQueue();
                } catch (Exception ignored) {
                }
            }
            currentLayer = nextLayer;
            nextLayer = new java.util.LinkedHashSet<>();
        }

        private void applyAt(com.sk89q.worldedit.EditSession session, BlockVector3 pos) {
            if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight())
                return;
            final org.bukkit.block.Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
            final Material cur = b.getType();
            if (!shouldTransform.test(cur))
                return;

            boolean ok = session.setBlock(pos, toState);
            if (!ok) {
                try {
                    org.bukkit.block.data.BlockData bd = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(toState);
                    b.setBlockData(bd, false);
                } catch (Throwable ignored) {
                }
            }
        }

        private void addNeighborIfEligible(int x, int y, int z) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight())
                return;
            final BlockVector3 p = BlockVector3.at(x, y, z);
            final Material m = world.getBlockAt(x, y, z).getType();
            if (!shouldPropagate.test(m))
                return;
            if (visited.add(p))
                nextLayer.add(p);
        }
    }
}
