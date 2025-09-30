package me.luckywars.bedwars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;
import java.util.stream.Collectors;

public final class TeamInitCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final Random random = new Random();

    // === sidebar state ===
    private static final String OBJ_NAME = "bedwars_sidebar";
    private static BukkitTask sidebarTask;
    private static long gameStartEpochSec = 0L;

    public TeamInitCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        final Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();

        // Собираем маркеры во всех мирах
        final List<Marker> bedMarkers = new ArrayList<>();
        final List<Marker> spawnMarkers = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            bedMarkers.addAll(w.getEntitiesByClass(Marker.class).stream()
                    .filter(m -> m.getScoreboardTags().contains("bedwars_bed"))
                    .toList());
            spawnMarkers.addAll(w.getEntitiesByClass(Marker.class).stream()
                    .filter(m -> m.getScoreboardTags().contains("swrg.spawn"))
                    .toList());
        }

        // Маппинг «команда -> список bed-маркеров» (уже привязаны заранее)
        final Map<String, List<Marker>> bedsByTeam = new HashMap<>();
        for (Marker m : bedMarkers) {
            Team t = getTeamOfEntity(sb, m);
            if (t != null)
                bedsByTeam.computeIfAbsent(t.getName(), k -> new ArrayList<>()).add(m);
        }

        // Текущее онлайновое распределение игроков по командам
        final Map<String, Integer> onlineByTeam = new HashMap<>();
        for (Team t : sb.getTeams()) {
            int c = 0;
            for (Player p : Bukkit.getOnlinePlayers()) {
                Team pt = getTeamOfPlayer(p, sb);
                if (pt != null && pt.equals(t))
                    c++;
            }
            onlineByTeam.put(t.getName(), c);
        }

        // Удобный список "команды, у которых есть bed-маркеры" (именно их и считаем
        // игровыми)
        final List<Team> playableTeams = bedsByTeam.keySet().stream()
                .map(sb::getTeam)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        // Обрабатываем всех игроков онлайн
        final List<Player> noTeamPlayers = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team pt = getTeamOfPlayer(p, sb);

            if (pt != null) {
                // Игрок состоит в команде. Проверяем, есть ли кровать этой команды.
                List<Marker> myBeds = bedsByTeam.get(pt.getName());
                if (myBeds != null && !myBeds.isEmpty()) {
                    // Берём ближайшую к игроку кровать и ближайший к ней spawn
                    Marker bed = nearestToPlayer(p, myBeds);
                    if (bed != null) {
                        Marker spawn = nearestSpawn(bed.getLocation().toCenterLocation(), spawnMarkers);
                        if (spawn != null) {
                            joinEntityToTeam(pt, spawn);
                            p.setRespawnLocation(spawn.getLocation(), true);
                            p.teleport(spawn.getLocation().toCenterLocation());
                        } else {
                            // fallback: телепорт к кровати
                            p.teleport(bed.getLocation().toCenterLocation());
                        }
                    }
                } else {
                    // У его команды НЕТ кровати — выкидываем из команды и отмечаем как "без
                    // команды"
                    try {
                        pt.removeEntry(p.getName());
                    } catch (Throwable ignored) {
                    }
                    decrement(onlineByTeam, pt.getName());
                    noTeamPlayers.add(p);
                }
            } else {
                noTeamPlayers.add(p);
            }
        }

        // Раздаём игроков без команды по командам с кроватями: приоритет пустым, далее
        // — по минимальному онлайну
        for (Player p : noTeamPlayers) {
            Team chosen = chooseTeamByFewestPlayers(playableTeams, onlineByTeam);
            if (chosen == null)
                continue;

            joinPlayerToTeam(chosen, p);
            increment(onlineByTeam, chosen.getName());

            // Найдём кровать этой команды и ближайший к ней spawn
            List<Marker> beds = bedsByTeam.getOrDefault(chosen.getName(), Collections.emptyList());
            if (!beds.isEmpty()) {
                Marker bed = nearestToPlayer(p, beds);
                Marker spawn = (bed == null) ? null : nearestSpawn(bed.getLocation().toCenterLocation(), spawnMarkers);
                if (spawn != null) {
                    joinEntityToTeam(chosen, spawn);
                    p.setRespawnLocation(spawn.getLocation(), true);
                    p.teleport(spawn.getLocation().toCenterLocation());
                } else if (bed != null) {
                    p.teleport(bed.getLocation().toCenterLocation());
                }
            }
        }

        // Если все игроки оказались в одной команде — половину перекидываем в случайную
        // другую (где есть кровать)
        rebalanceIfSingleTeam(sb, bedsByTeam, spawnMarkers, onlineByTeam, playableTeams);

        // Удаляем маркеры (и чистим кровати вокруг) для команд без игроков и маркеры
        // без команды
        cleanupOrphanMarkers(sb, bedsByTeam, spawnMarkers, onlineByTeam);

        // Запускаем/обновляем sidebar и фиксируем СТАРТОВЫЙ набор команд:
        // только те, где сейчас есть хотя бы один игрок (после
        // распределения/ребаланса).
        final LinkedHashSet<String> initialTeamsWithPlayers = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team t = getTeamOfPlayer(p, sb);
            if (t != null)
                initialTeamsWithPlayers.add(t.getName());
        }

        // Зафиксировали стартовый набор участвующих команд (только те, где есть игроки)
        // ...
        PARTICIPATING_TEAMS.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team t = getTeamOfPlayer(p, sb);
            if (t != null)
                PARTICIPATING_TEAMS.add(t.getName());
        }

        // --- СБРОС ТАЙМЕРА ИГРЫ ---
        gameStartEpochSec = System.currentTimeMillis() / 1000L; // теперь таймер начнётся с 0
        updateSidebar(sb); // мгновенно обновим отображение (если objective уже был)
        // ---------------------------

        ensureSidebarTickerRunning(plugin);

        PARTICIPATING_TEAMS.addAll(initialTeamsWithPlayers);

        ensureSidebarTickerRunning(plugin);

        sender.sendMessage(Component.text("[teaminit] done: " + Bukkit.getOnlinePlayers().size() + " players"));
        return true;
    }

    /* =================== helpers (distribution) =================== */

    private static Team getTeamOfPlayer(Player p, Scoreboard sb) {
        for (Team t : sb.getTeams()) {
            if (t.hasEntry(p.getName()))
                return t;
        }
        return null;
    }

    private static Team getTeamOfEntity(Scoreboard sb, Entity e) {
        try {
            for (Team t : sb.getTeams()) {
                if (t.hasEntity(e))
                    return t;
            }
            return null;
        } catch (NoSuchMethodError err) {
            String entry = e.getUniqueId().toString();
            for (Team t : sb.getTeams()) {
                if (t.hasEntry(entry))
                    return t;
            }
            return null;
        }
    }

    private static void joinEntityToTeam(Team t, Entity e) {
        try {
            t.addEntity(e);
        } catch (NoSuchMethodError err) {
            t.addEntry(e.getUniqueId().toString());
        }
    }

    private static void joinPlayerToTeam(Team t, Player p) {
        t.addEntry(p.getName());
    }

    private static void increment(Map<String, Integer> m, String k) {
        m.put(k, m.getOrDefault(k, 0) + 1);
    }

    private static void decrement(Map<String, Integer> m, String k) {
        m.put(k, Math.max(0, m.getOrDefault(k, 0) - 1));
    }

    private static Marker nearestSpawn(Location from, List<Marker> spawns) {
        if (spawns.isEmpty())
            return null;
        Marker best = null;
        double bestD = Double.MAX_VALUE;
        for (Marker m : spawns) {
            double d = m.getLocation().toCenterLocation().distanceSquared(from);
            if (d < bestD) {
                best = m;
                bestD = d;
            }
        }
        return best;
    }

    private static Marker nearestToPlayer(Player p, List<Marker> list) {
        if (list.isEmpty())
            return null;
        Location from = p.getLocation();
        Marker best = null;
        double bestD = Double.MAX_VALUE;
        for (Marker m : list) {
            double d = m.getLocation().toCenterLocation().distanceSquared(from);
            if (d < bestD) {
                best = m;
                bestD = d;
            }
        }
        return best;
    }

    private Team chooseTeamByFewestPlayers(List<Team> candidates, Map<String, Integer> onlineByTeam) {
        Team best = null;
        int bestC = Integer.MAX_VALUE;
        List<Team> zeros = new ArrayList<>();
        for (Team t : candidates) {
            int c = onlineByTeam.getOrDefault(t.getName(), 0);
            if (c == 0)
                zeros.add(t);
            if (c < bestC) {
                best = t;
                bestC = c;
            }
        }
        if (!zeros.isEmpty())
            return zeros.get(random.nextInt(zeros.size()));
        return best;
    }

    private void rebalanceIfSingleTeam(Scoreboard sb,
            Map<String, List<Marker>> bedsByTeam,
            List<Marker> spawnMarkers,
            Map<String, Integer> onlineByTeam,
            List<Team> playableTeams) {
        // Соберём онлайн по командам из скорборда ещё раз, чтобы убедиться
        Map<Team, List<Player>> members = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team t = getTeamOfPlayer(p, sb);
            if (t != null)
                members.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
        }
        if (members.isEmpty())
            return;
        if (members.size() > 1)
            return; // уже больше одной команды

        Map.Entry<Team, List<Player>> only = members.entrySet().iterator().next();
        Team current = only.getKey();
        List<Player> players = new ArrayList<>(only.getValue());
        if (players.size() <= 1)
            return;

        // Выбираем случайную другую команду с кроватью
        List<Team> others = new ArrayList<>(playableTeams);
        others.removeIf(t -> t.getName().equals(current.getName()));
        if (others.isEmpty())
            return;

        Team target = others.get(random.nextInt(others.size()));
        List<Marker> beds = bedsByTeam.getOrDefault(target.getName(), Collections.emptyList());
        if (beds.isEmpty())
            return;

        Marker bed = beds.get(0);
        Marker spawn = nearestSpawn(bed.getLocation().toCenterLocation(), spawnMarkers);

        // Половину игроков переводим
        Collections.shuffle(players, random);
        int moveCount = players.size() / 2;
        for (int i = 0; i < moveCount; i++) {
            Player p = players.get(i);
            // убрать из старой
            try {
                current.removeEntry(p.getName());
            } catch (Throwable ignored) {
            }
            decrement(onlineByTeam, current.getName());

            // добавить в новую
            joinPlayerToTeam(target, p);
            increment(onlineByTeam, target.getName());
            if (spawn != null) {
                joinEntityToTeam(target, spawn);
                p.setRespawnLocation(spawn.getLocation(), true);
                p.teleport(spawn.getLocation().toCenterLocation());
            } else {
                p.teleport(bed.getLocation().toCenterLocation());
            }
        }
    }

    private void cleanupOrphanMarkers(Scoreboard sb,
            Map<String, List<Marker>> bedsByTeam,
            List<Marker> spawnMarkers,
            Map<String, Integer> onlineByTeam) {
        // Команды без игроков
        Set<String> emptyTeams = new HashSet<>();
        for (String team : bedsByTeam.keySet()) {
            if (onlineByTeam.getOrDefault(team, 0) <= 0)
                emptyTeams.add(team);
        }

        // Удаляем кровати для пустых либо для маркеров без команды
        for (Marker bed : new ArrayList<>(bedsByTeam.values()).stream().flatMap(List::stream).toList()) {
            Team t = getTeamOfEntity(sb, bed);
            if (t == null || emptyTeams.contains(t.getName())) {
                clearBedsAround(bed.getLocation());
                bed.remove();
            }
        }

        // Удаляем спавны для пустых команд или без команды
        for (Marker sp : new ArrayList<>(spawnMarkers)) {
            Team t = getTeamOfEntity(sb, sp);
            if (t == null || emptyTeams.contains(t.getName())) {
                sp.remove();
            }
        }
    }

    private void clearBedsAround(Location center) {
        if (center == null)
            return;
        World w = center.getWorld();
        if (w == null)
            return;
        int y = center.getBlockY();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = w.getBlockAt(center.getBlockX() + dx, y, center.getBlockZ() + dz);
                BlockData data = b.getBlockData();
                if (data instanceof Bed) {
                    b.setType(Material.AIR, false);
                }
            }
        }
    }

    /* =================== sidebar (ticker) =================== */

    private void ensureSidebarTickerRunning(JavaPlugin plugin) {
        final Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = sb.getObjective(OBJ_NAME);
        if (obj == null) {
            obj = sb.registerNewObjective(OBJ_NAME, "dummy", Component.text(" "));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        if (gameStartEpochSec == 0L) {
            gameStartEpochSec = System.currentTimeMillis() / 1000L;
        }

        if (sidebarTask == null || sidebarTask.isCancelled()) {
            sidebarTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                // Отключаемся автоматически, если защита выключена (/bedwars stop)
                if (!plugin.getConfig().getBoolean("bedwars.enabled", true)) {
                    Objective o = sb.getObjective(OBJ_NAME);
                    if (o != null)
                        o.unregister();
                    sidebarTask.cancel();
                    sidebarTask = null;
                    return;
                }

                updateSidebar(sb);

            }, 0L, 20L);
        }
    }

    private void updateSidebar(Scoreboard sb) {
        Objective obj = sb.getObjective(OBJ_NAME);
        if (obj == null)
            return;

        // 1) собрать актуальные участники (зафиксированный набор)
        final List<Team> teams = PARTICIPATING_TEAMS.stream()
                .map(sb::getTeam)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 2) посчитать "alive" (онлайн-игроки в команде) и подготовить строки
        record Row(Team team, int alive, char icon, String line) {
        }
        final List<Row> rows = new ArrayList<>(teams.size());
        final Random rnd = this.random;

        for (Team t : teams) {
            // онлайн-участники команды
            List<Player> plist = new ArrayList<>(Bukkit.getOnlinePlayers()); // уже List<Player>
            plist.removeIf(p -> {
                Team pt = getTeamOfPlayer(p, sb);
                return pt == null || !pt.equals(t);
            });

            // иконка: если есть игроки — смотрим случайного, иначе ▩
            char icon = '▩';
            if (!plist.isEmpty()) {
                Player sample = plist.get(rnd.nextInt(plist.size()));
                icon = sample.getScoreboardTags().contains("have_bed") ? '■' : '▩';
            }

            int alive = plist.size();

            final NamedTextColor col = teamNamedColor(t);
            final String keySuffix = namedToKeySuffix(col);

            Component lineC = Component.text(icon + " ")
                    .append(Component.translatable("Team " + keySuffix))
                    .append(Component.text(": " + alive))
                    .color(col);

            rows.add(new Row(t, alive, icon, serialize(lineC)));
        }

        // 3) сортировка: по alive ↓, потом по номеру команды (если имя — число), иначе
        // по имени
        rows.sort((a, b) -> {
            int cmp = Integer.compare(b.alive(), a.alive());
            if (cmp != 0)
                return cmp;
            String an = a.team().getName(), bn = b.team().getName();
            try {
                int ai = Integer.parseInt(an), bi = Integer.parseInt(bn);
                return Integer.compare(ai, bi);
            } catch (NumberFormatException ignored) {
                return an.compareToIgnoreCase(bn);
            }
        });

        // 4) подготовить итоговые строки с таймером
        final List<String> lines = new ArrayList<>();
        long secs = Math.max(0L, (System.currentTimeMillis() / 1000L) - gameStartEpochSec);
        lines.add(serialize(Component.translatable("Game Time: ")
                .append(Component.text(": " + secs))
                .color(NamedTextColor.WHITE)));

        // ограничение в 14 командных строк (итого 15 с таймером)
        int budget = 14;
        for (Row r : rows) {
            if (budget-- <= 0)
                break;
            lines.add(r.line());
        }

        // 5) синхронизировать scoreboard-entries
        Set<String> keep = new HashSet<>(lines);
        for (String entry : new HashSet<>(sb.getEntries())) {
            try {
                if (obj.getScore(entry).isScoreSet() && !keep.contains(entry)) {
                    sb.resetScores(entry);
                }
            } catch (Throwable ignored) {
                sb.resetScores(entry);
            }
        }

        int score = lines.size();
        for (String line : lines) {
            obj.getScore(line).setScore(score--);
        }
    }

    private static String serialize(Component c) {
        return LegacyComponentSerializer.legacySection().serialize(c);
    }

    private static NamedTextColor teamNamedColor(Team t) {
        TextColor tc = t.color();
        if (tc instanceof NamedTextColor ntc)
            return ntc;
        return NamedTextColor.nearestTo(tc);
    }

    private static String namedToKeySuffix(NamedTextColor c) {
        if (c == NamedTextColor.AQUA)
            return "Gays";
        if (c == NamedTextColor.BLACK)
            return "Niggers";
        if (c == NamedTextColor.BLUE)
            return "Sky";
        if (c == NamedTextColor.DARK_AQUA)
            return "Cyan";
        if (c == NamedTextColor.DARK_BLUE)
            return "Nigablue";
        if (c == NamedTextColor.DARK_GRAY)
            return "Boring";
        if (c == NamedTextColor.DARK_GREEN)
            return "Drugs";
        if (c == NamedTextColor.DARK_PURPLE)
            return "Shulkers";
        if (c == NamedTextColor.DARK_RED)
            return "Shitlords";
        if (c == NamedTextColor.GOLD)
            return "Covered in oil";
        if (c == NamedTextColor.GRAY)
            return "Lightasquirt";
        if (c == NamedTextColor.GREEN)
            return "Lime";
        if (c == NamedTextColor.LIGHT_PURPLE)
            return "Pedics";
        if (c == NamedTextColor.RED)
            return "Red";
        if (c == NamedTextColor.WHITE)
            return "Overcums";
        if (c == NamedTextColor.YELLOW)
            return "Lemonade";
        // запасной вариант
        return "Lox";
    }

    // фиксируем состав команд на всю игру после /teaminit
    private static final LinkedHashSet<String> PARTICIPATING_TEAMS = new LinkedHashSet<>();

}
