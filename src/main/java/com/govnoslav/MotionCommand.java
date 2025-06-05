package com.govnoslav;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

public class MotionCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;

    public MotionCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("motion")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage("§cИспользование: /motion <set|add> <x> <y> <z> <цели>  или  /motion multiple <scalar> <цели>");
            return true;
        }

        String type = args[0].toLowerCase();
        switch (type) {
            case "set" -> {
                if (args.length != 5) {
                    sender.sendMessage("§cИспользование: /motion set <x> <y> <z> <цели>");
                    return true;
                }
                double x, y, z;
                try {
                    x = Double.parseDouble(args[1]);
                    y = Double.parseDouble(args[2]);
                    z = Double.parseDouble(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cНеверные координаты: " + args[1] + " " + args[2] + " " + args[3]);
                    return true;
                }
                String targetArg = args[4];
                Collection<Entity> targets;
                try {
                    targets = Bukkit.selectEntities(sender, targetArg);
                } catch (org.bukkit.command.CommandException | IllegalArgumentException ex) {
                //    sender.sendMessage("§cНе удалось найти цели: " + ex.getMessage());
                    return true;
                }
                if (targets.isEmpty()) {
                 //   sender.sendMessage("§cНе найдено ни одной цели.");
                    return true;
                }
                Vector newVec = new Vector(x, y, z);
                newVec = capVector(newVec);
                for (Entity ent : targets) {
                    ent.setVelocity(newVec);
                }
                //sender.sendMessage("§aMotion установлен в (" 
                //        + newVec.getX() + ", " + newVec.getY() + ", " + newVec.getZ() 
                //        + ") для " + targets.size() + " сущностей.");
                return true;
            }
            case "add" -> {
                if (args.length != 5) {
                    sender.sendMessage("§cИспользование: /motion add <x> <y> <z> <цели>");
                    return true;
                }
                double dx, dy, dz;
                try {
                    dx = Double.parseDouble(args[1]);
                    dy = Double.parseDouble(args[2]);
                    dz = Double.parseDouble(args[3]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cНеверные координаты: " + args[1] + " " + args[2] + " " + args[3]);
                    return true;
                }
                String targetArg = args[4];
                Collection<Entity> targets;
                try {
                    targets = Bukkit.selectEntities(sender, targetArg);
                } catch (org.bukkit.command.CommandException | IllegalArgumentException ex) {
                 //   sender.sendMessage("§cНе удалось найти цели: " + ex.getMessage());
                    return true;
                }
                if (targets.isEmpty()) {
                 //   sender.sendMessage("§cНе найдено ни одной цели.");
                    return true;
                }
                Vector delta = new Vector(dx, dy, dz);
                for (Entity ent : targets) {
                    Vector current = ent.getVelocity();
                    Vector result = current.clone().add(delta);
                    result = capVector(result);
                    ent.setVelocity(result);
                }
                //sender.sendMessage("§aMotion добавлен вектор (" 
                //        + dx + ", " + dy + ", " + dz 
                //        + ") c учётом ограничения до " + targets.size() + " сущностей.");
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
                } catch (org.bukkit.command.CommandException | IllegalArgumentException ex) {
                    //sender.sendMessage("§cНе удалось найти цели: " + ex.getMessage());
                    return true;
                }
                if (targets.isEmpty()) {
                    //sender.sendMessage("§cНе найдено ни одной цели.");
                    return true;
                }
                for (Entity ent : targets) {
                    Vector current = ent.getVelocity();
                    Vector result = current.clone().multiply(scalar);
                    result = capVector(result);
                    ent.setVelocity(result);
                }
                //sender.sendMessage("§aMotion умножен на " 
                //        + scalar + " c учётом ограничения для " + targets.size() + " сущностей.");
                return true;
            }
            default -> {
                sender.sendMessage("§cНеверный тип: выберите set, add или multiple.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("motion")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return Arrays.asList("set", "add", "multiple");
        }
        if (args.length == 5 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("add"))) {
            return Arrays.asList("@a", "@p", "@r", "@e");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("multiple")) {
            return Arrays.asList("@a", "@p", "@r", "@e");
        }
        return Collections.emptyList();
    }

    /**
     * Ограничивает компоненты вектора так, чтобы ни одна из |x|, |y| или |z|
     * не превышала 10. Если превышает, масштабируем весь вектор:
     * factor = 10 / maxAbsComponent.
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
