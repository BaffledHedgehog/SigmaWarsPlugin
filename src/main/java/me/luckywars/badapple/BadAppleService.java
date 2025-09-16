// src/main/java/me/luckywars/badapple/BadAppleService.java
package me.luckywars.badapple;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

// WorldEdit / FAWE (считаем, что всегда есть)
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSessionBuilder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;

public final class BadAppleService {
    // ======= ТЮНИНГ =======
    private static final int FRAME_PERIOD_TICKS = 2; // 1 кадр каждые 2 тика
    private static final int MAX_CHANGES_PER_TICK = 60000;
    private static final int INIT_CHANGES_PER_TICK = 60000;
    private static final int Y_REFRESH_PERIOD_TICKS = 100;

    private static final boolean DEBUG = true; // включить подробные логи
    private static final int DEBUG_TICKS_LIMIT = 200; // сколько тиков логировать подробно

    private static final String BADAPPLE_SOUND = "minecraft:lbcsounds.bad_apple";

    private final JavaPlugin plugin;

    // Геометрия из BAF2
    private FrameProvider provider;
    private int WIDTH; // X
    private int HEIGHT; // Z

    // Стейт
    private boolean running = false;
    private int frameIdx = 0;
    private BukkitRunnable frameTask;
    private BukkitRunnable yRefreshTask;

    private World world;
    private int centerX;
    private int centerZ;

    private int[] yMap; // size=WIDTH*HEIGHT, Integer.MIN_VALUE если нет столба
    private BitSet appliedFrame; // что реально стоит в мире
    private BitSet initPending; // что ещё надо покрасить при первом кадре

    private final AtomicBoolean soundPending = new AtomicBoolean(false);
    private final AtomicBoolean soundStarted = new AtomicBoolean(false);

    private int debugTickCounter = 0;

    public BadAppleService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public BadAppleCommand command() {
        return new BadAppleCommand(this);
    }

    private void log(String msg) {
        // plugin.getLogger().info("[BadApple] " + msg);
    }

    private void dbg(String msg) {
        // if (DEBUG)
        // plugin.getLogger().info("[BadApple][DBG] " + msg);
    }

    // ===== Команды =====

    public synchronized void start(Location trigger) {
        final long t0 = System.nanoTime();

        final Location origin = trigger.clone();
        this.world = origin.getWorld();
        if (this.world == null) {
            log("World is null, abort start");
            return;
        }

        final int px = origin.getBlockX();
        final int pz = origin.getBlockZ();
        if (px >= -192 && px <= 192 && pz >= -192 && pz <= 192) {
            this.centerX = 0;
            this.centerZ = 0;
        } else {
            this.centerX = px;
            this.centerZ = pz;
        }
        dbg("start(): center=(" + centerX + "," + centerZ + ") player=(" + px + "," + pz + ") world="
                + world.getName());

        if (!initProvider()) {
            // plugin.getLogger().warning("BadApple: не найден или не читается
            // plugins/lws/badapple.baf2");
            return;
        }

        log("BAF2: size=" + WIDTH + "x" + HEIGHT + ", frames=" + provider.frameCount());

        stopSoundAll();
        soundPending.set(true);
        soundStarted.set(false);

        this.running = true;
        this.frameIdx = 0;
        this.debugTickCounter = 0;

        this.appliedFrame = new BitSet(WIDTH * HEIGHT);
        this.yMap = new int[WIDTH * HEIGHT];
        Arrays.fill(this.yMap, Integer.MIN_VALUE);
        this.initPending = new BitSet(WIDTH * HEIGHT); // заполним после прогрева yMap

        warmupYMapThenStartFrames();
        startYRefreshTimer();

        log("started at X=" + centerX + " Z=" + centerZ + " world=" + world.getName()
                + " size=" + WIDTH + "x" + HEIGHT + " (start took " + msSince(t0) + " ms)");
    }

    public synchronized void stop() {
        if (frameTask != null) {
            frameTask.cancel();
            frameTask = null;
        }
        if (yRefreshTask != null) {
            yRefreshTask.cancel();
            yRefreshTask = null;
        }
        running = false;
        frameIdx = 0;
        stopSoundAll();
        soundPending.set(false);
        soundStarted.set(false);
        log("stopped.");
    }

    // ===== Провайдер BAF2 =====

    private boolean initProvider() {
        try {
            File dir = new File(plugin.getDataFolder().getParentFile(), "lws");
            File baf2 = new File(dir, "badapple.baf2");
            dbg("initProvider(): path=" + baf2.getAbsolutePath() + " exists=" + baf2.isFile());
            if (!baf2.isFile())
                return false;

            this.provider = new SafeFrameProvider(new DiffBinFrameProvider(baf2));
            this.WIDTH = provider.width();
            this.HEIGHT = provider.height();
            selfTestProvider();
            try {
                var md = java.security.MessageDigest.getInstance("SHA-1");
                try (var in = new java.io.FileInputStream(baf2)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1)
                        md.update(buf, 0, r);
                }
                log("BAF2 sha1=" + java.util.HexFormat.of().formatHex(md.digest()) +
                        " size=" + baf2.length());
            } catch (Throwable ignore) {
            }

            // sanity-check: попробуем дернуть кадр 0 и 1
            BitSet f0 = provider.getFrame(0);
            if (f0 == null) {
                log("Provider returned null for frame 0");
                return false;
            }
            BitSet f1 = provider.getFrame(1);
            if (f1 == null)
                dbg("Provider returned null for frame 1 (возможно 1 кадр или дельта пустая)");

            return provider.frameCount() > 0;
        } catch (Throwable t) {
            // plugin.getLogger().warning("BadApple: ошибка инициализации BAF2: " + t);
            return false;
        }
    }

    private void selfTestProvider() {
        int n = Math.min(provider.frameCount(), 64);
        BitSet prev = null;
        for (int i = 0; i < n; i++) {
            BitSet f = provider.getFrame(i);
            int ones = f == null ? -1 : f.cardinality();
            int diff = -1;
            if (f != null && prev != null) {
                BitSet t = (BitSet) f.clone();
                t.xor(prev);
                diff = t.cardinality();
            }
            // plugin.getLogger().info("[BadApple][SELFTEST] frame " + i + " ones=" + ones +
            // " diff=" + diff);
            prev = f;
        }
    }

    // ===== Таймеры =====

    private void startFrameTicker() {
        if (frameTask != null)
            frameTask.cancel();
        frameTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || world == null) {
                    cancel();
                    return;
                }
                try {
                    final boolean initLeft = (initPending != null && !initPending.isEmpty());
                    final long t0 = System.currentTimeMillis();

                    BitSet desired;
                    int reqIdx;

                    if (initLeft) {
                        reqIdx = 0;
                        desired = provider.getFrame(0); // всегда абсолютный первый
                    } else {
                        // нормальный цикл кадров
                        reqIdx = frameIdx;
                        if (reqIdx >= provider.frameCount())
                            reqIdx = 0;

                        BitSet got = provider.getFrame(reqIdx);
                        if (got == null) {
                            // ВАЖНО: null == «нет изменений», НЕ EOF
                            // подставляем текущее применённое состояние,
                            // чтобы delta вышла пустой и тик быстро завершился
                            desired = (appliedFrame != null)
                                    ? (BitSet) appliedFrame.clone()
                                    : new BitSet(WIDTH * HEIGHT);
                            // plugin.getLogger().info("[BadApple][DBG] tick: provider.getFrame(" + reqIdx
                            // + ") == null -> treat as NO-CHANGE frame");
                        } else {
                            desired = got;
                        }
                        frameIdx = (reqIdx + 1) % provider.frameCount();
                    }

                    // перед applyFrame(desired);
                    if (!initLeft) {
                        BitSet deltaProbe = (BitSet) desired.clone();
                        deltaProbe.xor(appliedFrame);
                        dbg("frame=" + reqIdx + " ones=" + desired.cardinality() + " deltaBits="
                                + deltaProbe.cardinality());
                    }

                    applyFrame(desired);
                } catch (Throwable t) {
                    // plugin.getLogger().warning("BadApple tick error: " + t);
                    cancel();
                }
            }
        };
        // оставляем асинхронный тикер
        frameTask.runTaskTimerAsynchronously(plugin, 1L, FRAME_PERIOD_TICKS);
    }

    private void startYRefreshTimer() {
        if (yRefreshTask != null)
            yRefreshTask.cancel();
        yRefreshTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || world == null)
                    return;
                refreshYMapAsync();
            }
        };
        yRefreshTask.runTaskTimerAsynchronously(plugin, 0L, Y_REFRESH_PERIOD_TICKS);
        dbg("startYRefreshTimer(): every " + Y_REFRESH_PERIOD_TICKS + " ticks");
    }

    // ===== Проекция =====

    private static final class ColumnChange {
        final int x, z, y;
        final boolean white;

        ColumnChange(int x, int z, int y, boolean white) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.white = white;
        }
    }

    private void applyFrame(BitSet desired) {
        final int halfW = WIDTH / 2;
        final int halfH = HEIGHT / 2;
        final boolean initMode = (initPending != null && !initPending.isEmpty());

        int budget = initMode ? INIT_CHANGES_PER_TICK : MAX_CHANGES_PER_TICK;
        final HashMap<Long, ArrayList<ColumnChange>> byChunk = new HashMap<>();

        final int initCard = (initPending != null) ? initPending.cardinality() : 0;

        if (initMode) {
            for (int idx = initPending.nextSetBit(0); idx >= 0 && budget > 0; idx = initPending.nextSetBit(idx + 1)) {

                int y = yMap[idx];
                if (y == Integer.MIN_VALUE)
                    continue;

                final int xOff = idx % WIDTH, zOff = idx / WIDTH;
                final int wx = centerX - halfW + xOff;
                final int wz = centerZ - halfH + zOff;

                final int cx = wx >> 4, cz = wz >> 4;
                long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                // ставим ЧЁРНЫЙ без чтений
                byChunk.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new ColumnChange(wx, wz, y, /* white= */false));

                appliedFrame.clear(idx); // чёрный
                initPending.clear(idx);
                budget--;
            }
        } else {
            BitSet delta = (BitSet) desired.clone();
            delta.xor(appliedFrame);
            final int deltaCount = delta.cardinality();
            if (debugTickCounter < DEBUG_TICKS_LIMIT) {
                dbg("applyFrame: DELTA count=" + deltaCount + " budget=" + budget);
            }
            if (delta.isEmpty()) {
                if (soundPending.compareAndSet(true, false)) {
                    Bukkit.getScheduler().runTask(plugin, this::playSoundAll);
                    dbg("applyFrame: delta empty -> start sound");
                }
                return;
            }
            for (int idx = delta.nextSetBit(0); idx >= 0 && budget > 0; idx = delta.nextSetBit(idx + 1)) {
                int y = yMap[idx];
                if (y == Integer.MIN_VALUE)
                    continue;

                final int xOff = idx % WIDTH;
                final int zOff = idx / WIDTH;
                final int wx = centerX - halfW + xOff;
                final int wz = centerZ - halfH + zOff;
                final boolean white = desired.get(idx);

                final int cx = wx >> 4, cz = wz >> 4;
                long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new ColumnChange(wx, wz, y, white));

                appliedFrame.set(idx, white);
                budget--;
            }
        }

        final boolean initDoneNow = (initPending != null && initPending.isEmpty());
        final boolean needBarrierForSound = (!soundStarted.get()) && initDoneNow && soundPending.get();

        if (debugTickCounter < DEBUG_TICKS_LIMIT) {
            dbg("applyFrame: grouped chunks=" + byChunk.size() +
                    " initPendingNow=" + (initPending != null ? initPending.cardinality() : 0) +
                    " needBarrierForSound=" + needBarrierForSound);
        }

        if (byChunk.isEmpty()) {
            if (needBarrierForSound && soundPending.compareAndSet(true, false)) {
                Bukkit.getScheduler().runTask(plugin, this::playSoundAll);
                dbg("applyFrame: no changes but init done -> start sound");
            }
            return;
        }

        final AtomicInteger remaining = needBarrierForSound ? new AtomicInteger(byChunk.size()) : null;

        for (Map.Entry<Long, ArrayList<ColumnChange>> e : byChunk.entrySet()) {
            final long key = e.getKey();
            final int cx = (int) (key >> 32);
            final int cz = (int) key;
            final List<ColumnChange> list = e.getValue();

            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                final long tChunk0 = System.nanoTime();
                int whitesN = 0, blacksN = 0, skipped = 0;
                try {
                    if (!world.isChunkLoaded(cx, cz)) {
                        dbg("chunk (" + cx + "," + cz + ") not loaded, skip");
                        return;
                    }

                    final ArrayList<BlockVector3> whites = new ArrayList<>();
                    final ArrayList<BlockVector3> blacks = new ArrayList<>();
                    final boolean forcePlace = initMode;

                    for (ColumnChange ch : list) {
                        if (forcePlace) {
                            (ch.white ? whites : blacks).add(BlockVector3.at(ch.x, ch.y, ch.z));
                        } else {
                            Block b = world.getBlockAt(ch.x, ch.y, ch.z);
                            Material want = ch.white ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE;
                            if (b.getType() == want) {
                                skipped++;
                                continue;
                            }
                            (ch.white ? whites : blacks).add(BlockVector3.at(ch.x, ch.y, ch.z));
                        }
                    }

                    whitesN = whites.size();
                    blacksN = blacks.size();

                    if (!whites.isEmpty() || !blacks.isEmpty()) {
                        try (EditSession es = newEditSession(world)) {
                            // максимально «жёсткий» режим, чтобы не трогать SQLite/rollback
                            try {
                                es.setTrackingHistory(false);
                            } catch (Throwable ignore) {
                            }
                            try {
                                es.setReorderMode(EditSession.ReorderMode.NONE);
                            } catch (Throwable ignore) {
                            }
                            try {
                                es.setBatchingChunks(true);
                            } catch (Throwable ignore) {
                            }
                            try {
                                es.setSideEffectApplier(com.sk89q.worldedit.util.SideEffectSet.none());
                            } catch (Throwable ignore) {
                            }

                            final var W = BlockTypes.WHITE_CONCRETE.getDefaultState();
                            final var B = BlockTypes.BLACK_CONCRETE.getDefaultState();

                            for (BlockVector3 p : whites)
                                es.rawSetBlock(p, W);
                            for (BlockVector3 p : blacks)
                                es.rawSetBlock(p, B);
                            if (!forcePlace) {
                                es.close();
                            }
                        } catch (Throwable t) {
                            // plugin.getLogger().warning("BadApple/FAWE chunk (" + cx + "," + cz + "): " +
                            // t);
                        }
                    }
                } finally {
                    if (debugTickCounter < DEBUG_TICKS_LIMIT) {
                        dbg("applied chunk (" + cx + "," + cz + "): whites=" + whitesN + " blacks=" + blacksN +
                                " skipped=" + skipped + " took=" + msSince(tChunk0) + " ms");
                    }
                    try {
                        world.refreshChunk(cx, cz);
                    } catch (Throwable ignored) {
                    }
                    if (needBarrierForSound && remaining != null && remaining.decrementAndGet() == 0) {
                        if (soundPending.compareAndSet(true, false)) {
                            Bukkit.getScheduler().runTask(plugin, this::playSoundAll);
                            dbg("barrier complete -> start sound");
                        }
                    }
                }
            });
        }
    }

    private EditSession newEditSession(World world) {
        EditSession es = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(BukkitAdapter.adapt(world))
                .maxBlocks(-1)
                .build();

        // 1) Полностью выключаем историю (чтобы FAWE не трогал SQLite)
        try {
            es.setTrackingHistory(false);
        } catch (Throwable ignore) {
        }

        //// Вариант А (проще, но предупреждение deprecation — можно игнорировать):
        // try {
        // es.setReorderMode(EditSession.ReorderMode.NONE);
        // } catch (Throwable ignore) {
        // }

        // Вариант Б (без предупреждения): отключить буферы, а потом при желании снова
        // включить чанковый батчинг
        // try {
        // es.disableBuffering();
        // } catch (Throwable ignore) {
        // }
        try {
            es.setBatchingChunks(true);
        } catch (Throwable ignore) {
        }

        // 2) Без апдейтов/света/тикеров
        // try {
        // es.setSideEffectApplier(com.sk89q.worldedit.util.SideEffectSet.none());
        // } catch (Throwable ignore) {
        // }
        // (Опционально) меньше оверхеда от вотчдога:
        // try {
        // es.setTickingWatchdog(false);
        // } catch (Throwable ignore) {
        // }

        return es;
    }

    // ===== Карта высот =====

    private void warmupYMapThenStartFrames() {
        if (world == null)
            return;
        final int halfW = WIDTH / 2;
        final int halfH = HEIGHT / 2;

        final HashMap<Long, ArrayList<int[]>> byChunk = new HashMap<>();
        for (int zOff = 0; zOff < HEIGHT; zOff++) {
            int wz = centerZ - halfH + zOff;
            for (int xOff = 0; xOff < WIDTH; xOff++) {
                int wx = centerX - halfW + xOff;
                int cx = wx >> 4, cz = wz >> 4;
                long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] { xOff, zOff, wx, wz });
            }
        }

        log("warmupYMap: totalPixels=" + (WIDTH * HEIGHT) + " chunks=" + byChunk.size());
        final long t0 = System.nanoTime();

        final AtomicInteger remaining = new AtomicInteger(byChunk.size());
        if (remaining.get() == 0) {
            buildInitPendingMask();
            startFrameTicker();
            return;
        }

        for (Map.Entry<Long, ArrayList<int[]>> e : byChunk.entrySet()) {
            final long key = e.getKey();
            final int cx = (int) (key >> 32);
            final int cz = (int) key;
            final ArrayList<int[]> list = e.getValue();

            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                int done = 0;
                if (!world.isChunkLoaded(cx, cz)) {
                    dbg("warmup: chunk (" + cx + "," + cz + ") not loaded");
                    if (remaining.decrementAndGet() == 0)
                        onWarmupDone(t0);
                    return;
                }
                for (int[] it : list) {
                    int xOff = it[0], zOff = it[1], wx = it[2], wz = it[3];
                    int y = findTopSolid(wx, wz);
                    yMap[zOff * WIDTH + xOff] = y;
                    done++;
                }
                if (debugTickCounter < DEBUG_TICKS_LIMIT) {
                    dbg("warmup: chunk (" + cx + "," + cz + ") filled=" + done + " remain=" + (remaining.get() - 1));
                }
                if (remaining.decrementAndGet() == 0)
                    onWarmupDone(t0);
            });
        }
    }

    private void onWarmupDone(long t0) {
        buildInitPendingMask();
        int valid = 0, invalid = 0;
        for (int v : yMap)
            if (v == Integer.MIN_VALUE)
                invalid++;
            else
                valid++;
        log("warmup done in " + msSince(t0) + " ms; columns valid=" + valid + " invalid=" + invalid);
        Bukkit.getScheduler().runTask(plugin, this::startFrameTicker);
    }

    private void onWarmupDone() {
        onWarmupDone(System.nanoTime());
    }

    private void buildInitPendingMask() {
        initPending.clear();
        final int N = WIDTH * HEIGHT;
        for (int i = 0; i < N; i++)
            if (yMap[i] != Integer.MIN_VALUE)
                initPending.set(i);
        dbg("buildInitPendingMask: set=" + initPending.cardinality() + " of " + N);
    }

    private void refreshYMapAsync() {
        if (world == null)
            return;
        final int halfW = WIDTH / 2;
        final int halfH = HEIGHT / 2;

        final HashMap<Long, ArrayList<int[]>> byChunk = new HashMap<>();
        for (int zOff = 0; zOff < HEIGHT; zOff++) {
            int wz = centerZ - halfH + zOff;
            for (int xOff = 0; xOff < WIDTH; xOff++) {
                int wx = centerX - halfW + xOff;
                int cx = wx >> 4, cz = wz >> 4;
                long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
                byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[] { xOff, zOff, wx, wz });
            }
        }

        dbg("refreshYMapAsync: chunks=" + byChunk.size());
        for (Map.Entry<Long, ArrayList<int[]>> e : byChunk.entrySet()) {
            final long key = e.getKey();
            final int cx = (int) (key >> 32);
            final int cz = (int) key;
            final ArrayList<int[]> list = e.getValue();

            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                if (!world.isChunkLoaded(cx, cz))
                    return;
                int done = 0;
                for (int[] it : list) {
                    int xOff = it[0], zOff = it[1], wx = it[2], wz = it[3];
                    int y = findTopSolid(wx, wz);
                    yMap[zOff * WIDTH + xOff] = y;
                    done++;
                }
                if (debugTickCounter < DEBUG_TICKS_LIMIT) {
                    dbg("refresh: chunk (" + cx + "," + cz + ") refreshed=" + done);
                }
            });
        }
    }

    private int findTopSolid(int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        y = Math.min(y, world.getMaxHeight() - 1);
        for (int yy = y; yy >= Math.max(world.getMinHeight(), y - 96); yy--) {
            if (world.getBlockAt(x, yy, z).getType().isSolid())
                return yy;
        }
        return Integer.MIN_VALUE;
    }

    // ===== Звук =====

    private void playSoundAll() {
        if (soundStarted.get())
            return;
        log("playSoundAll()");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), BADAPPLE_SOUND, SoundCategory.MASTER, 100000f, 1.0f);
        }
        soundStarted.set(true);
    }

    private void stopSoundAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (SoundCategory cat : SoundCategory.values()) {
                p.stopSound(BADAPPLE_SOUND, cat);
            }
        }
    }

    // ===== Утилиты =====
    private static long msSince(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
