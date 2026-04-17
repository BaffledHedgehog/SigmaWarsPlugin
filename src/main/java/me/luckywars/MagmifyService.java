package me.luckywars;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.luckywars.bedwars.BedwarsBootstrap;
import me.luckywars.bedwars.BedwarsRegionManager;
import net.kyori.adventure.text.Component;

public final class MagmifyService {

    private static final int SPIKE_CHANCE_PERCENT = 1;
    private static final int SPIKE_MIN_LENGTH = 3;
    private static final int SPIKE_MAX_LENGTH = 10;
    private static final int APPROX_CENTER_SAMPLE_SIZE = 30;
    private static final double SURFACE_IMPACT_RANGE = 1.0;
    private static final double SURFACE_IMPACT_VELOCITY = 3.0;
    private static final double SURFACE_IMPACT_DAMAGE = 40.0;
    private static final int SURFACE_IMPACT_PARTICLES = 50;
    private static final int SURFACE_IMPACT_SLOWNESS_DURATION = 20 * 10;
    private static final int SURFACE_IMPACT_SLOWNESS_AMPLIFIER = 4;
    private static final AtomicBoolean MAGMIFY_IN_PROGRESS = new AtomicBoolean(false);
    private static final Set<Material> AIR_MATERIALS = EnumSet.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR);
    private static final BlockVector3[] FACE_NEIGHBORS = new BlockVector3[] {
            BlockVector3.UNIT_X,
            BlockVector3.UNIT_X.multiply(-1),
            BlockVector3.UNIT_Y,
            BlockVector3.UNIT_Y.multiply(-1),
            BlockVector3.UNIT_Z,
            BlockVector3.UNIT_Z.multiply(-1)
    };
    private static final BlockVector3[] SIDE_NEIGHBORS = new BlockVector3[] {
            BlockVector3.UNIT_X,
            BlockVector3.UNIT_X.multiply(-1),
            BlockVector3.UNIT_Z,
            BlockVector3.UNIT_Z.multiply(-1)
    };
    private static final BlockVector3[] CONNECTED_26 = buildConnected26();
    private static final List<Material> ORE_MATERIALS = collectOreMaterials();
    private static final List<Material> STONE_MATERIALS = collectStoneMaterials();

    private MagmifyService() {
    }

    public static void register(LifecycleEventManager<Plugin> mgr, JavaPlugin owner) {
        mgr.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            LiteralCommandNode<CommandSourceStack> node = Commands
                    .literal("magmify")
                    .requires(src -> src.getSender().hasPermission("magmify.use"))
                    .executes(ctx -> {
                        magmifyAsync(owner, ctx.getSource());
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();

            event.registrar().register(
                    node,
                    "Transform the connected non-air structure at the execute position.",
                    List.of());
        });
    }

    private static void magmifyAsync(JavaPlugin owner, CommandSourceStack src) {
        Location origin = src.getLocation();
        World world = origin.getWorld();
        Entity executor = src.getExecutor();
        if (world == null) {
            src.getSender().sendMessage(Component.text("Magmify failed: world is unavailable."));
            return;
        }
        BedwarsProtectionSnapshot bedwarsProtection = captureBedwarsProtection(world);

        if (!MAGMIFY_IN_PROGRESS.compareAndSet(false, true)) {
            src.getSender().sendMessage(Component.text("Magmify is already in progress."));
            return;
        }

        BlockVector3 start = BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        Bukkit.getScheduler().runTaskAsynchronously(owner, () -> {
            Throwable error = null;
            MagmifyResult result = MagmifyResult.EMPTY;
            try {
                result = magmify(world, start, minY, maxY, executor, bedwarsProtection);
            } catch (Throwable t) {
                error = t;
                owner.getLogger().log(Level.SEVERE, "Failed to magmify structure.", t);
            }

            Throwable finalError = error;
            MagmifyResult finalResult = result;
            try {
                Bukkit.getScheduler().runTask(owner, () -> {
                    try {
                        if (finalError != null) {
                            src.getSender().sendMessage(Component.text("Magmify failed. Check console."));
                            return;
                        }
                        if (finalResult.reason != null) {
                            src.getSender().sendMessage(Component.text(finalResult.reason));
                            return;
                        }
                        int impactedEntities = 0;
                        if (finalResult.blocksAppliedSuccessfully) {
                            triggerLavaUpdates(world, finalResult.lavaBlocks);
                            impactedEntities = applySurfaceImpacts(world, finalResult.impactPlan, finalResult.damageExecutor);
                        }
                        src.getSender().sendMessage(Component.text(
                                "Magmify complete. captured=" + finalResult.capturedBlocks
                                        + ", planned=" + finalResult.plannedChanges
                                        + ", applied=" + finalResult.appliedChanges
                                        + ", hit=" + impactedEntities));
                    } finally {
                        MAGMIFY_IN_PROGRESS.set(false);
                    }
                });
            } catch (Throwable scheduleError) {
                MAGMIFY_IN_PROGRESS.set(false);
                owner.getLogger().log(Level.SEVERE, "Failed to schedule post-magmify step.", scheduleError);
            }
        });
    }

    private static MagmifyResult magmify(
            World world,
            BlockVector3 start,
            int minY,
            int maxY,
            Entity damageExecutor,
            BedwarsProtectionSnapshot bedwarsProtection) throws Exception {
        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        Map<BlockVector3, Material> captured = captureStructure(weWorld, start, minY, maxY);
        if (captured.isEmpty()) {
            return MagmifyResult.withReason("Magmify found no non-air block at the execute position.");
        }

        SurfaceImpactPlan impactPlan = buildImpactPlan(captured);
        Map<BlockVector3, Material> replacements = computeReplacements(captured, minY, maxY);
        filterProtectedReplacements(replacements, bedwarsProtection);
        Set<BlockVector3> lavaBlocks = collectBlocksOfType(replacements, Material.LAVA);
        if (replacements.isEmpty()) {
            return new MagmifyResult(captured.size(), 0, 0, null, impactPlan, true, damageExecutor, lavaBlocks);
        }

        int applied = applyReplacements(weWorld, replacements);
        boolean blocksAppliedSuccessfully = applied == replacements.size();
        return new MagmifyResult(
                captured.size(),
                replacements.size(),
                applied,
                null,
                impactPlan,
                blocksAppliedSuccessfully,
                damageExecutor,
                lavaBlocks);
    }

    private static Map<BlockVector3, Material> captureStructure(
            com.sk89q.worldedit.world.World weWorld,
            BlockVector3 start,
            int minY,
            int maxY) {
        try (EditSession session = newEditSession(weWorld)) {
            Material startMaterial = getMaterial(session, start);
            if (isAirLike(startMaterial)) {
                return Collections.emptyMap();
            }

            Map<BlockVector3, Material> captured = new HashMap<>();
            Set<BlockVector3> visited = new HashSet<>();
            ArrayDeque<BlockVector3> queue = new ArrayDeque<>();
            captured.put(start, startMaterial);
            visited.add(start);
            queue.add(start);

            while (!queue.isEmpty()) {
                BlockVector3 current = queue.removeFirst();
                for (BlockVector3 offset : CONNECTED_26) {
                    int nextY = current.y() + offset.y();
                    if (nextY < minY || nextY >= maxY) {
                        continue;
                    }

                    BlockVector3 next = current.add(offset.x(), offset.y(), offset.z());
                    if (!visited.add(next)) {
                        continue;
                    }

                    Material nextMaterial = getMaterial(session, next);
                    if (isAirLike(nextMaterial)) {
                        continue;
                    }

                    captured.put(next, nextMaterial);
                    queue.addLast(next);
                }
            }

            return captured;
        }
    }

    private static Map<BlockVector3, Material> computeReplacements(
            Map<BlockVector3, Material> captured,
            int minY,
            int maxY) {
        Map<BlockVector3, Material> replacements = new HashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (Map.Entry<BlockVector3, Material> entry : captured.entrySet()) {
            BlockVector3 position = entry.getKey();
            Material original = entry.getValue();
            Material replacement = null;

            if (isSurroundedBySolidBlocks(captured, position)) {
                replacement = Material.LAVA;
            } else if (hasNoFaceConnections(captured, position)) {
                replacement = Material.MAGMA_BLOCK;
            } else if (isAirBelow(captured, position)) {
                replacement = Material.REINFORCED_DEEPSLATE;
            } else if (hasAnyFreeSideFace(captured, position)) {
                replacement = randomFrom(ORE_MATERIALS, Material.DIAMOND_ORE, random);
            } else if (isAirAbove(captured, position)) {
                replacement = randomFrom(STONE_MATERIALS, Material.STONE, random);
            }

            if (replacement != null && replacement != original) {
                replacements.put(position, replacement);
            }
        }

        planLavaSpikes(captured, replacements, minY, maxY, random);
        return replacements;
    }

    private static void planLavaSpikes(
            Map<BlockVector3, Material> captured,
            Map<BlockVector3, Material> replacements,
            int minY,
            int maxY,
            ThreadLocalRandom random) {
        for (BlockVector3 position : captured.keySet()) {
            List<BlockVector3> occupiedFaces = getOccupiedFaces(captured, position);
            int faceNeighborCount = occupiedFaces.size();
            if (faceNeighborCount < 3 || faceNeighborCount >= 6) {
                continue;
            }
            if (random.nextInt(100) >= SPIKE_CHANCE_PERCENT) {
                continue;
            }

            List<BlockVector3> freeFaces = getFreeFaces(captured, position);
            BlockVector3 direction = selectSpikeDirection(occupiedFaces, freeFaces, random);
            if (direction == null || isZero(direction)) {
                continue;
            }

            int length = random.nextInt(SPIKE_MIN_LENGTH, SPIKE_MAX_LENGTH + 1);
            for (int step = 0; step < length; step++) {
                BlockVector3 spikePos = position.add(
                        direction.x() * step,
                        direction.y() * step,
                        direction.z() * step);
                if (spikePos.y() < minY || spikePos.y() >= maxY) {
                    break;
                }
                replacements.put(spikePos, Material.LAVA);
            }
        }
    }

    private static int applyReplacements(
            com.sk89q.worldedit.world.World weWorld,
            Map<BlockVector3, Material> replacements) throws Exception {
        int applied = 0;
        try (EditSession session = newEditSession(weWorld)) {
            for (Map.Entry<BlockVector3, Material> entry : replacements.entrySet()) {
                BlockState state = BukkitAdapter.adapt(entry.getValue().createBlockData());
                if (session.setBlock(entry.getKey(), state)) {
                    applied++;
                }
            }
            session.flushQueue();
        }
        return applied;
    }

    private static void triggerLavaUpdates(World world, Set<BlockVector3> lavaBlocks) {
        for (BlockVector3 lavaPos : lavaBlocks) {
            org.bukkit.block.Block block = world.getBlockAt(lavaPos.x(), lavaPos.y(), lavaPos.z());
            if (block.getType() == Material.LAVA) {
                block.fluidTick();
            }
        }
    }

    private static Set<BlockVector3> collectBlocksOfType(Map<BlockVector3, Material> replacements, Material type) {
        Set<BlockVector3> blocks = new HashSet<>();
        for (Map.Entry<BlockVector3, Material> entry : replacements.entrySet()) {
            if (entry.getValue() == type) {
                blocks.add(entry.getKey());
            }
        }
        return Set.copyOf(blocks);
    }

    private static BedwarsProtectionSnapshot captureBedwarsProtection(World world) {
        if (world == null) {
            return BedwarsProtectionSnapshot.EMPTY;
        }

        BedwarsRegionManager regionManager = BedwarsBootstrap.regionManager();
        if (regionManager == null || !regionManager.isEnabled() || !regionManager.isWorld(world)) {
            return BedwarsProtectionSnapshot.EMPTY;
        }

        Set<BlockVector3> teamBedBlocks = new HashSet<>();
        for (org.bukkit.entity.Marker marker : world.getEntitiesByClass(org.bukkit.entity.Marker.class)) {
            if (!marker.getScoreboardTags().contains("bedwars_bed")) {
                continue;
            }

            Location center = marker.getLocation();
            int y = center.getBlockY();
            int baseX = center.getBlockX();
            int baseZ = center.getBlockZ();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (type.name().endsWith("_BED")) {
                        teamBedBlocks.add(BlockVector3.at(x, y, z));
                    }
                }
            }
        }

        return new BedwarsProtectionSnapshot(regionManager, Set.copyOf(teamBedBlocks));
    }

    private static void filterProtectedReplacements(
            Map<BlockVector3, Material> replacements,
            BedwarsProtectionSnapshot protection) {
        if (protection == null || !protection.active()) {
            return;
        }
        replacements.entrySet().removeIf(entry -> protection.isProtected(entry.getKey()));
    }

    private static SurfaceImpactPlan buildImpactPlan(Map<BlockVector3, Material> captured) {
        Set<BlockVector3> surfaceBlocks = new HashSet<>();
        List<BlockVector3> centerSample = new ArrayList<>(APPROX_CENTER_SAMPLE_SIZE);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        double sampleSumX = 0.0;
        double sampleSumY = 0.0;
        double sampleSumZ = 0.0;
        int seen = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (BlockVector3 position : captured.keySet()) {
            minX = Math.min(minX, position.x());
            minY = Math.min(minY, position.y());
            minZ = Math.min(minZ, position.z());
            maxX = Math.max(maxX, position.x());
            maxY = Math.max(maxY, position.y());
            maxZ = Math.max(maxZ, position.z());

            seen++;
            if (centerSample.size() < APPROX_CENTER_SAMPLE_SIZE) {
                centerSample.add(position);
            } else {
                int replaceIndex = random.nextInt(seen);
                if (replaceIndex < APPROX_CENTER_SAMPLE_SIZE) {
                    centerSample.set(replaceIndex, position);
                }
            }

            if (!isSurroundedByCapturedFaces(captured, position)) {
                surfaceBlocks.add(position);
            }
        }

        for (BlockVector3 sample : centerSample) {
            sampleSumX += sample.x() + 0.5;
            sampleSumY += sample.y() + 0.5;
            sampleSumZ += sample.z() + 0.5;
        }

        int sampleCount = centerSample.size();

        return new SurfaceImpactPlan(
                surfaceBlocks,
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ,
                new Vector(sampleSumX / sampleCount, sampleSumY / sampleCount, sampleSumZ / sampleCount));
    }

    private static int applySurfaceImpacts(World world, SurfaceImpactPlan plan, Entity damageExecutor) {
        if (plan == null || plan.surfaceBlocks.isEmpty()) {
            return 0;
        }

        BoundingBox queryBox = new BoundingBox(
                plan.minX - SURFACE_IMPACT_RANGE,
                plan.minY - SURFACE_IMPACT_RANGE,
                plan.minZ - SURFACE_IMPACT_RANGE,
                plan.maxX + 1.0 + SURFACE_IMPACT_RANGE,
                plan.maxY + 1.0 + SURFACE_IMPACT_RANGE,
                plan.maxZ + 1.0 + SURFACE_IMPACT_RANGE);

        ItemStack particleItem = new ItemStack(Material.IRON_NUGGET);
        int impacted = 0;
        for (Entity entity : world.getNearbyEntities(queryBox)) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (!isWithinSurfaceImpactRange(living.getBoundingBox(), plan.surfaceBlocks)) {
                continue;
            }

            applySurfaceImpactToEntity(world, living, plan.center, particleItem, damageExecutor);
            impacted++;
        }
        return impacted;
    }

    private static void applySurfaceImpactToEntity(
            World world,
            LivingEntity living,
            Vector islandCenter,
            ItemStack particleItem,
            Entity damageExecutor) {
        BoundingBox box = living.getBoundingBox();
        Vector entityCenter = box.getCenter();
        DamageSource.Builder damageSourceBuilder = DamageSource.builder(DamageType.FALLING_BLOCK)
                .withDamageLocation(new Location(world, entityCenter.getX(), entityCenter.getY(), entityCenter.getZ()));
        if (damageExecutor != null) {
            damageSourceBuilder.withCausingEntity(damageExecutor);
            damageSourceBuilder.withDirectEntity(damageExecutor);
        }
        DamageSource damageSource = damageSourceBuilder.build();

        living.damage(SURFACE_IMPACT_DAMAGE, damageSource);
        living.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                SURFACE_IMPACT_SLOWNESS_DURATION,
                SURFACE_IMPACT_SLOWNESS_AMPLIFIER,
                false,
                true,
                true));

        double offsetX = Math.max(0.0, (box.getMaxX() - box.getMinX()) * 0.5);
        double offsetY = Math.max(0.0, (box.getMaxY() - box.getMinY()) * 0.5);
        double offsetZ = Math.max(0.0, (box.getMaxZ() - box.getMinZ()) * 0.5);
        world.spawnParticle(
                Particle.ITEM,
                entityCenter.getX(),
                entityCenter.getY(),
                entityCenter.getZ(),
                SURFACE_IMPACT_PARTICLES,
                offsetX,
                offsetY,
                offsetZ,
                0.001,
                particleItem,
                true);

        Vector knockback = entityCenter.clone().subtract(islandCenter);
        if (knockback.lengthSquared() < 1.0e-6) {
            knockback = new Vector(0.0, 1.0, 0.0);
        }
        living.setVelocity(knockback.normalize().multiply(SURFACE_IMPACT_VELOCITY));
    }

    private static boolean isWithinSurfaceImpactRange(BoundingBox entityBox, Set<BlockVector3> surfaceBlocks) {
        int minX = (int) Math.floor(entityBox.getMinX() - SURFACE_IMPACT_RANGE);
        int minY = (int) Math.floor(entityBox.getMinY() - SURFACE_IMPACT_RANGE);
        int minZ = (int) Math.floor(entityBox.getMinZ() - SURFACE_IMPACT_RANGE);
        int maxX = (int) Math.floor(entityBox.getMaxX() + SURFACE_IMPACT_RANGE);
        int maxY = (int) Math.floor(entityBox.getMaxY() + SURFACE_IMPACT_RANGE);
        int maxZ = (int) Math.floor(entityBox.getMaxZ() + SURFACE_IMPACT_RANGE);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockVector3 block = BlockVector3.at(x, y, z);
                    if (!surfaceBlocks.contains(block)) {
                        continue;
                    }
                    if (distanceSquaredToBlock(entityBox, block) <= SURFACE_IMPACT_RANGE * SURFACE_IMPACT_RANGE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double distanceSquaredToBlock(BoundingBox entityBox, BlockVector3 block) {
        double dx = axisDistance(entityBox.getMinX(), entityBox.getMaxX(), block.x(), block.x() + 1.0);
        double dy = axisDistance(entityBox.getMinY(), entityBox.getMaxY(), block.y(), block.y() + 1.0);
        double dz = axisDistance(entityBox.getMinZ(), entityBox.getMaxZ(), block.z(), block.z() + 1.0);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axisDistance(double aMin, double aMax, double bMin, double bMax) {
        if (aMax < bMin) {
            return bMin - aMax;
        }
        if (bMax < aMin) {
            return aMin - bMax;
        }
        return 0.0;
    }

    private static boolean isSurroundedBySolidBlocks(Map<BlockVector3, Material> captured, BlockVector3 position) {
        for (BlockVector3 offset : FACE_NEIGHBORS) {
            Material neighbor = captured.get(position.add(offset.x(), offset.y(), offset.z()));
            if (neighbor == null || !neighbor.isSolid()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSurroundedByCapturedFaces(Map<BlockVector3, Material> captured, BlockVector3 position) {
        for (BlockVector3 offset : FACE_NEIGHBORS) {
            if (!captured.containsKey(position.add(offset.x(), offset.y(), offset.z()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNoFaceConnections(Map<BlockVector3, Material> captured, BlockVector3 position) {
        for (BlockVector3 offset : FACE_NEIGHBORS) {
            if (captured.containsKey(position.add(offset.x(), offset.y(), offset.z()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAirBelow(Map<BlockVector3, Material> captured, BlockVector3 position) {
        return !captured.containsKey(position.add(0, -1, 0));
    }

    private static boolean hasAnyFreeSideFace(Map<BlockVector3, Material> captured, BlockVector3 position) {
        for (BlockVector3 offset : SIDE_NEIGHBORS) {
            if (!captured.containsKey(position.add(offset.x(), offset.y(), offset.z()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAirAbove(Map<BlockVector3, Material> captured, BlockVector3 position) {
        return !captured.containsKey(position.add(0, 1, 0));
    }

    private static List<BlockVector3> getOccupiedFaces(Map<BlockVector3, Material> captured, BlockVector3 position) {
        List<BlockVector3> occupied = new ArrayList<>(FACE_NEIGHBORS.length);
        for (BlockVector3 offset : FACE_NEIGHBORS) {
            if (captured.containsKey(position.add(offset.x(), offset.y(), offset.z()))) {
                occupied.add(offset);
            }
        }
        return occupied;
    }

    private static List<BlockVector3> getFreeFaces(Map<BlockVector3, Material> captured, BlockVector3 position) {
        List<BlockVector3> free = new ArrayList<>(FACE_NEIGHBORS.length);
        for (BlockVector3 offset : FACE_NEIGHBORS) {
            if (!captured.containsKey(position.add(offset.x(), offset.y(), offset.z()))) {
                free.add(offset);
            }
        }
        return free;
    }

    private static BlockVector3 selectSpikeDirection(
            List<BlockVector3> occupiedFaces,
            List<BlockVector3> freeFaces,
            ThreadLocalRandom random) {
        return switch (occupiedFaces.size()) {
            case 5 -> freeFaces.isEmpty() ? null : freeFaces.get(0);
            case 4 -> selectFourNeighborSpikeDirection(freeFaces, random);
            case 3 -> selectThreeNeighborSpikeDirection(occupiedFaces, freeFaces, random);
            default -> null;
        };
    }

    private static BlockVector3 selectFourNeighborSpikeDirection(
            List<BlockVector3> freeFaces,
            ThreadLocalRandom random) {
        if (freeFaces.size() < 2) {
            return freeFaces.isEmpty() ? null : freeFaces.get(0);
        }

        BlockVector3 first = freeFaces.get(0);
        BlockVector3 second = freeFaces.get(1);
        if (!areOpposites(first, second)) {
            return signVector(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
        }
        return freeFaces.get(random.nextInt(freeFaces.size()));
    }

    private static BlockVector3 selectThreeNeighborSpikeDirection(
            List<BlockVector3> occupiedFaces,
            List<BlockVector3> freeFaces,
            ThreadLocalRandom random) {
        int dx = 0;
        int dy = 0;
        int dz = 0;
        for (BlockVector3 occupiedFace : occupiedFaces) {
            dx -= occupiedFace.x();
            dy -= occupiedFace.y();
            dz -= occupiedFace.z();
        }

        BlockVector3 preferred = signVector(dx, dy, dz);
        if (!isZero(preferred)) {
            return preferred;
        }
        return freeFaces.isEmpty() ? null : freeFaces.get(random.nextInt(freeFaces.size()));
    }

    private static boolean areOpposites(BlockVector3 first, BlockVector3 second) {
        return first.x() == -second.x()
                && first.y() == -second.y()
                && first.z() == -second.z();
    }

    private static BlockVector3 signVector(int x, int y, int z) {
        return BlockVector3.at(Integer.signum(x), Integer.signum(y), Integer.signum(z));
    }

    private static boolean isZero(BlockVector3 vector) {
        return vector.x() == 0 && vector.y() == 0 && vector.z() == 0;
    }

    private static EditSession newEditSession(com.sk89q.worldedit.world.World weWorld) {
        EditSession session = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .limitUnlimited()
                .allowedRegionsEverywhere()
                .checkMemory(false)
                .changeSetNull()
                .build();
        session.setReorderMode(EditSession.ReorderMode.NONE);
        session.setFastMode(true);
        return session;
    }

    private static Material getMaterial(EditSession session, BlockVector3 position) {
        return BukkitAdapter.adapt(session.getFullBlock(position).getBlockType());
    }

    private static boolean isAirLike(Material material) {
        return AIR_MATERIALS.contains(material);
    }

    private static Material randomFrom(List<Material> materials, Material fallback, ThreadLocalRandom random) {
        if (materials.isEmpty()) {
            return fallback;
        }
        return materials.get(random.nextInt(materials.size()));
    }

    private static BlockVector3[] buildConnected26() {
        List<BlockVector3> offsets = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    offsets.add(BlockVector3.at(dx, dy, dz));
                }
            }
        }
        return offsets.toArray(BlockVector3[]::new);
    }

    private static List<Material> collectOreMaterials() {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isSolid()) {
                continue;
            }
            if (material.name().contains("ORE")) {
                materials.add(material);
            }
        }
        return List.copyOf(materials);
    }

    private static List<Material> collectStoneMaterials() {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isSolid() || !material.isOccluding()) {
                continue;
            }

            String name = material.name();
            if (!name.contains("STONE") || name.contains("ORE") || name.startsWith("INFESTED_")) {
                continue;
            }
            if (name.endsWith("SLAB")
                    || name.endsWith("STAIRS")
                    || name.endsWith("WALL")
                    || name.endsWith("BUTTON")
                    || name.endsWith("PRESSURE_PLATE")) {
                continue;
            }
            if (material == Material.STONECUTTER
                    || material == Material.GRINDSTONE
                    || material == Material.LODESTONE
                    || material == Material.REDSTONE_BLOCK) {
                continue;
            }

            materials.add(material);
        }
        return List.copyOf(materials);
    }

    private static final class MagmifyResult {
        private static final MagmifyResult EMPTY = new MagmifyResult(0, 0, 0, null, null, false, null, Set.of());

        private final int capturedBlocks;
        private final int plannedChanges;
        private final int appliedChanges;
        private final String reason;
        private final SurfaceImpactPlan impactPlan;
        private final boolean blocksAppliedSuccessfully;
        private final Entity damageExecutor;
        private final Set<BlockVector3> lavaBlocks;

        private MagmifyResult(
                int capturedBlocks,
                int plannedChanges,
                int appliedChanges,
                String reason,
                SurfaceImpactPlan impactPlan,
                boolean blocksAppliedSuccessfully,
                Entity damageExecutor,
                Set<BlockVector3> lavaBlocks) {
            this.capturedBlocks = capturedBlocks;
            this.plannedChanges = plannedChanges;
            this.appliedChanges = appliedChanges;
            this.reason = reason;
            this.impactPlan = impactPlan;
            this.blocksAppliedSuccessfully = blocksAppliedSuccessfully;
            this.damageExecutor = damageExecutor;
            this.lavaBlocks = lavaBlocks;
        }

        private static MagmifyResult withReason(String reason) {
            return new MagmifyResult(0, 0, 0, reason, null, false, null, Set.of());
        }
    }

    private static final class SurfaceImpactPlan {
        private final Set<BlockVector3> surfaceBlocks;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final Vector center;

        private SurfaceImpactPlan(
                Set<BlockVector3> surfaceBlocks,
                int minX,
                int minY,
                int minZ,
                int maxX,
                int maxY,
                int maxZ,
                Vector center) {
            this.surfaceBlocks = Set.copyOf(surfaceBlocks);
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.center = center;
        }
    }

    private static final class BedwarsProtectionSnapshot {
        private static final BedwarsProtectionSnapshot EMPTY = new BedwarsProtectionSnapshot(null, Set.of());

        private final BedwarsRegionManager regionManager;
        private final Set<BlockVector3> teamBedBlocks;

        private BedwarsProtectionSnapshot(BedwarsRegionManager regionManager, Set<BlockVector3> teamBedBlocks) {
            this.regionManager = regionManager;
            this.teamBedBlocks = teamBedBlocks;
        }

        private boolean active() {
            return regionManager != null;
        }

        private boolean isProtected(BlockVector3 position) {
            if (teamBedBlocks.contains(position)) {
                return true;
            }
            return regionManager != null && regionManager.isSnapshottedCoord(position.x(), position.y(), position.z());
        }
    }
}
