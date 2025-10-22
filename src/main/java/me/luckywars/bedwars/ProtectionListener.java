package me.luckywars.bedwars;

import me.luckywars.bedwars.util.DyeColorOfTeam;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.Tag;
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

    /* ============ exemption (no protection) ============ */

    private static boolean isExempt(Material m) {
        if (m == Material.WATER || m == Material.LAVA || m == Material.POWDER_SNOW)
            return true;

        // #minecraft:flowers
        if (Tag.SMALL_FLOWERS.isTagged(m) || Tag.FLOWERS.isTagged(m))
            return true;

        // «трава» (аналог #minecraft:grass)
        switch (m) {
            case SHORT_GRASS:
            case TALL_GRASS:
            case FERN:
            case LARGE_FERN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Защищаем смену типа блока только если:
     *  - защита включена и координата в регионе;
     *  - на снимке тут был НЕ null;
     *  - ни исходный тип из снимка, ни новый тип не входят в exempt-набор;
     *  - новый тип отличается от оригинального.
     */
    private boolean isProtectedTypeChange(Block current, Material newType) {
        if (!mgr.isEnabled() || !mgr.isInside(current))
            return false;
        Material orig = mgr.originalTypeAt(current);
        if (orig == null)
            return false; // снимок был воздух
        if (isExempt(orig) || isExempt(newType))
            return false;
        return newType != orig;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();

        if (!mgr.isEnabled() || !mgr.isInside(b))
            return;

        // Если исходный блок по снимку — exempt, защита не работает
        if (isExempt(mgr.originalTypeAt(b) != null ? mgr.originalTypeAt(b) : b.getType()))
            return;

        // Разрешить ЛИЧНОЕ разрушение кровати врага
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
                    e.setCancelled(true);
                    breakBedManually(b);
                    removeBeaconUnderNearestSpawn(b);
                    return;
                }
            }

            // не кровать ИЛИ своя кровать — защищаем как и раньше
            e.setCancelled(true);
        }
    }

    /** Ручной снос кровати (обе части), с предварительным снятием защиты снимка. */
    private void breakBedManually(Block anyPart) {
        if (!(anyPart.getBlockData() instanceof org.bukkit.block.data.type.Bed bedData)) {
            mgr.forgetSnapshotAt(anyPart.getX(), anyPart.getY(), anyPart.getZ());
            anyPart.setType(Material.AIR, false);
            return;
        }

        Block part1 = anyPart;
        BlockFace facing = bedData.getFacing();
        boolean isHead = bedData.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD;
        Block part2 = part1.getRelative(isHead ? facing.getOppositeFace() : facing);

        mgr.forgetSnapshotAt(part1.getX(), part1.getY(), part1.getZ());
        mgr.forgetSnapshotAt(part2.getX(), part2.getY(), part2.getZ());

        part1.setType(Material.AIR, false);
        if (part2.getType().name().endsWith("_BED")) {
            part2.setType(Material.AIR, false);
        }

        World w = part1.getWorld();
        w.playSound(part1.getLocation(), org.bukkit.Sound.BLOCK_WOOL_BREAK, 1f, 1f);
    }

    /** Удаляет BEACON на 2 блока ниже ближайшего swrg.spawn. */
    private void removeBeaconUnderNearestSpawn(Block bedPart) {
        try {
            World w = bedPart.getWorld();
            Location bedCenter = bedPart.getLocation().toCenterLocation();

            Marker nearest = null;
            double bestD = Double.MAX_VALUE;

            for (Marker m : w.getEntitiesByClass(Marker.class)) {
                if (!m.getScoreboardTags().contains("swrg.spawn")) continue;
                double d = m.getLocation().toCenterLocation().distanceSquared(bedCenter);
                if (d < bestD) { bestD = d; nearest = m; }
            }

            if (nearest == null) return;

            Location ml = nearest.getLocation();
            int x = ml.getBlockX();
            int y = ml.getBlockY() - 2; // ровно на 2 ниже
            int z = ml.getBlockZ();

            Block beacon = w.getBlockAt(x, y, z);
            if (beacon.getType() == Material.BEACON) {
                mgr.forgetSnapshotAt(x, y, z);
                beacon.setType(Material.AIR, false);
                Bukkit.getLogger().info(String.format(
                        "[Bedwars/Protect] Removed BEACON under nearest swrg.spawn at %s [%d,%d,%d]",
                        w.getName(), x, y, z));
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (!mgr.isInside(b)) return;

        Material orig = mgr.originalTypeAt(b);

        // «Нельзя перезаписать координату старого блока» — кроме exempt-оригиналов
        if (mgr.isOriginalTypeBlock(b) && mgr.isSnapshottedCoord(b.getX(), b.getY(), b.getZ())) {
            if (orig != null && !isExempt(orig)) {
                e.setCancelled(true);
                return;
            }
        }

        // Запрет подмены типа «старого» блока — кроме случаев с exempt
        Material newType = e.getBlockPlaced().getType();
        if (orig != null && !isExempt(orig) && !isExempt(newType) && newType != orig) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!mgr.isEnabled()) return;
        e.blockList().removeIf(b -> mgr.isOriginalTypeBlock(b) && !isExempt(b.getType()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!mgr.isEnabled()) return;
        e.blockList().removeIf(b -> mgr.isOriginalTypeBlock(b) && !isExempt(b.getType()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFlow(BlockFromToEvent e) {
        // блокируем только если «to» — защищаемый оригинальный блок НЕ из exempt
        if (mgr.isOriginalTypeBlock(e.getToBlock()) && !isExempt(e.getToBlock().getType()))
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
            if (mgr.isOriginalTypeBlock(b) && !isExempt(b.getType())) {
                e.setCancelled(true);
                return;
            }
        Block headTarget = e.getBlock().getRelative(e.getDirection());
        if (mgr.isSnapshottedCoord(headTarget.getX(), headTarget.getY(), headTarget.getZ())) {
            Material orig = mgr.originalTypeAt(headTarget);
            if (orig != null && !isExempt(orig)) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks())
            if (mgr.isOriginalTypeBlock(b) && !isExempt(b.getType())) {
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
        if (mgr.isOriginalTypeBlock(e.getBlock()) && !isExempt(e.getBlock().getType()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onLeaves(LeavesDecayEvent e) {
        if (mgr.isOriginalTypeBlock(e.getBlock()) && !isExempt(e.getBlock().getType()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (isProtectedTypeChange(e.getBlock(), e.getTo()))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onVanillaDestroy(BlockDestroyEvent e) {
        if (!mgr.isEnabled()) return;
        if (mgr.isOriginalTypeBlock(e.getBlock()) && !isExempt(e.getBlock().getType())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFire(BlockBurnEvent e) {
        if (mgr.isOriginalTypeBlock(e.getBlock()) && !isExempt(e.getBlock().getType()))
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
