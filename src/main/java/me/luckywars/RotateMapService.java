package me.luckywars;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.entity.TeleportFlag.EntityState;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;

public final class RotateMapService {

    private static final int SIZE_X = 320;
    private static final int SIZE_Y = 320;
    private static final int SIZE_Z = 320;
    private static final int Y_MIN = 0;
    private static final int Y_MAX = Y_MIN + SIZE_Y - 1;
    private static final AtomicBoolean ROTATION_IN_PROGRESS = new AtomicBoolean(false);

    private RotateMapService() {
    }

    public static void register(LifecycleEventManager<Plugin> mgr, JavaPlugin owner) {
        mgr.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands
                    .literal("rotatemap")
                    .requires(src -> src.getSender().hasPermission("rotatemap.use"))
                    .executes(ctx -> {
                        rotateAroundXAsync(owner, ctx.getSource());
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();

            event.registrar().register(
                    node,
                    "Повернуть куб 320x320x320 вокруг X",
                    List.of());
        });
    }

    private static void rotateAroundXAsync(JavaPlugin owner, CommandSourceStack src) {
        Location origin = src.getLocation();
        World world = origin.getWorld();
        if (world == null || isExcludedDimension(world)) {
            return;
        }

        if (!ROTATION_IN_PROGRESS.compareAndSet(false, true)) {
            src.getSender().sendMessage(Component.text("Map rotation is already in progress."));
            return;
        }

        broadcastRotateWarn();

        final int xStart = origin.getBlockX() - (SIZE_X / 2);
        final int zStart = origin.getBlockZ() - (SIZE_Z / 2);

        Bukkit.getScheduler().runTaskAsynchronously(owner, () -> {
            Throwable error = null;
            RotateResult rotateResult = RotateResult.EMPTY;
            try {
                rotateResult = rotateRegionWithFawe(world, xStart, zStart);
            } catch (Throwable t) {
                error = t;
                owner.getLogger().log(Level.SEVERE, "Failed to rotate map region.", t);
            }

            Throwable finalError = error;
            RotateResult finalRotateResult = rotateResult;
            try {
                Bukkit.getScheduler().runTask(owner, () -> {
                    try {
                        if (finalError == null && finalRotateResult.pastedBlocks > 0) {
                            rotateEntities(world, src, xStart, zStart);
                        } else if (finalError == null) {
                            src.getSender().sendMessage(Component.text(
                                    "Map rotation changed 0 blocks. copied=" + finalRotateResult.copiedBlocks
                                            + ", pasted=" + finalRotateResult.pastedBlocks
                                            + ", tried=" + finalRotateResult.triedPastes));
                        } else {
                            src.getSender().sendMessage(Component.text("Map rotation failed. Check console."));
                        }
                    } finally {
                        ROTATION_IN_PROGRESS.set(false);
                    }
                });
            } catch (Throwable scheduleError) {
                ROTATION_IN_PROGRESS.set(false);
                owner.getLogger().log(Level.SEVERE, "Failed to schedule post-rotation step.", scheduleError);
            }
        });
    }

    private static RotateResult rotateRegionWithFawe(World world, int xStart, int zStart) throws Exception {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        BlockVector3 min = BlockVector3.at(xStart, Y_MIN, zStart);
        BlockVector3 max = BlockVector3.at(xStart + SIZE_X - 1, Y_MAX, zStart + SIZE_Z - 1);

        CuboidRegion region = new CuboidRegion(weWorld, min, max);
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);

        int copied;
        int pastedOk;
        int pastedTried;
        try (EditSession copySession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .limitUnlimited()
                .allowedRegionsEverywhere()
                .checkMemory(false)
                .changeSetNull()
                .build()) {
            copySession.setReorderMode(EditSession.ReorderMode.NONE);
            copySession.setFastMode(true);

            ForwardExtentCopy copy = new ForwardExtentCopy(weWorld, region, min, clipboard, min);
            copy.setCopyingEntities(false);
            copy.setCopyingBiomes(false);
            Operations.complete(copy);
            copied = copy.getAffected();
            clipboard.flush();
        }

        try (EditSession pasteSession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .limitUnlimited()
                .allowedRegionsEverywhere()
                .checkMemory(false)
                .changeSetNull()
                .build()) {
            pasteSession.setReorderMode(EditSession.ReorderMode.NONE);
            pasteSession.setFastMode(true);

            pastedOk = 0;
            pastedTried = 0;
            for (int x = xStart; x < xStart + SIZE_X; x++) {
                for (int y = Y_MIN; y <= Y_MAX; y++) {
                    int relY = y - Y_MIN;
                    for (int z = zStart; z < zStart + SIZE_Z; z++) {
                        int relZ = z - zStart;

                        // +90 around X for 320-sized cube [0..319] in Y/Z
                        int newY = Y_MIN + (SIZE_Y - 1 - relZ);
                        int newZ = zStart + relY;

                        BaseBlock block = clipboard.getFullBlock(x, y, z);
                        pastedTried++;
                        if (pasteSession.setBlock(x, newY, newZ, block)) {
                            pastedOk++;
                        }
                    }
                }
            }
            pasteSession.flushQueue();
        } finally {
            clipboard.close();
        }
        return new RotateResult(copied, pastedOk, pastedTried);
    }

    private static void rotateEntities(World world, CommandSourceStack src, int xStart, int zStart) {
        BoundingBox box = new BoundingBox(
                xStart, Y_MIN, zStart,
                xStart + SIZE_X, Y_MAX + 1, zStart + SIZE_Z);
        Collection<Entity> toMove = new ArrayList<>(world.getNearbyEntities(box));
        if (src.getSender() instanceof Player player && !toMove.contains(player)) {
            toMove.add(player);
        }

        double pivotY = Y_MIN + (SIZE_Y / 2.0);
        double pivotZ = zStart + (SIZE_Z / 2.0);
        for (Entity entity : toMove) {
            Location loc = entity.getLocation();
            double relY = loc.getY() - pivotY;
            double relZ = loc.getZ() - pivotZ;
            double newY = pivotY - relZ;
            double newZ = pivotZ + relY;
            entity.teleport(
                    new Location(world, loc.getX(), newY, newZ, loc.getYaw(), loc.getPitch()),
                    EntityState.RETAIN_PASSENGERS,
                    EntityState.RETAIN_VEHICLE);
        }
    }

    private static void broadcastRotateWarn() {
        Component warn = Component.translatable("map_rotate_warn");
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(warn);
        }
    }

    private static boolean isExcludedDimension(World world) {
        String dim = world.getKey().toString();
        return "minecraft:nexus".equals(dim) || "minecraft:imprinted".equals(dim);
    }

    private static final class RotateResult {
        private static final RotateResult EMPTY = new RotateResult(0, 0, 0);
        private final int copiedBlocks;
        private final int pastedBlocks;
        private final int triedPastes;

        private RotateResult(int copiedBlocks, int pastedBlocks, int triedPastes) {
            this.copiedBlocks = copiedBlocks;
            this.pastedBlocks = pastedBlocks;
            this.triedPastes = triedPastes;
        }
    }
}
