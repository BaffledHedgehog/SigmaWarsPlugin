package com.govnoslav;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

/**
 * Команда /motion с поддержкой «~» (относительно текущей скорости) и «^»
 * (локальная система координат сущности). Импульс выдаётся один раз за тик.
 *
 * Синтаксис:
 *   /motion set <x> <y> <z> <цели>
 *   /motion add <x> <y> <z> <цели>
 *   /motion multiple <scalar> <цели>
 *
 * где:
 *   <x>, <y>, <z> могут быть:
 *     - абсолютными числами ("1.5", "-2")
 *     - с тильдой "~"   : "~"  (оставить координату текущей скорости) 
 *                         "~n" (текущее + n)
 *     - с кареткой "^"   : "^", "^n" (локальный вектор относительно взгляда)
 *
 * При наличии хотя бы одного «^» во входных аргументах (<x>,<y>,<z>)
 * вся троица воспринимается как локальный вектор:
 *   - localX = parseCaret(argX)
 *   - localY = parseCaret(argY)
 *   - localZ = parseCaret(argZ)
 *   worldVector = right*localX + up*localY + forward*localZ
 *
 * «~» используется только если нет ни одного «^»:
 *   - для set: "~" → newAxis = currentAxis; "~n" → newAxis = currentAxis + n
 *   - для add: "~" → delta = 0; "~n" → delta = n
 *
 * Ограничение: итоговый вектор по любой координате не должен превышать 10 по абсолютной величине.
 * Если превышает, масштабируем весь вектор так, чтобы max(|x|,|y|,|z|)=10.
 */
public class MotionCommand implements CommandExecutor, TabCompleter {


    //public MotionCommand(JavaPlugin plugin) {
    //}

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("motion")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /motion <set|add|multiple> ...");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "set" -> {
                // /motion set <x> <y> <z> <цели>
                if (args.length != 5) {
                    sender.sendMessage("§cИспользование: /motion set <x> <y> <z> <цели>");
                    return true;
                }
                String sx = args[1], sy = args[2], sz = args[3];
                String targetArg = args[4];

                Collection<Entity> targets;
                try {
                    targets = Bukkit.selectEntities(sender, targetArg);
                } catch (IllegalArgumentException ex) {
                 //   sender.sendMessage("§cНе удалось найти цели: " + ex.getMessage());
                    return true;
                }
                if (targets.isEmpty()) {
                 //   sender.sendMessage("§cНе найдено ни одной цели.");
                    return true;
                }

              //  int count = 0;
                for (Entity ent : targets) {
                    if (!(ent instanceof LivingEntity)) continue;
                    Vector current = ent.getVelocity();
                    Vector finalVec;

                    // Если хотя бы один аргумент начинается с "^" → локальные координаты
                    if (sx.startsWith("^") || sy.startsWith("^") || sz.startsWith("^")) {
                        // Парсим локальные компоненты (метод parseCaret)
                        double localX = parseCaret(sx);
                        double localY = parseCaret(sy);
                        double localZ = parseCaret(sz);
                        finalVec = localToWorld(ent.getLocation(), localX, localY, localZ);
                    } else {
                        // Абсолютные или с "~"
                        double x = parseSetComponent(sx, current.getX());
                        double y = parseSetComponent(sy, current.getY());
                        double z = parseSetComponent(sz, current.getZ());
                        finalVec = new Vector(x, y, z);
                    }

                    finalVec = capVector(finalVec);
                    ent.setVelocity(finalVec);
                 //   count++;
                }

              //  sender.sendMessage("§aMotion установлен для " + count + " сущностей.");
                return true;
            }

            case "add" -> {
                // /motion add <x> <y> <z> <цели>
                if (args.length != 5) {
                    sender.sendMessage("§cИспользование: /motion add <x> <y> <z> <цели>");
                    return true;
                }
                String sx = args[1], sy = args[2], sz = args[3];
                String targetArg = args[4];

                Collection<Entity> targets;
                try {
                    targets = Bukkit.selectEntities(sender, targetArg);
                } catch (IllegalArgumentException ex) {
                 //   sender.sendMessage("§cНе удалось найти цели: " + ex.getMessage());
                    return true;
                }
                if (targets.isEmpty()) {
                 //   sender.sendMessage("§cНе найдено ни одной цели.");
                    return true;
                }

              //  int count = 0;
                for (Entity ent : targets) {
                    if (!(ent instanceof LivingEntity)) continue;
                    Vector current = ent.getVelocity();
                    Vector delta;

                    if (sx.startsWith("^") || sy.startsWith("^") || sz.startsWith("^")) {
                        // Локальный вектор добавления
                        double localX = parseCaret(sx);
                        double localY = parseCaret(sy);
                        double localZ = parseCaret(sz);
                        delta = localToWorld(ent.getLocation(), localX, localY, localZ);
                    } else {
                        // Относительные через "~" или абсолютные
                        double dx = parseAddComponent(sx);
                        double dy = parseAddComponent(sy);
                        double dz = parseAddComponent(sz);
                        delta = new Vector(dx, dy, dz);
                    }

                    Vector result = current.clone().add(delta);
                    result = capVector(result);
                    ent.setVelocity(result);
                 //   count++;
                }

              //  sender.sendMessage("§aMotion добавлен для " + count + " сущностей.");
                return true;
            }

            case "multiple" -> {
                // /motion multiple <scalar> <цели>
                if (args.length != 3) {
                    sender.sendMessage("§cИспользование: /motion multiple <scalar> <цели>");
                    return true;
                }
                double scalar;
                try {
                    scalar = Double.parseDouble(args[1]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cНеверное число: " + args[1]);
                    return true;
                }
                String targetArg = args[2];

                Collection<Entity> targets;
                try {
                    targets = Bukkit.selectEntities(sender, targetArg);
                } catch (IllegalArgumentException ex) {
                //    sender.sendMessage("§cНе удалось найти цели: " + ex.getMessage());
                    return true;
                }
                if (targets.isEmpty()) {
                 //   sender.sendMessage("§cНе найдено ни одной цели.");
                    return true;
                }

               // int count = 0;
                for (Entity ent : targets) {
                    if (!(ent instanceof LivingEntity)) continue;
                    Vector current = ent.getVelocity();
                    Vector result = current.clone().multiply(scalar);
                    result = capVector(result);
                    ent.setVelocity(result);
                 //   count++;
                }

              //  sender.sendMessage("§aMotion умножен на " 
              //      + scalar + " для " + count + " сущностей.");
                return true;
            }

            default -> {
                sender.sendMessage("§cНеверный тип: выберите set, add или multiple.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, 
                                      String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("motion")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("set", "add", "multiple");
        }
        if (args.length == 5 && (args[0].equalsIgnoreCase("set") 
                               || args[0].equalsIgnoreCase("add"))) {
            return Arrays.asList("@a", "@p", "@r", "@e");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("multiple")) {
            return Arrays.asList("@a", "@p", "@r", "@e");
        }
        return Collections.emptyList();
    }

    /**
     * Если аргумент начинается с "~":
     *   - "~"   → возвращает текущее значение axis
     *   - "~n"  → возвращает current + n
     * Иначе (абсолютное число) → парсит Double.parseDouble(arg).
     */
    private double parseSetComponent(String arg, double currentAxis) {
        if (arg.startsWith("~")) {
            if (arg.equals("~")) {
                return currentAxis;
            } else {
                double offset = 0.0;
                try {
                    offset = Double.parseDouble(arg.substring(1));
                } catch (NumberFormatException ignore) { }
                return currentAxis + offset;
            }
        }
        // Абсолютное число
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException ex) {
            return currentAxis; // безопасный fallback
        }
    }

    /**
     * Для /motion add: 
     * Если аргумент "~"  → 0
     * Если "~n"         → n
     * Иначе (абсолютное число) → Double.parseDouble(arg).
     */
    private double parseAddComponent(String arg) {
        if (arg.startsWith("~")) {
            if (arg.equals("~")) {
                return 0.0;
            } else {
                try {
                    return Double.parseDouble(arg.substring(1));
                } catch (NumberFormatException ignore) { }
                return 0.0;
            }
        }
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /**
     * Если аргумент начинается с "^":
     *   - "^"  → 0
     *   - "^n" → n
     * Иначе → 0 (только для локальных координат).
     */
    private double parseCaret(String arg) {
        if (!arg.startsWith("^")) return 0.0;
        if (arg.equals("^")) return 0.0;
        try {
            return Double.parseDouble(arg.substring(1));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /**
     * Переводит локальный вектор (localX, localY, localZ) относительно взгляда сущности
     * в мировой Vector:
     *   forward  = направление взгляда (normalize)
     *   right    = forward × worldUp (normalize)
     *   up       = (0,1,0)
     * worldVec = right*localX + up*localY + forward*localZ
     */
    private Vector localToWorld(Location loc, double localX, double localY, double localZ) {
        Vector forward = loc.getDirection().clone().normalize();
        Vector worldUp = new Vector(0, 1, 0);
        Vector right = forward.clone().crossProduct(worldUp).normalize();
        Vector up = worldUp; // не учитываем крен, только вертикаль

        Vector result = new Vector(0, 0, 0);
        result.add(right.multiply(localX));
        result.add(up.multiply(localY));
        result.add(forward.multiply(localZ));
        return result;
    }

    /**
     * Ограничивает компоненты вектора: 
     * ни |x|, ни |y|, ни |z| не должны превысить 10.
     * Если превышает, масштабируем весь вектор так, чтобы max(|x|,|y|,|z|)=10.
     */
    private Vector capVector(Vector v) {
        double ax = Math.abs(v.getX());
        double ay = Math.abs(v.getY());
        double az = Math.abs(v.getZ());
        double max = Math.max(ax, Math.max(ay, az));
        if (max <= 10.0) {
            return v;
        }
        double factor = 10.0 / max;
        return new Vector(v.getX() * factor, v.getY() * factor, v.getZ() * factor);
    }
}
