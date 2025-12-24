package me.luckywars;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;

import java.util.Objects;

public final class MidasCommand implements BasicCommand {

    private static final int STOPPER_RADIUS = 20; // блокирует распространение «мидаса»

    private final Plugin plugin;

    public MidasCommand(Plugin plugin) {
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

        // Один волновой запуск (RAW_GOLD_BLOCK), слой-за-тик.
        new WaveRunner(
                plugin, world, origin,
                (mat) -> !mat.isAir() && mat != Material.RAW_GOLD_BLOCK, // ставим золото на любой не-воздух
                BlockTypes.RAW_GOLD_BLOCK.getDefaultState(),
                (mat) -> !mat.isAir(), // распространяемся только по не-воздуху
                "Midas Wave").runTaskTimer(plugin, 0L, 1L);

        source.getSender().sendMessage(Component.text(String.format(
             //   "§eЗапущен Мидас из [%s] (%.0f, %.0f, %.0f)",
                world.getName(), loc.getX(), loc.getY(), loc.getZ())));
    }

    // --- реализация волны (послойно, один слой за тик) ---

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
            this.plugin = plugin;
            this.world = world;
            this.shouldTransform = shouldTransform;
            this.toState = toState;
            this.shouldPropagate = shouldPropagate;
            this.debugName = debugName;

            currentLayer.add(origin);
            // не добавляем в visited заранее — только при постановке в очередь соседей
        }

        @Override
        public void run() {
            if (currentLayer.isEmpty()) {
                cancel();
                return;
            }

            // новая EditSession на тик -> гарантированная фиксация слоя
            try (com.sk89q.worldedit.EditSession session = com.sk89q.worldedit.WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world))
                    .fastMode(true)
                    .build()) {

                var layer = new java.util.ArrayList<>(currentLayer);
                for (BlockVector3 pos : layer) {
                    // stopper-зона: не трогаем блок и не расширяемся из него
                    if (hasMagicStopperNearby(pos))
                        continue;

                    // превращаем блок в золото
                    applyAt(session, pos);

                    // «позолочиваем» сущностей над этим блоком
                    goldifyEntitiesAbove(session, pos);

                    // распространяемся на 6 соседей (если они не воздух и не в зоне stopper’а)
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

        private boolean hasMagicStopperNearby(BlockVector3 p) {
            final Location c = new Location(world, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
            for (Entity ent : world.getNearbyEntities(c, STOPPER_RADIUS, STOPPER_RADIUS, STOPPER_RADIUS)) {
                if (ent.getType() == EntityType.MARKER && ent.getScoreboardTags().contains("stopper_magic")) {
                    return true;
                }
            }
            return false;
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

            if (hasMagicStopperNearby(p))
                return; // stopper-зона блокирует распространение

            final Material m = world.getBlockAt(x, y, z).getType();
            if (!shouldPropagate.test(m))
                return;

            if (visited.add(p))
                nextLayer.add(p);
        }

        /** Убиваем сущности над блоком и «заливаем» их хитбоксы RAW_GOLD_BLOCK */
        private void goldifyEntitiesAbove(com.sk89q.worldedit.EditSession session, BlockVector3 pos) {
            // кубик над блоком (x,y+1,z) … (x+1,y+3,z+1) как объём, где ищем пересечения с
            // хитбоксами
            Location center = new Location(world, pos.x() + 0.5, pos.y() + 1.5, pos.z() + 0.5);
            for (Entity e : world.getNearbyEntities(center, 1.2, 2.5, 1.2)) {
                BoundingBox bb = e.getBoundingBox();
                BoundingBox above = new BoundingBox(pos.x(), pos.y() + 1, pos.z(), pos.x() + 1, pos.y() + 3,
                        pos.z() + 1);
                if (!bb.overlaps(above))
                    continue;

                // убить/удалить
                if (e instanceof Item) {
                    e.remove(); // предметы — просто исчезают
                } else if (e instanceof Player player) {
                    if (player.getGameMode() != GameMode.SPECTATOR) {
                        player.setHealth(0.0);
                    }
                } else if (e instanceof LivingEntity living) {
                    living.setHealth(0.0);
                } else {
                    e.remove();
                }

                // заполнить хитбокс сырой золотой «решёткой» по блокам
                fillBoxWithGold(session, bb);
            }
        }

        private void fillBoxWithGold(com.sk89q.worldedit.EditSession session, BoundingBox bb) {
            int minX = Math.max((int) Math.floor(bb.getMinX()), world.getMinHeight()); // minHeight misuse guard
            int maxX = (int) Math.ceil(bb.getMaxX()) - 1;
            int minY = Math.max((int) Math.floor(bb.getMinY()), world.getMinHeight());
            int maxY = Math.min((int) Math.ceil(bb.getMaxY()) - 1, world.getMaxHeight() - 1);
            int minZ = (int) Math.floor(bb.getMinZ());
            int maxZ = (int) Math.ceil(bb.getMaxZ()) - 1;

            // поправка на X-координату: world.getMinHeight() — это по Y, по X/Z ограничений
            // нет
            minX = (int) Math.floor(bb.getMinX());
            maxX = (int) Math.ceil(bb.getMaxX()) - 1;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight())
                        continue;
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockVector3 p = BlockVector3.at(x, y, z);
                        // ставим золото; если WE не смог — fallback через Bukkit
                        boolean ok = session.setBlock(p, BlockTypes.RAW_GOLD_BLOCK.getDefaultState());
                        if (!ok) {
                            try {
                                org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                                org.bukkit.block.data.BlockData bd = com.sk89q.worldedit.bukkit.BukkitAdapter
                                        .adapt(BlockTypes.RAW_GOLD_BLOCK.getDefaultState());
                                b.setBlockData(bd, false);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        }
    }
}
