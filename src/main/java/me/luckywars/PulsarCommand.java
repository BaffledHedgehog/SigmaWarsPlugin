package me.luckywars;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.EditSession;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * /pulsar
 * Стартует 3 независимые волны «поедания» блоков из блока (~ ~ ~) контекста
 * исполнения команды:
 * 1) сразу: всё, что не воздух и не WHITE/BLACK_CONCRETE -> WHITE_CONCRETE.
 * BFS, слой за тик.
 * 2) +2 сек: всё, что не воздух и не BLACK_CONCRETE -> BLACK_CONCRETE. BFS,
 * слой за тик.
 * 3) +4 сек: всё, что не воздух -> AIR. BFS, слой за тик.
 *
 * Важное: позиция берётся из CommandSourceStack#getLocation(), что сохраняет
 * контекст /execute из датапака.
 * Paper Brigadier гарантирует нам эту позицию.
 */
public final class PulsarCommand implements BasicCommand {

    private final Plugin plugin;

    public PulsarCommand(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        // Точка запуска — ровно блок ~ ~ ~ из контекста исполнения
        final Location loc = source.getLocation(); // Paper Brigadier хранит позицию /execute
        final World world = loc.getWorld();
        if (world == null) {
            source.getSender().sendMessage(Component.text("§cМир неизвестен для источника команды."));
            return;
        }

        final BlockVector3 origin = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());

        // ВОЛНА 1: сразу
        new WaveRunner(plugin,
                world,
                origin,
                // Условие обработки текущего блока:
                (mat) -> !mat.isAir() && mat != Material.WHITE_CONCRETE && mat != Material.BLACK_CONCRETE,
                // Состояние, в которое ставим:
                BlockTypes.WHITE_CONCRETE.getDefaultState(),
                // Условие, по которому сосед добавляется в очередь:
                (mat) -> !mat.isAir() && mat != Material.WHITE_CONCRETE && mat != Material.BLACK_CONCRETE,
                "Pulsar Wave #1 (white)").runTaskTimer(plugin, 0L, 1L);

        // ВОЛНА 2: через 2 секунды (40 тиков)
        new WaveRunner(plugin,
                world,
                origin,
                (mat) -> !mat.isAir() && mat != Material.BLACK_CONCRETE,
                BlockTypes.BLACK_CONCRETE.getDefaultState(),
                (mat) -> !mat.isAir() && mat != Material.BLACK_CONCRETE,
                "Pulsar Wave #2 (black)").runTaskTimer(plugin, 40L, 1L);

        // ВОЛНА 3: ещё через 2 секунды (итого +4 сек = 80 тиков)
        new WaveRunner(plugin,
                world,
                origin,
                (mat) -> !mat.isAir(),
                BlockTypes.AIR.getDefaultState(),
                (mat) -> !mat.isAir(),
                "Pulsar Wave #3 (air)").runTaskTimer(plugin, 80L, 1L);

        source.getSender().sendMessage(Component.text("§aЗапущен пульсар в " +
                String.format("[%s] (%d,%d,%d)",
                        world.getName(), origin.x(), origin.y(), origin.z())));
    }

    // --- реализация волны ---

    @FunctionalInterface
    private interface BlockCondition {
        boolean test(Material mat);
    }

    private static final class WaveRunner extends BukkitRunnable {
        private final Plugin plugin;
        private final World world;
        private final String debugName;

        private final BlockCondition shouldTransform;
        private final BlockState toState;
        private final BlockCondition shouldPropagate;

        private final Queue<BlockVector3> frontier = new ArrayDeque<>();
        private final Set<BlockVector3> visited = new HashSet<>();

        private EditSession session;

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

            frontier.add(origin);
            visited.add(origin);
        }

        @Override
        public void run() {
            if (session == null) {
                // Один EditSession на всю волну — так быстрее и чище для FAWE/WE очереди.
                session = WorldEdit.getInstance()
                        .newEditSessionBuilder()
                        .world(BukkitAdapter.adapt(world))
                        .fastMode(true)
                        .build();
            }

            if (frontier.isEmpty()) {
                // Закрываем сессию, когда закончили.
                try (EditSession toClose = session) {
                    // try-with-resources закроет и сбросит очередь
                }
                this.cancel();
                return;
            }

            // Обрабатываем РОВНО один «слой» BFS за тик (все вершины текущей границы).
            int layerSize = frontier.size();
            for (int i = 0; i < layerSize; i++) {
                final BlockVector3 pos = frontier.poll();
                if (pos == null)
                    continue;

                final Block b = world.getBlockAt(pos.x(), pos.y(), pos.z());
                final Material mat = b.getType();

                // Применяем изменение, если подходит под правило волны
                if (shouldTransform.test(mat)) {
                    session.setBlock(pos, toState);
                }

                // Распространяемся на 6 соседей по «манхэттену», если подходит правилу
                // распространения
                addIfEligible(BlockVector3.at(pos.x() + 1, pos.y(), pos.z()));
                addIfEligible(BlockVector3.at(pos.x() - 1, pos.y(), pos.z()));
                addIfEligible(BlockVector3.at(pos.x(), pos.y() + 1, pos.z()));
                addIfEligible(BlockVector3.at(pos.x(), pos.y() - 1, pos.z()));
                addIfEligible(BlockVector3.at(pos.x(), pos.y(), pos.z() + 1));
                addIfEligible(BlockVector3.at(pos.x(), pos.y(), pos.z() - 1));
            }
            // Ничего более — следующий слой пойдёт на следующий тик
        }

        private void addIfEligible(BlockVector3 p) {
            if (visited.contains(p))
                return;
            final Material m = world.getBlockAt(p.x(), p.y(), p.z()).getType();
            if (shouldPropagate.test(m)) {
                visited.add(p);
                frontier.add(p);
            }
        }

        @Override
        public synchronized void cancel() throws IllegalStateException {
            super.cancel();
            // Гарантированно закрыть EditSession, если он ещё жив
            if (session != null) {
                try (EditSession toClose = session) {
                    // закрытие
                } catch (Exception ignored) {
                }
                session = null;
            }
        }
    }
}
