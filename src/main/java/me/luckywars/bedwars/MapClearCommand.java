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

    // рамка 4 регионов по чанкам и блокам
    private static final int CMIN = -32, CMAX = 31; // [-32..31] x [-32..31]
    private static final int XZ_MIN = -512, XZ_MAX = 511; // [-512..511] по блокам

    // безопасные зазоры по секциям (16бл)
    private static final int SAFE_MARGIN_SECTIONS_DEFAULT = 3; // сверху 3 секции (48 блоков)
    // снизу зазор 0 секций, если minY >= 0 (чтобы чистить 0..47)

    private final JavaPlugin plugin;

    public MapClearCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ======== Команда ========

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        final boolean includeSpecial = Arrays.stream(args).anyMatch(a -> a.equalsIgnoreCase("includespecial"));

        List<org.bukkit.World> targets = new ArrayList<>();
        org.bukkit.World main = Bukkit.getWorld("world");
        if (main != null)
            targets.add(main);

        // minecraft:white полностью убран
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
                    // флаг загрузки
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "scoreboard players set #if_map_loaded swrg.math 1");
                    sender.sendMessage("[mapclear] Готово.");
                }));

        return true;
    }

    // ======== Основная логика: только загруженные чанки, асинхронно ========

    private CompletableFuture<Result> clearLoadedChunksOnly(CommandSender sender, org.bukkit.World bukkitWorld) {
        final long start = System.currentTimeMillis();
        final String wName = bukkitWorld.getName();

        // SYNC: снимок загруженных чанков внутри рамки 4 регионов
        CompletableFuture<List<int[]>> snapshotLoaded = runSync(() -> {
            List<int[]> list = new ArrayList<>();
            for (Chunk ch : bukkitWorld.getLoadedChunks()) {
                int cx = ch.getX(), cz = ch.getZ();
                if (cx >= CMIN && cx <= CMAX && cz >= CMIN && cz <= CMAX) {
                    list.add(new int[] { cx, cz });
                }
            }
            return list;
        });

        // ASYNC: решаем какие из загруженных чанков чистить (palette.size()>1 в любой
        // секции; проверка снизу-вверх)
        CompletableFuture<List<int[]>> decideDirty = snapshotLoaded.thenApplyAsync(loadedChunks -> {
            sender.sendMessage("[mapclear:" + wName + "] Загруженных чанков в области: " + loadedChunks.size());
            File regionDir = new File(bukkitWorld.getWorldFolder(), "region");
            List<int[]> dirty = new ArrayList<>();
            for (int[] c : loadedChunks) {
                int cx = c[0], cz = c[1];
                try {
                    if (chunkHasSectionWithPaletteLenGt1(regionDir, cx, cz)) {
                        dirty.add(c);
                    }
                } catch (IOException e) {
                    // не смогли прочитать — подстрахуемся, считаем «грязным»
                    dirty.add(c);
                }
            }
            return dirty;
        });

        // параметры «безопасной» высоты
        final World weWorld = BukkitAdapter.adapt(bukkitWorld);
        final int worldMinY = weWorld.getMinY();
        final int worldMaxYInclusive = weWorld.getMaxY() - 1;
        final int minSection = Math.floorDiv(worldMinY, 16);
        final int maxSection = Math.floorDiv(worldMaxYInclusive, 16);
        final int bottomMarginSections = (worldMinY >= 0) ? 0 : SAFE_MARGIN_SECTIONS_DEFAULT;
        final int topMarginSections = SAFE_MARGIN_SECTIONS_DEFAULT;

        int tmpStartY = ((minSection + bottomMarginSections) << 4);
        int tmpEndY = ((maxSection - topMarginSections) << 4) + 15;
        if (tmpStartY > tmpEndY) {
            tmpStartY = (minSection << 4);
            tmpEndY = ((maxSection) << 4) + 15;
        }
        final int fillStartY = tmpStartY;
        final int fillEndY = tmpEndY;

        sender.sendMessage(String.format(
                "[mapclear:%s] minY=%d, maxY=%d, safeFillY=[%d..%d]",
                wName, worldMinY, worldMaxYInclusive, fillStartY, fillEndY));

        // ASYNC: FAWE — «сырое» заполнение воздухом по выбранным чанкам (без
        // forceload/unload)
        CompletableFuture<Integer> faweClear = decideDirty.thenApplyAsync(dirtyChunks -> {
            if (dirtyChunks.isEmpty())
                return 0;
            int changedTotal = 0;
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

        // Aggregation
        return faweClear.handle((changed, ex) -> {
            long ms = System.currentTimeMillis() - start;
            if (ex != null) {
                ex.printStackTrace();
                return Result.fail(wName, "ошибка очистки: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                        ms);
            } else {
                return Result.ok(wName, changed == null ? 0 : changed, ms);
            }
        });
    }

    // ======== NBT-проверка: есть ли секция с palette.size()>1 (идём снизу вверх)
    // ========

    private static boolean chunkHasSectionWithPaletteLenGt1(File regionDir, int cx, int cz) throws IOException {
        int rx = Math.floorDiv(cx, 32);
        int rz = Math.floorDiv(cz, 32);
        File rf = new File(regionDir, "r." + rx + "." + rz + ".mca");
        if (!rf.isFile())
            return true; // нет файла — подстрахуемся, чистим

        int localX = cx - rx * 32;
        int localZ = cz - rz * 32;
        int index = localX + localZ * 32;

        try (RandomAccessFile raf = new RandomAccessFile(rf, "r")) {
            // таблица локаций
            byte[] loc = new byte[4096];
            raf.readFully(loc);
            int base = index * 4;
            int v = ((loc[base] & 0xFF) << 24)
                    | ((loc[base + 1] & 0xFF) << 16)
                    | ((loc[base + 2] & 0xFF) << 8)
                    | (loc[base + 3] & 0xFF);
            int offsetSectors = (v >>> 8);
            int sectorCount = (v & 0xFF);
            if (offsetSectors == 0 || sectorCount == 0) {
                // чанка нет в файле (но он загружен в памяти) — чистим на всякий случай
                return true;
            }

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

            DataInputStream in = new DataInputStream(new BufferedInputStream(cis));

            // корневой Compound
            byte rootType = in.readByte();
            if (rootType != 10)
                return true;
            readNbtString(in); // root name

            // найдём sections, соберём (secY, paletteLen), отсортируем по Y и пойдём снизу
            // вверх
            List<SecInfo> sections = readSectionsPaletteSizes(in);
            if (sections.isEmpty())
                return false; // нет секций → трактуем как «пустой»
            sections.sort(Comparator.comparingInt(s -> s.y));
            for (SecInfo s : sections) {
                if (s.paletteLen != null && s.paletteLen > 1)
                    return true;
                // paletteLen==null трактуем как 1 (воздух) -> продолжаем
            }
            return false;
        }
    }

    private record SecInfo(int y, Integer paletteLen) {
    }

    private static List<SecInfo> readSectionsPaletteSizes(DataInputStream in) throws IOException {
        List<SecInfo> out = new ArrayList<>(24);
        while (true) {
            byte type = in.readByte();
            if (type == 0)
                break; // TAG_End
            String name = readNbtString(in);
            if (type == 9 && "sections".equals(name)) {
                byte elemType = in.readByte();
                int len = in.readInt();
                if (elemType != 10) {
                    // не Compound — пропускаем как список
                    for (int i = 0; i < len; i++)
                        skipTagPayload(in, elemType);
                    continue;
                }
                for (int i = 0; i < len; i++) {
                    out.add(readOneSectionPaletteLen(in));
                }
            } else if (type == 10) {
                // вложенный Compound (например, Level) — ищем внутри
                out.addAll(readSectionsPaletteSizes(in));
            } else {
                skipTagPayload(in, type);
            }
        }
        return out;
    }

    private static SecInfo readOneSectionPaletteLen(DataInputStream in) throws IOException {
        Integer yVal = null;
        Integer paletteLen = null;

        while (true) {
            byte t = in.readByte();
            if (t == 0)
                break; // конец секции
            String n = readNbtString(in);

            if ("Y".equals(n)) {
                if (t == 1) {
                    yVal = (int) in.readByte();
                } else if (t == 3) {
                    yVal = in.readInt();
                } else {
                    skipTagPayload(in, t);
                }
            } else if ("block_states".equals(n) && t == 10) {
                // block_states { palette: List<Compound> ... }
                paletteLen = readPaletteLenFromBlockStates(in);
            } else if ("Palette".equals(n) && t == 9) {
                // старый формат: Palette: List<Compound>
                paletteLen = readListSize(in);
                skipListOfCompounds(in, paletteLen);
            } else {
                skipTagPayload(in, t);
            }
        }
        if (yVal == null)
            yVal = 0; // на всякий
        return new SecInfo(yVal, paletteLen); // paletteLen==null трактуем как 1 при проверке
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
                // пропускаем содержимое (Compound-элементы)
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
        return len;
    }

    private static void skipListOfCompounds(DataInputStream in, int len) throws IOException {
        for (int i = 0; i < len; i++) {
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

    // ======== Утилиты ========

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
