package me.luckywars;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Marker;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;

import java.util.Collection;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.bukkit.craftbukkit.entity.CraftEntity;

import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
// NBT load:
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import org.joml.Quaternionf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Физика для MARKER / ITEM_DISPLAY / BLOCK_DISPLAY с параметрами в корневом NBT
 * под data:{...}.
 * НИКАКИХ fallback-ов через команды/PDC. Только NMS.
 */
public final class PhysicalObjectPhysics implements Runnable {

    // Включающий тэг
    public static final String TRACK_TAG = "physical_object";
    // Рабочие тэги
    private static final String TAG_IN_ENTITY = "in_entity";
    private static final String TAG_IN_BLOCK = "in_block";
    private static final String TAG_ON_BLOCK = "on_block";
    private static final Logger LOG = LoggerFactory.getLogger(PhysicalObjectPhysics.class);

    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double MAX_STEP = 0.5; // против туннелирования
    private static final double EPS = 1e-4;
    private static final double VEL_EPS = 1e-4;

    private final Plugin plugin;
    private BukkitTask task;

    public PhysicalObjectPhysics(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null)
            return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public void run() {
        for (World world : Bukkit.getWorlds()) {
            Collection<Entity> pool = world.getEntitiesByClasses(Marker.class, ItemDisplay.class, BlockDisplay.class);
            if (pool.isEmpty())
                continue;

            for (Entity e : pool) {
                if (!e.isValid())
                    continue;
                if (!e.getScoreboardTags().contains(TRACK_TAG))
                    continue;
                stepEntity(e);
            }
        }
    }

    /* ===================== Основной шаг ===================== */

    private void stepEntity(Entity e) {
        PhysParams p = readParamsFromEntityNbt(e); // читаем data:{...}
        double vx = p.motion[0], vy = p.motion[1], vz = p.motion[2];

        // гравитация
        vy -= GRAVITY_PER_TICK;

        Location loc = e.getLocation();
        World w = loc.getWorld();

        int subSteps = Math.max(1, (int) Math.ceil(maxAbs(vx, vy, vz) / MAX_STEP));
        double sx = vx / subSteps, sy = vy / subSteps, sz = vz / subSteps;

        boolean collidedAny = false;
        boolean onBlock = false;

        BoundingBox box = computeHitbox(e, loc);

        for (int i = 0; i < subSteps; i++) {
            // осевой свип: X -> Y -> Z (клиппинг до ближайшего препятствия)
            double mx = sweepAxisX(w, box, sx);
            if (mx != sx) {
                collidedAny = true;
                vx = -vx * p.bounceness;
                // не «откатываем» смещение, уже клиппнули до касания
            }
            box = box.shift(mx, 0, 0);
            loc.add(mx, 0, 0);

            double my = sweepAxisY(w, box, sy);
            if (my != sy) {
                collidedAny = true;
                if (sy < 0)
                    onBlock = true; // нижнее касание
                vy = -vy * p.bounceness;
            }
            box = box.shift(0, my, 0);
            loc.add(0, my, 0);

            double mz = sweepAxisZ(w, box, sz);
            if (mz != sz) {
                collidedAny = true;
                vz = -vz * p.bounceness;
            }
            box = box.shift(0, 0, mz);
            loc.add(0, 0, mz);

            // телепорт один раз за подшаг
            e.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        // drag
        double drag = onBlock ? p.friction : p.airFriction;
        vx *= drag;
        vz *= drag;
        vy *= (onBlock ? Math.max(0.0, drag - 0.02) : drag);

        // загашение малых скоростей (чтобы не дрожал и не «полз» по плоскостям)
        if (Math.abs(vx) < VEL_EPS)
            vx = 0.0;
        if (Math.abs(vy) < VEL_EPS)
            vy = 0.0;
        if (Math.abs(vz) < VEL_EPS)
            vz = 0.0;

        // метки
        boolean inEntity = intersectsAnyLiving(e, box);
        boolean inBlock = overlapsSolid(w, box); // именно «находится внутри твёрдого блока» по факту
        setScoreboardTag(e, TAG_IN_ENTITY, inEntity);
        setScoreboardTag(e, TAG_IN_BLOCK, inBlock || collidedAny);
        setScoreboardTag(e, TAG_ON_BLOCK, onBlock);

        // ориентация по скорости: только если есть заметное движение
        final double speed2 = vx * vx + vy * vy + vz * vz;
        if (speed2 > (1e-6)) {
            applyFacing(e, vx, vy, vz);
        }

        // пишем обновлённые параметры обратно в NBT data:{...}
        p.motion[0] = vx;
        p.motion[1] = vy;
        p.motion[2] = vz;
        writeParamsToEntityNbt(e, p);
    }

    private static double maxAbs(double a, double b, double c) {
        return Math.max(Math.abs(a), Math.max(Math.abs(b), Math.abs(c)));
    }

    /* ===================== Свип-коллизии (swept AABB) ===================== */

    private double sweepAxisX(World w, BoundingBox box, double dx) {
        if (dx == 0)
            return 0;
        double allowed = dx;

        int minX = (int) Math.floor(Math.min(box.getMinX(), box.getMinX() + dx));
        int maxX = (int) Math.floor(Math.max(box.getMaxX(), box.getMaxX() + dx));
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int y = minY; y <= maxY; y++) {
            if (y < w.getMinHeight() || y > w.getMaxHeight())
                continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isSolid())
                        continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb == null)
                        continue;

                    // Проверяем перекрытие по YZ для текущего box
                    if (box.getMaxY() <= bb.getMinY() || box.getMinY() >= bb.getMaxY())
                        continue;
                    if (box.getMaxZ() <= bb.getMinZ() || box.getMinZ() >= bb.getMaxZ())
                        continue;

                    if (dx > 0) {
                        double limit = bb.getMinX() - box.getMaxX() - EPS;
                        if (limit < allowed)
                            allowed = Math.max(limit, 0.0); // клиппинг вплоть до касания
                    } else {
                        double limit = bb.getMaxX() - box.getMinX() + EPS;
                        if (limit > allowed)
                            allowed = Math.min(limit, 0.0);
                    }
                    if (allowed == 0.0)
                        return 0.0; // дальше смысла нет
                }
            }
        }
        return allowed;
    }

    private double sweepAxisY(World w, BoundingBox box, double dy) {
        if (dy == 0)
            return 0;
        double allowed = dy;

        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(Math.min(box.getMinY(), box.getMinY() + dy));
        int maxY = (int) Math.floor(Math.max(box.getMaxY(), box.getMaxY() + dy));
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int y = minY; y <= maxY; y++) {
            if (y < w.getMinHeight() || y > w.getMaxHeight())
                continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isSolid())
                        continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb == null)
                        continue;

                    // Проверяем перекрытие по XZ
                    if (box.getMaxX() <= bb.getMinX() || box.getMinX() >= bb.getMaxX())
                        continue;
                    if (box.getMaxZ() <= bb.getMinZ() || box.getMinZ() >= bb.getMaxZ())
                        continue;

                    if (dy > 0) {
                        double limit = bb.getMinY() - box.getMaxY() - EPS;
                        if (limit < allowed)
                            allowed = Math.max(limit, 0.0);
                    } else {
                        double limit = bb.getMaxY() - box.getMinY() + EPS;
                        if (limit > allowed)
                            allowed = Math.min(limit, 0.0);
                    }
                    if (allowed == 0.0)
                        return 0.0;
                }
            }
        }
        return allowed;
    }

    private double sweepAxisZ(World w, BoundingBox box, double dz) {
        if (dz == 0)
            return 0;
        double allowed = dz;

        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(Math.min(box.getMinZ(), box.getMinZ() + dz));
        int maxZ = (int) Math.floor(Math.max(box.getMaxZ(), box.getMaxZ() + dz));

        for (int y = minY; y <= maxY; y++) {
            if (y < w.getMinHeight() || y > w.getMaxHeight())
                continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isSolid())
                        continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb == null)
                        continue;

                    // Проверяем перекрытие по XY
                    if (box.getMaxX() <= bb.getMinX() || box.getMinX() >= bb.getMaxX())
                        continue;
                    if (box.getMaxY() <= bb.getMinY() || box.getMinY() >= bb.getMaxY())
                        continue;

                    if (dz > 0) {
                        double limit = bb.getMinZ() - box.getMaxZ() - EPS;
                        if (limit < allowed)
                            allowed = Math.max(limit, 0.0);
                    } else {
                        double limit = bb.getMaxZ() - box.getMinZ() + EPS;
                        if (limit > allowed)
                            allowed = Math.min(limit, 0.0);
                    }
                    if (allowed == 0.0)
                        return 0.0;
                }
            }
        }
        return allowed;
    }

    private boolean overlapsSolid(World w, BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());
        for (int y = minY; y <= maxY; y++) {
            if (y < w.getMinHeight() || y > w.getMaxHeight())
                continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isSolid())
                        continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb != null && box.overlaps(bb))
                        return true;
                }
            }
        }
        return false;
    }

    /* ===================== Коллизии с мобами ===================== */

    private boolean intersectsAnyLiving(Entity self, BoundingBox box) {
        for (Entity e : self.getWorld().getNearbyEntities(box)) {
            if (e == self)
                continue;
            if (!(e instanceof LivingEntity))
                continue;
            if (!e.isValid())
                continue;
            BoundingBox bb = e.getBoundingBox();
            if (bb != null && box.overlaps(bb))
                return true;
        }
        return false;
    }

    /* ===================== Хитбоксы ===================== */

    private BoundingBox computeHitbox(Entity e, Location loc) {
        if (e instanceof Marker) {
            // Точка — почти нулевой AABB
            double eps = 1e-3;
            return BoundingBox.of(loc.toVector(), eps, eps, eps);
        }
        if (e instanceof ItemDisplay id) {
            Transformation t = id.getTransformation();
            var s = t.getScale();
            // ПОЛНЫЙ куб по scale
            double hx = Math.max(1e-3, s.x());
            double hy = Math.max(1e-3, s.y());
            double hz = Math.max(1e-3, s.z());
            return BoundingBox.of(loc.toVector(), hx * 0.5, hy * 0.5, hz * 0.5);
        }
        if (e instanceof BlockDisplay bd) {
            Transformation t = bd.getTransformation();
            var s = t.getScale();
            double hx = Math.max(1e-3, s.x());
            double hy = Math.max(1e-3, s.y());
            double hz = Math.max(1e-3, s.z());
            return BoundingBox.of(loc.toVector(), hx * 0.5, hy * 0.5, hz * 0.5);
        }
        return BoundingBox.of(loc.toVector(), 0.25, 0.25, 0.25);
    }

    private void setScoreboardTag(Entity e, String tag, boolean present) {
        Set<String> tags = e.getScoreboardTags();
        if (present) {
            if (!tags.contains(tag))
                e.addScoreboardTag(tag);
        } else {
            if (tags.contains(tag))
                e.removeScoreboardTag(tag);
        }
    }

    /* ===================== Поворот по скорости ===================== */

    private void applyFacing(Entity e, double vx, double vy, double vz) {
        // yaw/pitch как у игроков
        double h = Math.sqrt(vx * vx + vz * vz);
        float yaw = (float) Math.toDegrees(Math.atan2(-vx, vz));
        float pitch = (float) Math.toDegrees(-Math.atan2(vy, h));

        if (e instanceof Marker) {
            e.setRotation(yaw, pitch);
            return;
        }
        if (e instanceof ItemDisplay id) {
            Transformation t = id.getTransformation();
            // Кватернион: сначала yaw вокруг Y, затем pitch вокруг X
            Quaternionf q = new Quaternionf()
                    .rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(pitch));
            Transformation nt = new Transformation(t.getTranslation(), q, t.getScale(), new Quaternionf());
            id.setTransformation(nt);
        }
        // BlockDisplay не трогаем (не просили)
    }

    /*
     * ===================== NBT: чтение/запись корневого data:{}
     * =====================
     */

    private static final class PhysParams {
        final double[] motion = new double[3];
        double friction = DEFAULT_FRICTION;
        double airFriction = DEFAULT_AIR_FRICTION;
        double bounceness = DEFAULT_BOUNCENESS;
    }

    private static final String NBT_ROOT = "data";
    private static final String NBT_MOTION = "motion";
    private static final String NBT_FRICTION = "friction";
    private static final String NBT_AIR_FRICTION = "air_friction";
    private static final String NBT_BOUNCENESS = "bounceness";

    private static final double DEFAULT_FRICTION = 0.60;
    private static final double DEFAULT_AIR_FRICTION = 0.99;
    private static final double DEFAULT_BOUNCENESS = 0.00;

    private PhysParams readParamsFromEntityNbt(org.bukkit.entity.Entity bukkitEntity) {
        PhysParams p = new PhysParams();
        try {
            CompoundTag root = snapshotEntityNbt(bukkitEntity);

            // data:{...} — берём безопасно (или пустой, если нет)
            CompoundTag data = root.getCompoundOrEmpty(NBT_ROOT);

            // motion: список из 3 DoubleTag
            if (data.contains(NBT_MOTION)) {
                ListTag list = data.getListOrEmpty(NBT_MOTION);
                if (list.size() == 3
                        && list.get(0) instanceof DoubleTag d0
                        && list.get(1) instanceof DoubleTag d1
                        && list.get(2) instanceof DoubleTag d2) {
                    p.motion[0] = d0.doubleValue();
                    p.motion[1] = d1.doubleValue();
                    p.motion[2] = d2.doubleValue();
                }
            }

            p.friction = data.getDoubleOr(NBT_FRICTION, DEFAULT_FRICTION);
            p.airFriction = data.getDoubleOr(NBT_AIR_FRICTION, DEFAULT_AIR_FRICTION);
            p.bounceness = data.getDoubleOr(NBT_BOUNCENESS, DEFAULT_BOUNCENESS);

        } catch (Throwable t) {
            // оставляем дефолты
        }
        return p;
    }

    private void writeParamsToEntityNbt(org.bukkit.entity.Entity bukkitEntity, PhysParams p) {
        try {
            CompoundTag root = snapshotEntityNbt(bukkitEntity);
            CompoundTag data = root.getCompoundOrEmpty(NBT_ROOT); // безопасно вернёт пустой, если нет

            // Перезапишем значения
            ListTag motion = new ListTag();
            motion.add(DoubleTag.valueOf(p.motion[0]));
            motion.add(DoubleTag.valueOf(p.motion[1]));
            motion.add(DoubleTag.valueOf(p.motion[2]));
            data.put(NBT_MOTION, motion);

            data.putDouble(NBT_FRICTION, p.friction);
            data.putDouble(NBT_AIR_FRICTION, p.airFriction);
            data.putDouble(NBT_BOUNCENESS, p.bounceness);

            // Вставляем/обновляем data и применяем в сущность
            root.put(NBT_ROOT, data);
            applyEntityNbt(bukkitEntity, root);
        } catch (Throwable t) {
            // по требованию — НИКАКИХ fallback-ов
        }
    }

    private CompoundTag snapshotEntityNbt(Entity bukkitEntity) {
        var nms = ((CraftEntity) bukkitEntity).getHandle();
        try (ProblemReporter.ScopedCollector pr = new ProblemReporter.ScopedCollector(
                () -> "PhysicalObjectPhysics/snapshot", LOG)) {
            TagValueOutput out = TagValueOutput.createWithContext(pr, nms.registryAccess());
            // vanilla так делает в CraftEntity#save():
            out.putString(net.minecraft.world.entity.Entity.TAG_ID, nms.getEncodeId(true));
            nms.saveWithoutId(out);
            return out.buildResult();
        }
    }

    private void applyEntityNbt(org.bukkit.entity.Entity bukkitEntity, CompoundTag root) {
        var nms = ((CraftEntity) bukkitEntity).getHandle();
        try (ProblemReporter.ScopedCollector pr = new ProblemReporter.ScopedCollector(
                () -> "PhysicalObjectPhysics/apply", LOG)) {
            ValueInput in = TagValueInput.create(pr, nms.registryAccess(), root);
            nms.load(in);
        }
    }

    /*
     * ===================== Ниже старые AABB-утилиты (не используются теперь, но
     * можешь оставить) =====================
     */

    private static final class CollisionResult {
        boolean hit, blockX, blockY, blockZ, hitBottom;
    }

    private CollisionResult collideWithBlocks(World w, BoundingBox box, double dy) {
        CollisionResult res = new CollisionResult();
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());

        for (int y = minY; y <= maxY; y++) {
            if (y < w.getMinHeight() || y > w.getMaxHeight())
                continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isSolid())
                        continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb == null || !box.overlaps(bb))
                        continue;

                    res.hit = true;
                    double penX = overlap(box.getMinX(), box.getMaxX(), bb.getMinX(), bb.getMaxX());
                    double penY = overlap(box.getMinY(), box.getMaxY(), bb.getMinY(), bb.getMaxY());
                    double penZ = overlap(box.getMinZ(), box.getMaxZ(), bb.getMinZ(), bb.getMaxZ());

                    if (penX <= penY && penX <= penZ) {
                        res.blockX = true;
                    } else if (penY <= penX && penY <= penZ) {
                        res.blockY = true;
                        if (dy < 0 && (box.getMinY() >= bb.getMaxY() - 1e-3))
                            res.hitBottom = true;
                    } else {
                        res.blockZ = true;
                    }
                }
            }
        }
        return res;
    }

    private BoundingBox resolvePenetration(World w, BoundingBox prev, BoundingBox curr) {
        int minX = (int) Math.floor(curr.getMinX());
        int maxX = (int) Math.floor(curr.getMaxX());
        int minY = (int) Math.floor(curr.getMinY());
        int maxY = (int) Math.floor(curr.getMaxY());
        int minZ = (int) Math.floor(curr.getMinZ());
        int maxZ = (int) Math.floor(curr.getMaxZ());

        BoundingBox res = curr;

        for (int y = minY; y <= maxY; y++) {
            if (y < w.getMinHeight() || y > w.getMaxHeight())
                continue;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isSolid())
                        continue;
                    BoundingBox bb = b.getBoundingBox();
                    if (bb == null || !res.overlaps(bb))
                        continue;

                    double penX = overlap(res.getMinX(), res.getMaxX(), bb.getMinX(), bb.getMaxX());
                    double penY = overlap(res.getMinY(), res.getMaxY(), bb.getMinY(), bb.getMaxY());
                    double penZ = overlap(res.getMinZ(), res.getMaxZ(), bb.getMinZ(), bb.getMaxZ());

                    if (penX <= penY && penX <= penZ) {
                        if (res.getCenterX() >= bb.getCenterX())
                            res = res.shift(penX + 1e-4, 0, 0);
                        else
                            res = res.shift(-(penX + 1e-4), 0, 0);
                    } else if (penY <= penX && penY <= penZ) {
                        if (res.getCenterY() >= bb.getCenterY())
                            res = res.shift(0, penY + 1e-4, 0);
                        else
                            res = res.shift(0, -(penY + 1e-4), 0);
                    } else {
                        if (res.getCenterZ() >= bb.getCenterZ())
                            res = res.shift(0, 0, penZ + 1e-4);
                        else
                            res = res.shift(0, 0, -(penZ + 1e-4));
                    }
                }
            }
        }
        return res;
    }

    private static double overlap(double aMin, double aMax, double bMin, double bMax) {
        double l = Math.max(aMin, bMin), r = Math.min(aMax, bMax);
        return Math.max(0.0, r - l);
    }
}
