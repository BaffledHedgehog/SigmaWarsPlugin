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

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

// WorldEdit / FAWE (считаем, что всегда есть)
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EditSessionBuilder;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.fastasyncworldedit.core.history.changeset.NullChangeSet;

public final class BadAppleService {
    // ======= ТЮНИНГ =======
    private static final int FRAME_PERIOD_TICKS = 2; // 1 кадр каждые 2 тика
    private static final int MAX_CHANGES_PER_TICK = 60000;
    // Кэш до 3 «этажей» ниже для каждой колонки: [idx*3 + 0..2], Integer.MIN_VALUE
    // если нет
    private int[] floors3; // размер WIDTH*HEIGHT*3, аллоцируем в start()

    // Листенер для отслеживания изменений блоков/взрывов в нашем мире
    private Listener blockWatch;

    private static final String BADAPPLE_SOUND = "minecraft:lbcsounds.bad_apple";

    private final JavaPlugin plugin;
    // синхронизация старта звука после первого кадра
    private final AtomicInteger firstFrameLatch = new AtomicInteger(0);
    private final AtomicBoolean firstFrameArmed = new AtomicBoolean(false);
    // Кэш EditSession по чанку (cx,cz). Ключ: ((long)cx<<32)^(cz&0xffffffffL)
    private final ConcurrentHashMap<Long, EditSession> esByChunk = new ConcurrentHashMap<>();

    // Геометрия из BAF2
    private FrameProvider provider;
    private int WIDTH; // X
    private int HEIGHT; // Z

    // (опционально) Предзагрузка провайдера на старте сервера, чтобы не тратить
    // время при /start
    private static volatile FrameProvider PRELOADED;

    public static void preloadBAF(JavaPlugin plugin) {
        if (PRELOADED instanceof AsyncPreloadingProvider)
            return;
        try {
            File dir = new File(plugin.getDataFolder().getParentFile(), "lws");
            File baf2 = new File(dir, "badapple.baf2");
            if (!baf2.isFile())
                return;

            FrameProvider base = new SafeFrameProvider(new DiffBinFrameProvider(baf2));
            PRELOADED = AsyncPreloadingProvider.start(plugin, base, /* warmFirst */ 240);
        } catch (Throwable ignore) {
        }
    }

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

    private final AtomicBoolean soundStarted = new AtomicBoolean(false);

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

        // Жёсткий сброс всего перед новым стартом
        resetForRestart();

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

        if (!initProvider())
            return;

        log("BAF2: size=" + WIDTH + "x" + HEIGHT + ", frames=" + provider.frameCount());

        stopSoundAll();
        soundStarted.set(false);

        this.running = true;
        this.frameIdx = 0;

        // baseline выставит applyFrame() на первом тике
        this.appliedFrame = null;

        this.yMap = new int[WIDTH * HEIGHT];
        Arrays.fill(this.yMap, Integer.MIN_VALUE);

        // Кэш этажей: по умолчанию пустой
        this.floors3 = new int[WIDTH * HEIGHT * 3];
        Arrays.fill(this.floors3, Integer.MIN_VALUE);

        // Регистрируем вотчер за изменениями столбцов
        registerBlockWatch();

        warmupYMapThenStartFrames();

        log("started at X=" + centerX + " Z=" + centerZ + " world=" + world.getName()
                + " size=" + WIDTH + "x" + HEIGHT + " (start took " + msSince(t0) + " ms)");
    }

    public synchronized void stop() {
        unregisterBlockWatch();
        for (EditSession es : esByChunk.values()) {
            try {
                es.close();
            } catch (Throwable ignored) {
            }
        }
        esByChunk.clear();

        resetForRestart();
        stopSoundAll();
        log("stopped.");
    }

    // ===== Провайдер BAF2 =====

    private boolean initProvider() {
        try {
            if (PRELOADED != null) {
                this.provider = PRELOADED;
            } else {
                File dir = new File(plugin.getDataFolder().getParentFile(), "lws");
                File baf2 = new File(dir, "badapple.baf2");
                dbg("initProvider(): path=" + baf2.getAbsolutePath() + " exists=" + baf2.isFile());
                if (!baf2.isFile())
                    return false;
                this.provider = new SafeFrameProvider(new DiffBinFrameProvider(baf2));
            }
            this.WIDTH = provider.width();
            this.HEIGHT = provider.height();
            // никакого чтения кадров на старте — только лёгкая проверка:
            return provider.frameCount() > 0 && WIDTH > 0 && HEIGHT > 0;
        } catch (Throwable t) {
            return false;
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
                    final long t0 = System.currentTimeMillis();

                    // Обычный цикл кадров, без initLeft
                    int reqIdx = frameIdx;
                    if (reqIdx >= provider.frameCount())
                        reqIdx = 0;

                    BitSet got = provider.getFrame(reqIdx);
                    final BitSet desired;
                    if (got == null) {
                        // null трактуем как "нет изменений"
                        desired = (appliedFrame != null)
                                ? (BitSet) appliedFrame.clone()
                                : new BitSet(WIDTH * HEIGHT);
                    } else {
                        desired = got;
                    }

                    // DEBUG дельта-проба (аккуратно с null)
                    if (appliedFrame != null) {
                        BitSet deltaProbe = (BitSet) desired.clone();
                        deltaProbe.xor(appliedFrame);
                        dbg("frame=" + reqIdx + " ones=" + desired.cardinality() + " deltaBits="
                                + deltaProbe.cardinality());
                    } else {
                        dbg("frame=" + reqIdx + " ones=" + desired.cardinality() + " (baseline pending)");
                    }

                    applyFrame(desired);

                    // следующий кадр
                    frameIdx = (reqIdx + 1) % provider.frameCount();
                } catch (Throwable t) {
                    cancel();
                }
            }
        };
        frameTask.runTaskTimerAsynchronously(plugin, 1L, FRAME_PERIOD_TICKS);
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

    // ПАТЧ: стартуем звук сразу после RAW-инициализации, если первый кадр пустой
    // (Δ=0),
    // и по-прежнему выходим мгновенно при Δ=0 вне init.
    // ДЕЛТА-РЕНДЕР: ничего не делаем при Δ=0, никаких "первых заливок".
    // БЕЗ чтений мира (никаких b.getType()==...), "raw" ставим только там,
    // где бит изменился относительно appliedFrame.
    // ДЕЛТА-РЕНДЕР: вообще ничего не делаем при Δ=0. Без "первой заливки".
    // ДЕЛТА-РЕНДЕР + "этажи ниже": Δ=0 — no-op; при изменении колонки
    // красим верхний блок и до 3 кэшированных поверхностей ниже (если есть).
    // ДЕЛТА-рендер с кэшом EditSession по чанку и корректным flush в конце
    @SuppressWarnings("null")
    private void applyFrame(BitSet desired) {
        if (world == null || WIDTH <= 0 || HEIGHT <= 0)
            return;

        // если пришёл null на самом первом кадре — трактуем как пустой,
        // но всё равно стартуем звук
        if (desired == null && appliedFrame == null) {
            appliedFrame = new BitSet(WIDTH * HEIGHT);
            // в applyFrame() — оба места, где ты пытаешься стартануть звук:
            if (soundStarted.compareAndSet(false, true)) {
                Bukkit.getScheduler().runTask(plugin, this::playSoundAll);
            }

            return;
        }

        final int halfW = WIDTH / 2;
        final int halfH = HEIGHT / 2;

        // ПЕРВЫЙ вызов: создаём baseline И сразу запускаем звук
        if (appliedFrame == null) {
            appliedFrame = (desired != null) ? (BitSet) desired.clone() : new BitSet(WIDTH * HEIGHT);
            // в applyFrame() — оба места, где ты пытаешься стартануть звук:
            if (soundStarted.compareAndSet(false, true)) {
                Bukkit.getScheduler().runTask(plugin, this::playSoundAll);
            }

            return;
        }

        // Δ=0 => no-op
        if (desired.equals(appliedFrame))
            return;

        // Дельта
        final BitSet delta = (BitSet) desired.clone();
        delta.xor(appliedFrame);
        if (delta.isEmpty())
            return;

        int budget = MAX_CHANGES_PER_TICK;
        final HashMap<Long, ArrayList<ColumnChange>> byChunk = new HashMap<>();

        final int W = WIDTH;
        for (int idx = delta.nextSetBit(0); idx >= 0 && budget > 0; idx = delta.nextSetBit(idx + 1)) {
            final int xOff = idx % W;
            final int zOff = idx / W;
            final int wx = centerX - halfW + xOff;
            final int wz = centerZ - halfH + zOff;

            final int cx = wx >> 4, cz = wz >> 4;
            final long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
            final boolean white = desired.get(idx);

            byChunk.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new ColumnChange(wx, wz, /* y lazy */ Integer.MIN_VALUE, white));

            // фиксируем новое ожидаемое состояние сразу (чтобы следующий кадр видел базу)
            if (white)
                appliedFrame.set(idx);
            else
                appliedFrame.clear(idx);

            budget--;
        }
        if (byChunk.isEmpty())
            return;

        // Чанковые задачи
        for (Map.Entry<Long, ArrayList<ColumnChange>> e : byChunk.entrySet()) {
            final long key = e.getKey();
            final ArrayList<ColumnChange> list = e.getValue();
            final int cx = (int) (key >> 32);
            final int cz = (int) (key & 0xffffffffL);

            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                if (!world.isChunkLoaded(cx, cz))
                    return;

                // ВАЖНО: получаем/реюзаем один EditSession на чанк, не закрываем его здесь
                EditSession es = getOrCreateSession(cx, cz);

                final var WHT = BlockTypes.WHITE_CONCRETE.getDefaultState();
                final var BLK = BlockTypes.BLACK_CONCRETE.getDefaultState();

                // Один раз на батч по чанку
                final boolean lockBeds = isBedwarsLockEnabled();

                for (ColumnChange ch : list) {
                    final int idxCol = colIndex(ch.x, ch.z);

                    int yTop = (idxCol >= 0 && yMap != null) ? yMap[idxCol] : Integer.MIN_VALUE;
                    if (yTop == Integer.MIN_VALUE) {
                        yTop = world.getHighestBlockYAt(ch.x, ch.z, HeightMap.WORLD_SURFACE);
                        if (idxCol >= 0 && yMap != null)
                            yMap[idxCol] = yTop;
                    }

                    // гарантируем кэш этажей
                    if (idxCol >= 0 && floors3 != null) {
                        recomputeFloorsForColumn(idxCol, ch.x, ch.z);
                    }

                    // верх: не трогаем, если там кровать и бедварс-лок активен
                    if (!(lockBeds && isBedBlock(world.getBlockAt(ch.x, yTop, ch.z).getType()))) {
                        es.rawSetBlock(BlockVector3.at(ch.x, yTop, ch.z), ch.white ? WHT : BLK);
                    }

                    // этажи ниже (до 3): аналогично
                    if (idxCol >= 0 && floors3 != null) {
                        final int base = idxCol * 3;
                        final int y1 = floors3[base];
                        final int y2 = floors3[base + 1];
                        final int y3 = floors3[base + 2];

                        if (y1 != Integer.MIN_VALUE && y1 != yTop) {
                            if (!(lockBeds && isBedBlock(world.getBlockAt(ch.x, y1, ch.z).getType()))) {
                                es.rawSetBlock(BlockVector3.at(ch.x, y1, ch.z), ch.white ? WHT : BLK);
                            }
                        }
                        if (y2 != Integer.MIN_VALUE && y2 != yTop) {
                            if (!(lockBeds && isBedBlock(world.getBlockAt(ch.x, y2, ch.z).getType()))) {
                                es.rawSetBlock(BlockVector3.at(ch.x, y2, ch.z), ch.white ? WHT : BLK);
                            }
                        }
                        if (y3 != Integer.MIN_VALUE && y3 != yTop) {
                            if (!(lockBeds && isBedBlock(world.getBlockAt(ch.x, y3, ch.z).getType()))) {
                                es.rawSetBlock(BlockVector3.at(ch.x, y3, ch.z), ch.white ? WHT : BLK);
                            }
                        }
                    }
                }

                // ВАЖНО: flush после всего батча по чанку
                try {
                    es.flushQueue();
                } catch (Throwable ignored) {
                }

                // Часто лишний оверхед, FAWE сам пушит — сначала попробуй без этого
                // try { world.refreshChunk(cx, cz); } catch (Throwable ignored) {}
            });
        }
    }

    private EditSession getOrCreateSession(int cx, int cz) {
        long key = (((long) cx) << 32) ^ (cz & 0xffffffffL);
        return esByChunk.computeIfAbsent(key, k -> {
            EditSession es = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(BukkitAdapter.adapt(world))
                    .maxBlocks(-1)
                    .build();
            try {
                es.setTrackingHistory(false);
            } catch (Throwable ignored) {
            }
            try {
                es.setReorderMode(EditSession.ReorderMode.NONE);
            } catch (Throwable ignored) {
            }
            try {
                es.setBatchingChunks(true);
            } catch (Throwable ignored) {
            }
            try {
                es.setSideEffectApplier(com.sk89q.worldedit.util.SideEffectSet.none());
            } catch (Throwable ignored) {
            }
            return es;
        });
    }

    // ===== Карта высот =====

    private void warmupYMapThenStartFrames() {
        // Не ждём глобальный прогрев: сразу стартуем тикер кадров
        Bukkit.getScheduler().runTask(plugin, this::startFrameTicker);

        // При желании можно оставить фоновое обновление Y-карты таймером (у тебя уже
        // есть refreshYMapAsync()).
        // Здесь больше ничего не делаем.
    }

    // Сбрасывает ВЕСЬ рантайм: чтобы новый старт шёл с нуля.
    private void resetForRestart() {
        try {
            cancelFrameTaskIfAny();
        } catch (Throwable ignore) {
        } // если есть свой таск — отмени

        // Обнуление "рендерного" состояния
        appliedFrame = null; // ключевое: иначе Δ=0 навсегда и ничего не рисуется
        try {
            soundStarted.set(false);
        } catch (Throwable ignore) {
        }
        try {
            frameIdx = 0;
        } catch (Throwable ignore) {
        }

        firstFrameLatch.set(0);
        firstFrameArmed.set(false);

        dbg("resetForRestart: state cleared");
    }
    // ===== Звук =====

    private void playSoundAll() {
        // без проверок флага здесь — он уже true, задача одна
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), BADAPPLE_SOUND, SoundCategory.MASTER, 100000f, 1.0f);
        }
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

    // Гасим таймеры, если есть
    private void cancelFrameTaskIfAny() {
        if (frameTask != null) {
            frameTask.cancel();
            frameTask = null;
        }
        if (yRefreshTask != null) {
            yRefreshTask.cancel();
            yRefreshTask = null;
        }
    }

    // index колонки по мировым координатам; -1 если вне прямоугольника
    private int colIndex(int wx, int wz) {
        final int halfW = WIDTH / 2, halfH = HEIGHT / 2;
        final int xOff = wx - (centerX - halfW);
        final int zOff = wz - (centerZ - halfH);
        if (xOff < 0 || xOff >= WIDTH || zOff < 0 || zOff >= HEIGHT)
            return -1;
        return zOff * WIDTH + xOff;
    }

    // Пересчитать yMap[idx] и floors3[idx*3..+2] для одной колонки (до 64 блоков
    // вниз)
    private void recomputeFloorsForColumn(int idx, int wx, int wz) {
        if (idx < 0)
            return;

        // верхняя «поверхность» = WORLD_SURFACE (может быть не solid — нам и надо)
        int yTop = world.getHighestBlockYAt(wx, wz, HeightMap.WORLD_SURFACE);
        yMap[idx] = yTop;

        // Скан вниз: ищем до 3 следующих поверхностей (границ "воздух -> блок")
        final int base = idx * 3;
        floors3[base] = floors3[base + 1] = floors3[base + 2] = Integer.MIN_VALUE;

        int found = 0;
        int minY = Math.max(world.getMinHeight(), yTop - 64);
        int yy = yTop - 2; // сразу ниже видимой поверхности

        while (yy >= minY && found < 3) {
            // спускаемся через воздух
            while (yy >= minY && !world.getBlockAt(wx, yy, wz).getType().isSolid()) {
                yy--;
            }
            if (yy < minY)
                break;

            // сейчас стоим на solid — проверим, что сверху воздух (граница «поверхность»)
            boolean airAbove = (yy + 1 <= world.getMaxHeight() - 1)
                    && !world.getBlockAt(wx, yy + 1, wz).getType().isSolid();
            if (airAbove) {
                floors3[base + found] = yy;
                found++;
            }

            // пропускаем массив solid
            while (yy >= minY && world.getBlockAt(wx, yy, wz).getType().isSolid()) {
                yy--;
            }
        }
    }

    private void registerBlockWatch() {
        if (blockWatch != null)
            return;
        blockWatch = new Listener() {
            @EventHandler(ignoreCancelled = true)
            public void onPlace(BlockPlaceEvent e) {
                if (world != null && e.getBlock().getWorld().equals(world)) {
                    // после установки блок уже на месте — но держим всё единообразно: на следующий
                    // тик
                    Block b = e.getBlock();
                    scheduleRecomputeColumn(b.getX(), b.getZ());
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void onBreak(BlockBreakEvent e) {
                if (world != null && e.getBlock().getWorld().equals(world)) {
                    // важно: лом срабатывает ДО фактического удаления -> переносим на следующий тик
                    Block b = e.getBlock();
                    scheduleRecomputeColumn(b.getX(), b.getZ());
                }
            }

            @EventHandler(ignoreCancelled = true)
            public void onEntityExplode(EntityExplodeEvent e) {
                if (world == null || !e.getEntity().getWorld().equals(world))
                    return;
                final HashSet<Long> cols = new HashSet<>();
                for (Block b : e.blockList())
                    cols.add(pack(b.getX(), b.getZ()));
                scheduleRecomputeColumns(cols);
            }

            @EventHandler(ignoreCancelled = true)
            public void onBlockExplode(BlockExplodeEvent e) {
                if (world == null || !e.getBlock().getWorld().equals(world))
                    return;
                final HashSet<Long> cols = new HashSet<>();
                for (Block b : e.blockList())
                    cols.add(pack(b.getX(), b.getZ()));
                scheduleRecomputeColumns(cols);
            }
        };
        Bukkit.getPluginManager().registerEvents(blockWatch, plugin);
    }

    private void unregisterBlockWatch() {
        if (blockWatch == null)
            return;
        org.bukkit.event.HandlerList.unregisterAll(blockWatch);
        blockWatch = null;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private void scheduleRecomputeColumns(Set<Long> cols) {
        if (cols == null || cols.isEmpty())
            return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (long key : cols) {
                int wx = (int) (key >> 32);
                int wz = (int) key;
                int idx = colIndex(wx, wz);
                if (idx != -1)
                    recomputeFloorsForColumn(idx, wx, wz);
            }
        });
    }

    private void scheduleRecomputeColumn(int wx, int wz) {
        HashSet<Long> set = new HashSet<>(1);
        set.add(pack(wx, wz));
        scheduleRecomputeColumns(set);
    }

    // Полный кеш всех кадров. Для кадров "без изменений" переиспользуем ссылку на
    // предыдущий кадр.
    // Асинхронное полное кеширование всех кадров без блокировок главного треда.
    // Асинхронное полное кеширование всех кадров без блокировки главного треда.
    private static final class AsyncPreloadingProvider implements FrameProvider {
        private final FrameProvider delegate;
        private final int w, h, fc;
        private final BitSet[] cache; // null => ещё не готов
        private volatile boolean preloadDone = false;

        private AsyncPreloadingProvider(FrameProvider delegate) {
            this.delegate = delegate;
            this.w = delegate.width();
            this.h = delegate.height();
            this.fc = delegate.frameCount();
            this.cache = new BitSet[fc];
        }

        static AsyncPreloadingProvider start(JavaPlugin plugin, FrameProvider base, int warmFirst) {
            AsyncPreloadingProvider p = new AsyncPreloadingProvider(base);

            new BukkitRunnable() {
                @Override
                public void run() {
                    try {
                        int n = Math.min(Math.max(warmFirst, 0), p.fc);
                        BitSet prev = null;

                        // 1) быстрый прогрев первых N кадров
                        for (int i = 0; i < n; i++) {
                            BitSet f = base.getFrame(i);
                            BitSet fr = (f != null) ? (BitSet) f.clone()
                                    : (prev != null ? (BitSet) prev.clone() : new BitSet(p.w * p.h));
                            p.cache[i] = fr;
                            prev = fr;
                            if ((i & 15) == 0)
                                Thread.yield();
                        }

                        // 2) полный прогрев остальных
                        for (int i = n; i < p.fc; i++) {
                            BitSet f = base.getFrame(i);
                            BitSet fr;
                            if (f != null) {
                                fr = (BitSet) f.clone();
                            } else {
                                BitSet prev2 = (i > 0 && p.cache[i - 1] != null) ? p.cache[i - 1]
                                        : (prev != null ? prev : new BitSet(p.w * p.h));
                                fr = (BitSet) prev2.clone();
                            }
                            p.cache[i] = fr;
                            prev = fr;

                            // легкий прогресс-лог раз в 600 кадров (не шумит)
                            if (i % 600 == 0) {
                                try {
                                    plugin.getLogger().info("[BadApple] caching frames: " + i + "/" + p.fc);
                                } catch (Throwable ignore) {
                                }
                            }

                            if ((i & 31) == 0)
                                Thread.yield();
                        }

                        p.preloadDone = true;
                        try {
                            plugin.getLogger().info("[BadApple] Bad Apple prepared successfully (" +
                                    p.fc + " frames, " + p.w + "x" + p.h + ")");
                        } catch (Throwable ignore) {
                        }

                    } catch (Throwable ignore) {
                    }
                }
            }.runTaskAsynchronously(plugin);

            return p;
        }

        @Override
        public int width() {
            return w;
        }

        @Override
        public int height() {
            return h;
        }

        @Override
        public int frameCount() {
            return fc;
        }

        @Override
        public BitSet getFrame(int idx) {
            if (idx < 0 || idx >= fc)
                return null;
            BitSet c = cache[idx];
            if (c != null)
                return (BitSet) c.clone();

            // если кадр ещё не прогрет — читаем из delegate и кладём в кеш
            BitSet f = delegate.getFrame(idx);
            BitSet fr;
            if (f != null) {
                fr = (BitSet) f.clone();
            } else {
                BitSet prev = (idx > 0 && cache[idx - 1] != null) ? cache[idx - 1] : new BitSet(w * h);
                fr = (BitSet) prev.clone();
            }
            cache[idx] = fr;
            return (BitSet) fr.clone();
        }
    }

    /**
     * true, если мы в режиме бедварса (значение swrg.math/#gamemode равно 3 или 4).
     */
    private boolean isBedwarsLockEnabled() {
        try {
            if (Bukkit.getScoreboardManager() == null)
                return false;
            final Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            if (sb == null)
                return false;
            final Objective obj = sb.getObjective("swrg.math");
            if (obj == null)
                return false;
            final Score sc = obj.getScore("#gamemode");
            if (!sc.isScoreSet())
                return false;
            final int v = sc.getScore();
            return v >= 3 && v <= 4;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** Быстрая проверка, является ли материал кроватью. */
    private static boolean isBedBlock(final Material m) {
        // Все цветные кровати имеют имя *_BED
        return m != null && m.name().endsWith("_BED");
    }

}
