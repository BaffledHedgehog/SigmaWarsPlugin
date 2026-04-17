package me.luckywars;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;

public final class MidasCommand implements BasicCommand {

    private static final int STOPPER_RADIUS = 20;

    private final Plugin plugin;
    private final boolean affectEntities;
    private final int maxSpreadDistance;

    public MidasCommand(Plugin plugin) {
        this(plugin, true, -1);
    }

    public MidasCommand(Plugin plugin, boolean affectEntities, int maxSpreadDistance) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.affectEntities = affectEntities;
        this.maxSpreadDistance = maxSpreadDistance;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        Location loc = source.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            source.getSender().sendMessage(Component.text("\u00a7cWorld is unavailable for this command source."));
            return;
        }

        BlockVector3 origin = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        new WaveRunner(
                world,
                origin,
                mat -> !mat.isAir() && mat != Material.RAW_GOLD_BLOCK,
                BlockTypes.RAW_GOLD_BLOCK.getDefaultState(),
                mat -> !mat.isAir(),
                affectEntities,
                maxSpreadDistance)
                .runTaskTimer(plugin, 0L, 1L);
    }

    @FunctionalInterface
    private interface BlockCondition {
        boolean test(Material mat);
    }

    private static final class WaveRunner extends BukkitRunnable {
        private final World world;
        private final BlockVector3 origin;
        private final BlockCondition shouldTransform;
        private final BlockState toState;
        private final BlockCondition shouldPropagate;
        private final boolean affectEntities;
        private final int maxSpreadDistanceSquared;

        private LinkedHashSet<BlockVector3> currentLayer = new LinkedHashSet<>();
        private LinkedHashSet<BlockVector3> nextLayer = new LinkedHashSet<>();
        private final HashSet<BlockVector3> visited = new HashSet<>();

        WaveRunner(
                World world,
                BlockVector3 origin,
                BlockCondition shouldTransform,
                BlockState toState,
                BlockCondition shouldPropagate,
                boolean affectEntities,
                int maxSpreadDistance) {
            this.world = world;
            this.origin = origin;
            this.shouldTransform = shouldTransform;
            this.toState = toState;
            this.shouldPropagate = shouldPropagate;
            this.affectEntities = affectEntities;
            this.maxSpreadDistanceSquared = maxSpreadDistance < 0 ? -1 : maxSpreadDistance * maxSpreadDistance;

            currentLayer.add(origin);
            visited.add(origin);
        }

        @Override
        public void run() {
            if (currentLayer.isEmpty()) {
                cancel();
                return;
            }

            try (EditSession session = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(BukkitAdapter.adapt(world))
                    .fastMode(true)
                    .build()) {

                ArrayList<BlockVector3> layer = new ArrayList<>(currentLayer);
                for (BlockVector3 pos : layer) {
                    if (!isWithinSpreadLimit(pos) || hasMagicStopperNearby(pos)) {
                        continue;
                    }

                    applyAt(session, pos);
                    if (affectEntities) {
                        goldifyEntitiesAbove(session, pos);
                    }

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
            nextLayer = new LinkedHashSet<>();
        }

        private boolean isWithinSpreadLimit(BlockVector3 pos) {
            if (maxSpreadDistanceSquared < 0) {
                return true;
            }

            int dx = pos.x() - origin.x();
            int dy = pos.y() - origin.y();
            int dz = pos.z() - origin.z();
            return dx * dx + dy * dy + dz * dz <= maxSpreadDistanceSquared;
        }

        private boolean hasMagicStopperNearby(BlockVector3 pos) {
            Location center = new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5);
            for (Entity entity : world.getNearbyEntities(center, STOPPER_RADIUS, STOPPER_RADIUS, STOPPER_RADIUS)) {
                if (entity.getType() == EntityType.MARKER && entity.getScoreboardTags().contains("stopper_magic")) {
                    return true;
                }
            }
            return false;
        }

        private void applyAt(EditSession session, BlockVector3 pos) {
            if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
                return;
            }

            org.bukkit.block.Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            Material currentType = block.getType();
            if (!shouldTransform.test(currentType)) {
                return;
            }

            boolean changed = session.setBlock(pos, toState);
            if (!changed) {
                try {
                    block.setBlockData(BukkitAdapter.adapt(toState), false);
                } catch (Throwable ignored) {
                }
            }
        }

        private void addNeighborIfEligible(int x, int y, int z) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return;
            }

            BlockVector3 pos = BlockVector3.at(x, y, z);
            if (!isWithinSpreadLimit(pos) || hasMagicStopperNearby(pos)) {
                return;
            }

            Material type = world.getBlockAt(x, y, z).getType();
            if (!shouldPropagate.test(type)) {
                return;
            }

            if (visited.add(pos)) {
                nextLayer.add(pos);
            }
        }

        private void goldifyEntitiesAbove(EditSession session, BlockVector3 pos) {
            Location center = new Location(world, pos.x() + 0.5, pos.y() + 1.5, pos.z() + 0.5);
            BoundingBox affectedVolume = new BoundingBox(pos.x(), pos.y() + 1, pos.z(), pos.x() + 1, pos.y() + 3,
                    pos.z() + 1);

            for (Entity entity : world.getNearbyEntities(center, 1.2, 2.5, 1.2)) {
                BoundingBox entityBox = entity.getBoundingBox();
                if (!entityBox.overlaps(affectedVolume)) {
                    continue;
                }

                if (entity instanceof Item) {
                    entity.remove();
                } else if (entity instanceof Player player) {
                    if (player.getGameMode() != GameMode.SPECTATOR) {
                        player.setHealth(0.0);
                    }
                } else if (entity instanceof LivingEntity living) {
                    living.setHealth(0.0);
                } else {
                    entity.remove();
                }

                fillBoxWithGold(session, entityBox);
            }
        }

        private void fillBoxWithGold(EditSession session, BoundingBox box) {
            int minX = (int) Math.floor(box.getMinX());
            int maxX = (int) Math.ceil(box.getMaxX()) - 1;
            int minY = Math.max((int) Math.floor(box.getMinY()), world.getMinHeight());
            int maxY = Math.min((int) Math.ceil(box.getMaxY()) - 1, world.getMaxHeight() - 1);
            int minZ = (int) Math.floor(box.getMinZ());
            int maxZ = (int) Math.ceil(box.getMaxZ()) - 1;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }

                    for (int z = minZ; z <= maxZ; z++) {
                        BlockVector3 pos = BlockVector3.at(x, y, z);
                        boolean changed = session.setBlock(pos, BlockTypes.RAW_GOLD_BLOCK.getDefaultState());
                        if (!changed) {
                            try {
                                org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                                block.setBlockData(BukkitAdapter.adapt(BlockTypes.RAW_GOLD_BLOCK.getDefaultState()),
                                        false);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }
    }
}
