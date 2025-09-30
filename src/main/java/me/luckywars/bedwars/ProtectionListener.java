package me.luckywars.bedwars;

import me.luckywars.bedwars.util.DyeColorOfTeam;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Marker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;

public final class ProtectionListener implements Listener {
    private final BedwarsRegionManager mgr;

    public ProtectionListener(BedwarsRegionManager mgr) {
        this.mgr = mgr;
    }

    private boolean isProtectedTypeChange(Block current, Material newType) {
        if (!mgr.isEnabled() || !mgr.isInside(current))
            return false;
        Material orig = mgr.originalTypeAt(current);
        if (orig == null)
            return false; // снимок был воздух
        return newType != orig;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();

        if (!mgr.isEnabled() || !mgr.isInside(b))
            return;

        // Разрешить ЛИЧНОЕ разрушение кровати, если её цвет != цвету команды игрока
        if (mgr.isOriginalTypeBlock(b)) {
            DyeColor bed = bedDyeOf(b.getType());
            if (bed != null) {
                DyeColor playerTeam = DyeColorOfTeam.dyeOf(e.getPlayer());
                boolean allow = bed != playerTeam;

                Bukkit.getLogger().info(String.format(
                        "[Bedwars/Protect] %s tries to break bed %s at %s [%d,%d,%d]; bed=%s, playerTeam=%s -> %s",
                        e.getPlayer().getName(),
                        b.getType().name(),
                        b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                        bed, playerTeam, (allow ? "ALLOW" : "DENY")));

                if (allow) {
                    // ВАЖНО: сами ломаем обе половинки, предварительно сняв защиту со снимка
                    e.setCancelled(true);
                    breakBedManually(b);

                    // Дополнительно: удалить маяк под ближайшим swrg.spawn (на 2 блока ниже)
                    removeBeaconUnderNearestSpawn(b);

                    return;
                }
            }

            // не кровать ИЛИ кровать своего цвета — защищаем как и раньше
            e.setCancelled(true);
            return;
        }
    }

    /** Ручной снос кровати (обе части), с предварительным снятием защиты снимка. */
    private void breakBedManually(Block anyPart) {
        if (!(anyPart.getBlockData() instanceof org.bukkit.block.data.type.Bed bedData)) {
            // На всякий случай, если ломали не bed
            mgr.forgetSnapshotAt(anyPart.getX(), anyPart.getY(), anyPart.getZ());
            anyPart.setType(Material.AIR, false);
            return;
        }

        // Текущая часть
        Block part1 = anyPart;
        // Вторая часть
        BlockFace facing = bedData.getFacing();
        boolean isHead = bedData.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD;
        Block part2 = part1.getRelative(isHead ? facing.getOppositeFace() : facing);

        // Снять защиту со снимка на обеих координатах
        mgr.forgetSnapshotAt(part1.getX(), part1.getY(), part1.getZ());
        mgr.forgetSnapshotAt(part2.getX(), part2.getY(), part2.getZ());

        // Удалить оба блока
        part1.setType(Material.AIR, false);
        if (part2.getType().name().endsWith("_BED")) {
            part2.setType(Material.AIR, false);
        }

        // Немного обратной связи (необязательно)
        World w = part1.getWorld();
        w.playSound(part1.getLocation(), org.bukkit.Sound.BLOCK_WOOL_BREAK, 1f, 1f);
    }

    /**
     * Удаляет блок BEACON, который находится ровно на 2 блока ниже ближайшего
     * swrg.spawn к указанной кровати.
     */
    private void removeBeaconUnderNearestSpawn(Block bedPart) {
        try {
            World w = bedPart.getWorld();
            Location bedCenter = bedPart.getLocation().toCenterLocation();

            Marker nearest = null;
            double bestD = Double.MAX_VALUE;

            for (Marker m : w.getEntitiesByClass(Marker.class)) {
                if (!m.getScoreboardTags().contains("swrg.spawn"))
                    continue;
                double d = m.getLocation().toCenterLocation().distanceSquared(bedCenter);
                if (d < bestD) {
                    bestD = d;
                    nearest = m;
                }
            }

            if (nearest == null)
                return;

            Location ml = nearest.getLocation();
            int x = ml.getBlockX();
            int y = ml.getBlockY() - 2; // ровно на 2 ниже
            int z = ml.getBlockZ();

            Block beacon = w.getBlockAt(x, y, z);
            if (beacon.getType() == Material.BEACON) {
                // снять защиту и удалить
                mgr.forgetSnapshotAt(x, y, z);
                beacon.setType(Material.AIR, false);
                Bukkit.getLogger().info(String.format(
                        "[Bedwars/Protect] Removed BEACON under nearest swrg.spawn at %s [%d,%d,%d]",
                        w.getName(), x, y, z));
            }
        } catch (Throwable t) {
            // тихо игнорируем, чтобы не ломать событие
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (!mgr.isInside(b))
            return;

        // Нельзя перезаписать координату "старого" блока
        if (mgr.isOriginalTypeBlock(b) && mgr.isSnapshottedCoord(b.getX(), b.getY(), b.getZ())) {
            e.setCancelled(true);
            return;
        }

        // Если тут был "старый" тип — не позволим заменить на другой
        Material orig = mgr.originalTypeAt(b);
        if (orig != null && e.getBlockPlaced().getType() != orig)
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!mgr.isEnabled())
            return;
        e.blockList().removeIf(mgr::isOriginalTypeBlock);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!mgr.isEnabled())
            return;
        e.blockList().removeIf(mgr::isOriginalTypeBlock);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFlow(BlockFromToEvent e) {
        if (mgr.isOriginalTypeBlock(e.getToBlock()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFluid(FluidLevelChangeEvent e) {
        if (isProtectedTypeChange(e.getBlock(), e.getNewData().getMaterial()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks())
            if (mgr.isOriginalTypeBlock(b)) {
                e.setCancelled(true);
                return;
            }
        Block headTarget = e.getBlock().getRelative(e.getDirection());
        if (mgr.isSnapshottedCoord(headTarget.getX(), headTarget.getY(), headTarget.getZ()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks())
            if (mgr.isOriginalTypeBlock(b)) {
                e.setCancelled(true);
                return;
            }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onGrow(BlockGrowEvent e) {
        if (isProtectedTypeChange(e.getBlock(), e.getNewState().getType()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSpread(BlockSpreadEvent e) {
        if (isProtectedTypeChange(e.getBlock(), e.getNewState().getType()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onForm(BlockFormEvent e) {
        if (isProtectedTypeChange(e.getBlock(), e.getNewState().getType()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFade(BlockFadeEvent e) {
        if (mgr.isOriginalTypeBlock(e.getBlock()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onLeaves(LeavesDecayEvent e) {
        if (mgr.isOriginalTypeBlock(e.getBlock()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (isProtectedTypeChange(e.getBlock(), e.getTo()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onVanillaDestroy(BlockDestroyEvent e) {
        // /fill destroy, /setblock destroy и пр. — не считаются «личным ломанием»
        if (!mgr.isEnabled())
            return;
        if (mgr.isOriginalTypeBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFire(BlockBurnEvent e) {
        if (mgr.isOriginalTypeBlock(e.getBlock()))
            e.setCancelled(true);
    }

    /* ================= helpers ================= */

    private static DyeColor bedDyeOf(Material m) {
        return switch (m) {
            case WHITE_BED -> DyeColor.WHITE;
            case ORANGE_BED -> DyeColor.ORANGE;
            case MAGENTA_BED -> DyeColor.MAGENTA;
            case LIGHT_BLUE_BED -> DyeColor.LIGHT_BLUE;
            case YELLOW_BED -> DyeColor.YELLOW;
            case LIME_BED -> DyeColor.LIME;
            case PINK_BED -> DyeColor.PINK;
            case GRAY_BED -> DyeColor.GRAY;
            case LIGHT_GRAY_BED -> DyeColor.LIGHT_GRAY;
            case CYAN_BED -> DyeColor.CYAN;
            case PURPLE_BED -> DyeColor.PURPLE;
            case BLUE_BED -> DyeColor.BLUE;
            case BROWN_BED -> DyeColor.BROWN;
            case GREEN_BED -> DyeColor.GREEN;
            case RED_BED -> DyeColor.RED;
            case BLACK_BED -> DyeColor.BLACK;
            default -> null;
        };
    }
}
