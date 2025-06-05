package com.govnoslav;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.entity.TeleportFlag.EntityState;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class RotateMapService {

    public static void register(LifecycleEventManager<Plugin> mgr, JavaPlugin owner) {
        mgr.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands
                    .literal("rotatemap")
                    .requires(src -> src.getSender().hasPermission("rotatemap.use"))
                    .executes(ctx -> {
                        rotateAroundX(ctx.getSource());
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();

            // регистрация команды
            event.registrar().register(
                    node,
                    "Повернуть куб 320×320×320 вокруг X",
                    List.of());
        });

    }
    // …

    private static void rotateAroundX(CommandSourceStack src) {
        // 0) Точка запуска и мир
        Location origin = src.getLocation();
        World world = origin.getWorld();
        if (world == null) {
           //src.getSender().sendPlainMessage("§cНе удалось определить мир.");
            return;
        }

        // 1) Параметры куба 320×320×320
        final int sizeX = 320, sizeZ = 320;
        final int yMin = 0, yMax = 319;
        final int halfX = sizeX / 2, halfZ = sizeZ / 2;
        final int xStart = origin.getBlockX() - halfX;
        final int zStart = origin.getBlockZ() - halfZ;
        final int cy = (yMin + yMax) / 2;
        final int cz = zStart + halfZ;

        // буфер всех BlockData
        BlockData[][][] dataBuf = new BlockData[sizeX][yMax - yMin + 1][sizeZ];
        // карта для содержимого контейнеров: ключ (dx,dy,dz) → ItemStack[]
        Map<Triple, ItemStack[]> invMap = new HashMap<>();

        // 2) Считываем BlockData и, если это контейнер, его содержимое
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dy = 0; dy <= yMax - yMin; dy++) {
                for (int dz = 0; dz < sizeZ; dz++) {
                    int bx = xStart + dx;
                    int by = yMin + dy;
                    int bz = zStart + dz;

                    Block block = world.getBlockAt(bx, by, bz);
                    dataBuf[dx][dy][dz] = block.getBlockData();

                    BlockState state = block.getState();
                    if (state instanceof Container container) {
                        // snapshot‐инвентарь, чтобы не трогать живые объекты мира
                        ItemStack[] items = container.getSnapshotInventory().getContents().clone();
                        if (items.length > 0) {
                            invMap.put(new Triple(dx, dy, dz), items);
                        }
                    }
                }
            }
        }

        // 3) Пишем обратно, поворачивая +90° вокруг X, и восстанавливаем контейнеры
        for (int dx = 0; dx < sizeX; dx++) {
            int bx = xStart + dx;
            for (int dy = 0; dy <= yMax - yMin; dy++) {
                for (int dz = 0; dz < sizeZ; dz++) {
                    int by = yMin + dy;
                    int bz = zStart + dz;

                    // поворот координат вокруг X
                    int relY = by - cy;
                    int relZ = bz - cz;
                    int newY = cy - relZ;
                    int newZ = cz + relY;

                    Block dst = world.getBlockAt(bx, newY, newZ);
                    dst.setBlockData(dataBuf[dx][dy][dz], false);

                    Triple key = new Triple(dx, dy, dz);
                    if (invMap.containsKey(key)) {
                        BlockState newState = dst.getState();
                        if (newState instanceof Container newContainer) {
                            newContainer.getSnapshotInventory().setContents(invMap.get(key));
                            // обязательно сохраняем состояние блока в мир
                            newState.update(true, false);
                        }
                    }
                }
            }
        }

        // 4) Телепортируем все сущности (включая игрока) вместе с их пассажирами
        double pivotX = xStart + halfX + 0.5;
        double pivotY = cy + 0.5;
        double pivotZ = cz + 0.5;
        double radiusX = halfX;
        double radiusY = (yMax - yMin + 1) / 2.0;
        double radiusZ = halfZ;

        Collection<Entity> toMove = world.getNearbyEntities(origin, radiusX, radiusY, radiusZ);
        // добавляем командующий источник, если это игрок
        if (src.getSender() instanceof Player pl && !toMove.contains(pl)) {
            toMove.add(pl);
        }

        // телепорт с флагом RETAIN_PASSENGERS (PaperMC API)
        for (Entity e : toMove) {
            Location loc = e.getLocation();
            double dx = loc.getX() - pivotX;
            double dy = loc.getY() - pivotY;
            double dz = loc.getZ() - pivotZ;
            double fx = pivotX + dx;
            double fy = pivotY - dz;
            double fz = pivotZ + dy + 0.5;
            e.teleport(
                    new Location(world, fx, fy, fz, loc.getYaw(), loc.getPitch()),
                    EntityState.RETAIN_PASSENGERS,
                    EntityState.RETAIN_VEHICLE);
        }

        //src.getSender().sendPlainMessage("§aКуб 320×320×320 успешно повернут на +90° вокруг X.");
    }

    // утилитарный record для ключей
    private record Triple(int x, int y, int z) {
    }

}
