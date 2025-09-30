package me.luckywars.bedwars;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.stream.Collectors;

public class MapClearCommand implements CommandExecutor, TabCompleter {

    // --- настройки ---
    private static final int FORCELOAD_BLOCK_RADIUS = 240; // чанки [-15..15] после очистки
    private static final int SAFE_MARGIN_SECTIONS_DEFAULT = 3; // зазор по 16 блоков сверху/снизу для FAWE
    private static final int CMIN = -32, CMAX = 31; // рамка 4 регионов по чанкам
    private static final int XZ_MIN = -512, XZ_MAX = 511; // рамка 4 регионов по блокам

    private final JavaPlugin plugin;

    public MapClearCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ======== Public command entry ========

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final boolean includeSpecial = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("includespecial"));

        List<org.bukkit.World> targets = new ArrayList<>();
        org.bukkit.World main = Bukkit.getWorld("world");
        if (main != null)
            targets.add(main);

        // minecraft:white УДАЛЁН
        if (includeSpecial) {
            org.bukkit.World imprinted = Bukkit.getWorld("world_minecraft_imprinted");
            if (imprinted != null)
                targets.add(imprinted);
        }

        if (targets.isEmpty()) {
            sender.sendMessage("[mapclear] Не найден ни один целевой мир.");
            return true;
        }

        sender.sendMessage("[mapclear] Запускаю очистку " + targets.size() +
                " мир(ов): " + targets.stream().map(org.bukkit.World::getName).collect(Collectors.joining(", ")));

        List<CompletableFuture<Result>> futures = targets.stream()
                .map(w -> clearLoadedChunksOnly(sender, w))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        sender.sendMessage("[mapclear] Ошибка пайплайна: " + err.getMessage());
                        err.printStackTrace();
                    }
                    for (CompletableFuture<Result> f : futures) {
                        Result r = f.join();
                        if (r.error != null) {
                            sender.sendMessage("[mapclear] " + r.worldName + ": " + r.error);
                        } else {
                            sender.sendMessage("[mapclear] " + r.worldName + ": очищено (FAWE): " + r.changed +
                                    " блоков, время: " + r.ms + " ms");
                        }
                    }
                    // Всегда: ставим флаг загрузки
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "scoreboard players set #if_map_loaded swrg.math 1");
                    sender.sendMessage("[mapclear] Готово.");
                }));

        return true;
    }

    // ======== Core: только загруженные чанки ========

    private CompletableFuture<Result> clearLoadedChunksOnly(CommandSender sender, org.bukkit.World bukkitWorld) {
        final long start = System.currentTimeMillis();
        final String wName = bukkitWorld.getName();

        // набор загруженных чанков в рамке 4 регионов
        Set<Long> loaded = new HashSet<>();
        for (Chunk ch : bukkitWorld.getLoadedChunks()) {
            int cx = ch.getX(), cz = ch.getZ();
            if (cx >= CMIN && cx <= CMAX && cz >= CMIN && cz <= CMAX) {
                loaded.add(chunkKey(cx, cz));
            }
        }
        sender.sendMessage("[mapclear:" + wName + "] Загруженных чанков в области: " + loaded.size());

        // безопасные высоты
        final World weWorld = BukkitAdapter.adapt(bukkitWorld);
        final int worldMinY = weWorld.getMinY();
        final int worldMaxYInclusive = weWorld.getMaxY() - 1;

        final int minSection = Math.floorDiv(worldMinY, 16);
        final int maxSection = Math.floorDiv(worldMaxYInclusive, 16);

        final int bottomMarginSections = (worldMinY >= 0) ? 0 : SAFE_MARGIN_SECTIONS_DEFAULT; // при minY>=0 — снизу без
                                                                                              // зазора
        final int topMarginSections = SAFE_MARGIN_SECTIONS_DEFAULT;

        int tmpStartY = ((minSection + bottomMarginSections) << 4);
        int tmpEndY = ((maxSection - topMarginSections) << 4) + 15;

        if (tmpStartY > tmpEndY) {
            tmpStartY = (minSection << 4);
            tmpEndY = ((maxSection) << 4) + 15;
            if (tmpStartY > tmpEndY) {
                return CompletableFuture.completedFuture(Result.ok(wName, 0, 0));
            }
        }
        final int fillStartY = tmpStartY;
        final int fillEndY = tmpEndY;

        sender.sendMessage(String.format(
                "[mapclear:%s] minY=%d, maxY=%d, safeFillY=[%d..%d]",
                wName, worldMinY, worldMaxYInclusive, fillStartY, fillEndY));

        // ASYNC: решить какие загруженные чанки «грязные» (palette.size()>1 в любой
        // секции)
        CompletableFuture<List<int[]>> decideDirty = CompletableFuture.supplyAsync(() -> {
            File regionDir = new File(bukkitWorld.getWorldFolder(), "region");
            List<int[]> dirty = new ArrayList<>();
            for (long key : loaded) {
                int cx = (int) (key >> 32), cz = (int) key;
                try {
                    if (chunkHasAnyComplexSection(regionDir, cx, cz)) {
                        dirty.add(new int[] { cx, cz });
                    }
                } catch (IOException e) {
                    // если не удалось прочитать — подчищаем на всякий случай
                    dirty.add(new int[] { cx, cz });
                }
            }
            return dirty;
        });

        // ASYNC: FAWE — чистим безопасный коридор во всех «грязных» чанках
        CompletableFuture<Integer> faweClear = decideDirty.thenApplyAsync(dirtyChunks -> {
            int changedTotal = 0;
            if (dirtyChunks.isEmpty())
                return 0;

            try (EditSession es = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(weWorld)
                    .maxBlocks(-1)
                    .build()) {

                es.setReorderMode(EditSession.ReorderMode.NONE);
                try {
                    es.setFastMode(true);
                } catch (Throwable ignored) {
                }

                for (int[] c : dirtyChunks) {
                    int cx = c[0], cz = c[1];
                    int bx0 = cx << 4, bz0 = cz << 4;
                    int bx1 = bx0 + 15, bz1 = bz0 + 15;

                    for (int y = fillStartY; y <= fillEndY; y += 16) {
                        int yTop = Math.min(y + 15, fillEndY);
                        CuboidRegion slice = new CuboidRegion(
                                weWorld,
                                BlockVector3.at(bx0, y, bz0),
                                BlockVector3.at(bx1, yTop, bz1));
                        changedTotal += setBlocksRaw(
                                es,
                                slice,
                                com.sk89q.worldedit.world.block.BlockTypes.AIR.getDefaultState());
                    }
                }
                es.flushQueue();
            } catch (Throwable t) {
                throw new CompletionException(t);
            }
            return changedTotal;
        });

        // SYNC+ASYNC: ограниченный forceload для центральной зоны
        CompletableFuture<Void> forceLoad = faweClear.thenCompose(v -> runSync(() -> {
            int fcMin = Math.floorDiv(-FORCELOAD_BLOCK_RADIUS, 16);
            int fcMax = Math.floorDiv(FORCELOAD_BLOCK_RADIUS, 16);
            for (int cx = fcMin; cx <= fcMax; cx++) {
                for (int cz = fcMin; cz <= fcMax; cz++) {
                    bukkitWorld.setChunkForceLoaded(cx, cz, true);
                }
            }
        }).thenCompose(v2 -> {
            int fcMin = Math.floorDiv(-FORCELOAD_BLOCK_RADIUS, 16);
            int fcMax = Math.floorDiv(FORCELOAD_BLOCK_RADIUS, 16);
            List<CompletableFuture<Chunk>> futs = new ArrayList<>((fcMax - fcMin + 1) * (fcMax - fcMin + 1));
            for (int cx = fcMin; cx <= fcMax; cx++) {
                for (int cz = fcMin; cz <= fcMax; cz++) {
                    try {
                        futs.add(bukkitWorld.getChunkAtAsync(cx, cz, true));
                    } catch (Throwable ignored) {
                    }
                }
            }
            return CompletableFuture.allOf(futs.toArray(new CompletableFuture[0]));
        })).exceptionally(t -> null);

        // Aggregation
        return forceLoad.handle((v, ex) -> {
            long ms = System.currentTimeMillis() - start;
            if (ex != null) {
                ex.printStackTrace();
                return Result.fail(wName, "ошибка очистки: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                        ms);
            } else {
                int changed = faweClear.getNow(0);
                return Result.ok(wName, changed, ms);
            }
        });
    }

    // ======== NBT utils: palette.size()>1? ========

    private static boolean chunkHasAnyComplexSection(File regionDir, int cx, int cz) throws IOException {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        File rf = new File(regionDir, "r." + rx + "." + rz + ".mca");
        if (!rf.isFile())
            return true; // нет файла — подстрахуемся, считаем «грязным»

        int localX = cx - rx * 32;
        int localZ = cz - rz * 32;
        int index = localX + localZ * 32;

        try (RandomAccessFile raf = new RandomAccessFile(rf, "r")) {
            // таблица локаций
            byte[] locTable = new byte[4096];
            raf.readFully(locTable);
            int base = index * 4;
            int v = ((locTable[base] & 0xFF) << 24)
                    | ((locTable[base + 1] & 0xFF) << 16)
                    | ((locTable[base + 2] & 0xFF) << 8)
                    | (locTable[base + 3] & 0xFF);
            int offsetSectors = (v >>> 8);
            int sectorCount = (v & 0xFF);
            if (offsetSectors == 0 || sectorCount == 0)
                return false; // чанка нет => «чистый»

            long fileOffset = (long) offsetSectors * 4096L;
            raf.seek(fileOffset);

            int length = raf.readInt(); // длина сжатых данных + 1
            if (length <= 0)
                return true;
            int compType = raf.readUnsignedByte();
            int payloadLen = length - 1;
            if (payloadLen <= 0)
                return true;

            byte[] payload = new byte[payloadLen];
            raf.readFully(payload);

            InputStream cis;
            if (compType == 2)
                cis = new InflaterInputStream(new ByteArrayInputStream(payload));
            else if (compType == 1)
                cis = new GZIPInputStream(new ByteArrayInputStream(payload));
            else if (compType == 3)
                cis = new ByteArrayInputStream(payload);
            else
                return true;

            // читаем корневой TAG_Compound
            DataInputStream in = new DataInputStream(new BufferedInputStream(cis));
            byte rootType = in.readByte();
            if (rootType != 10)
                return true;
            readNbtString(in); // root name

            // ищем List "sections" где угодно (root/Level)
            return compoundHasComplexSection(in);
        }
    }

    private static boolean compoundHasComplexSection(DataInputStream in) throws IOException {
        while (true) {
            byte type = in.readByte();
            if (type == 0)
                return false; // конец этого compound
            String name = readNbtString(in);

            if (type == 9 && "sections".equals(name)) {
                byte elemType = in.readByte();
                int len = in.readInt();
                if (elemType != 10) { // ожидаем List<Compound>
                    for (int i = 0; i < len; i++)
                        skipTagPayload(in, elemType);
                    continue;
                }
                for (int i = 0; i < len; i++) {
                    if (sectionPaletteLenGt1(in))
                        return true; // нашли секцию с palette.size()>1
                }
            } else if (type == 10) {
                if (compoundHasComplexSection(in))
                    return true; // вложенные compound (Level и т.п.)
            } else {
                skipTagPayload(in, type);
            }
        }
    }

    private static boolean sectionPaletteLenGt1(DataInputStream in) throws IOException {
        // читаем поля секции до TAG_End, ищем palette/Palette как список
        while (true) {
            byte t = in.readByte();
            if (t == 0)
                break;
            String n = readNbtString(in);

            if ("block_states".equals(n) && t == 10) {
                // block_states { palette: List<Compound>, ... }
                Integer len = readPaletteLenFromBlockStates(in);
                if (len != null && len > 1)
                    return true;
            } else if ("Palette".equals(n) && t == 9) {
                // старый формат: Palette: List<Compound>
                int size = readListSize(in);
                if (size > 1) {
                    skipListOfCompounds(in, size);
                    return true;
                }
                skipListOfCompounds(in, size);
            } else {
                skipTagPayload(in, t);
            }
        }
        return false;
    }

    private static Integer readPaletteLenFromBlockStates(DataInputStream in) throws IOException {
        Integer paletteLen = null;
        while (true) {
            byte t = in.readByte();
            if (t == 0)
                break;
            String n = readNbtString(in);
            if ("palette".equals(n) && t == 9) {
                paletteLen = readListSize(in);
                // пропустим содержимое палитры как список Compound
                skipListOfCompounds(in, paletteLen);
            } else {
                skipTagPayload(in, t);
            }
        }
        return paletteLen;
    }

    private static int readListSize(DataInputStream in) throws IOException {
        byte elemType = in.readByte();
        int len = in.readInt();
        return len; // тип вернули, потребителю решать, что делать
    }

    private static void skipListOfCompounds(DataInputStream in, int len) throws IOException {
        // предполагаем elemType уже прочитан
        for (int i = 0; i < len; i++) {
            // элемент — Compound, пропускаем до TAG_End
            while (true) {
                byte t = in.readByte();
                if (t == 0)
                    break;
                String n = readNbtString(in);
                skipTagPayload(in, t);
            }
        }
    }

    private static String readNbtString(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void skipTagPayload(DataInputStream in, byte type) throws IOException {
        switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                int n = in.readInt();
                in.skipNBytes(n);
            }
            case 8 -> {
                int n = in.readUnsignedShort();
                in.skipNBytes(n);
            }
            case 9 -> {
                byte et = in.readByte();
                int len = in.readInt();
                for (int i = 0; i < len; i++)
                    skipTagPayload(in, et);
            }
            case 10 -> {
                while (true) {
                    byte t = in.readByte();
                    if (t == 0)
                        break;
                    String n = readNbtString(in);
                    skipTagPayload(in, t);
                }
            }
            case 11 -> {
                int n = in.readInt();
                in.skipNBytes((long) n * 4);
            }
            case 12 -> {
                int n = in.readInt();
                in.skipNBytes((long) n * 8);
            }
            default -> {
            }
        }
    }

    // ======== misc ========

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private <T> CompletableFuture<T> runSync(CallableWithEx<T> action) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                cf.complete(action.call());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    private CompletableFuture<Void> runSync(Runnable action) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                cf.complete(null);
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    @FunctionalInterface
    private interface CallableWithEx<T> {
        T call() throws Exception;
    }

    private record Result(String worldName, int changed, long ms, String error) {
        static Result ok(String worldName, int changed, long ms) {
            return new Result(worldName, changed, ms, null);
        }

        static Result fail(String worldName, String error, long ms) {
            return new Result(worldName, 0, ms, error);
        }
    }

    // Явная generic-перегрузка setBlocks(Region, B extends BlockStateHolder<B>)
    // чтобы избежать «ambiguous».
    private static int setBlocksRaw(EditSession es,
            com.sk89q.worldedit.regions.Region region,
            com.sk89q.worldedit.world.block.BlockState state) {
        return es.<com.sk89q.worldedit.world.block.BlockState>setBlocks(region, state);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("includespecial".startsWith(prefix))
                return List.of("includespecial");
        }
        return Collections.emptyList();
    }
}
