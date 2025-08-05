package me.luckywars;

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
 * (локальная система координат сущности или контекста выполнения).
 * Импульс выдаётся один раз за тик.
 *
 * Синтаксис:
 * /motion set <x> <y> <z> <цели>
 * /motion add <x> <y> <z> <цели>
 * /motion multiple <scalar> <цели>
 *
 * где:
 * <x>, <y>, <z> могут быть:
 * - абсолютными числами ("1.5", "-2")
 * - с тильдой "~" : "~" (оставить координату текущей скорости)
 * "~n" (текущее + n)
 * - с кареткой "^" : "^", "^n" (локальный вектор относительно взгляда
 * или контекста выполнения, если команда исполнена через
 * execute rotated ... run motion ...)
 *
 * Ограничение: итоговый вектор по любой координате не должен превышать 10 по
 * абсолютной величине.
 * Если превышает, масштабируем весь вектор так, чтобы max(|x|,|y|,|z|)=10.
 */
public class MotionCommand implements CommandExecutor, TabCompleter {

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
                    return true;
                }
                if (targets.isEmpty()) {
                    return true;
                }

                for (Entity ent : targets) {
                    if (!(ent instanceof LivingEntity))
                        continue;
                    Vector current = ent.getVelocity();
                    Vector finalVec;

                    // Если хотя бы один аргумент начинается с "^" → локальные координаты
                    if (sx.startsWith("^") || sy.startsWith("^") || sz.startsWith("^")) {
                        double localX = parseCaret(sx);
                        double localY = parseCaret(sy);
                        double localZ = parseCaret(sz);
                        // При ^ учитываем контекст выполнения команды (execute rotated ...)
                        Location basis = (sender instanceof Entity)
                                ? ((Entity) sender).getLocation()
                                : ent.getLocation();
                        finalVec = localToWorld(basis, localX, localY, localZ);
                    } else {
                        double x = parseSetComponent(sx, current.getX());
                        double y = parseSetComponent(sy, current.getY());
                        double z = parseSetComponent(sz, current.getZ());
                        finalVec = new Vector(x, y, z);
                    }

                    finalVec = capVector(finalVec);
                    ent.setVelocity(finalVec);
                }
                return true;
            }

            case "add" -> {
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
                    return true;
                }
                if (targets.isEmpty()) {
                    return true;
                }

                for (Entity ent : targets) {
                    if (!(ent instanceof LivingEntity))
                        continue;
                    Vector current = ent.getVelocity();
                    Vector delta;

                    if (sx.startsWith("^") || sy.startsWith("^") || sz.startsWith("^")) {
                        double localX = parseCaret(sx);
                        double localY = parseCaret(sy);
                        double localZ = parseCaret(sz);
                        Location basis = (sender instanceof Entity)
                                ? ((Entity) sender).getLocation()
                                : ent.getLocation();
                        delta = localToWorld(basis, localX, localY, localZ);
                    } else {
                        double dx = parseAddComponent(sx);
                        double dy = parseAddComponent(sy);
                        double dz = parseAddComponent(sz);
                        delta = new Vector(dx, dy, dz);
                    }

                    Vector result = current.clone().add(delta);
                    result = capVector(result);
                    ent.setVelocity(result);
                }
                return true;
            }

            case "multiple" -> {
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
                    return true;
                }
                if (targets.isEmpty()) {
                    return true;
                }

                for (Entity ent : targets) {
                    if (!(ent instanceof LivingEntity))
                        continue;
                    Vector current = ent.getVelocity();
                    Vector result = current.clone().multiply(scalar);
                    result = capVector(result);
                    ent.setVelocity(result);
                }
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

    private double parseSetComponent(String arg, double currentAxis) {
        if (arg.startsWith("~")) {
            if (arg.equals("~")) {
                return currentAxis;
            } else {
                try {
                    double offset = Double.parseDouble(arg.substring(1));
                    return currentAxis + offset;
                } catch (NumberFormatException ignore) {
                }
            }
        }
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException ex) {
            return currentAxis;
        }
    }

    private double parseAddComponent(String arg) {
        if (arg.startsWith("~")) {
            if (arg.equals("~"))
                return 0.0;
            try {
                return Double.parseDouble(arg.substring(1));
            } catch (NumberFormatException ignore) {
            }
            return 0.0;
        }
        try {
            return Double.parseDouble(arg);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private double parseCaret(String arg) {
        if (!arg.startsWith("^"))
            return 0.0;
        if (arg.equals("^"))
            return 0.0;
        try {
            return Double.parseDouble(arg.substring(1));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private Vector localToWorld(Location loc, double localX, double localY, double localZ) {
        Vector forward = loc.getDirection().clone().normalize();
        Vector worldUp = new Vector(0, 1, 0);
        Vector right = forward.clone().crossProduct(worldUp).normalize();
        Vector up = worldUp;

        Vector result = new Vector(0, 0, 0);
        result.add(right.multiply(localX));
        result.add(up.multiply(localY));
        result.add(forward.multiply(localZ));
        return result;
    }

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
