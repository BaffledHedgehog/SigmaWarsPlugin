package me.luckywars;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Brigadier /motion с полным execute-контекстом (позиция/поворот) и корректной
 * поддержкой @s.
 *
 * /motion set <x> <y> <z> <targets>
 * /motion add <x> <y> <z> <targets>
 * /motion multiple <scalar> <targets>
 */
public final class MotionBrigadier implements BasicCommand {

    private static final String LOG_PREFIX = "[/motion] ";
    private static final Pattern NUMERIC = Pattern.compile("^[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)$");

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        logInvoker(source, "motion", args);

        if (args.length == 0) {
            source.getSender().sendMessage(Component.text("§cИспользование: /motion <set|add|multiple> ..."));
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "set" -> execSet(source, args);
                case "add" -> execAdd(source, args);
                case "multiple" -> execMultiple(source, args);
                default -> {
                    warn(source, "unknown subcommand: '%s'", sub);
                    source.getSender().sendMessage(Component.text("§cНеверный тип: выберите set, add или multiple."));
                }
            }
        } catch (Throwable t) {
            warn(source, "exception: %s", String.valueOf(t));
            source.getSender().sendMessage(Component.text("§cОшибка при выполнении /motion (см. лог)."));
            t.printStackTrace();
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 1)
            return List.of("set", "add", "multiple");
        if ((args.length == 5 && (eq(args[0], "set") || eq(args[0], "add")))
                || (args.length == 3 && eq(args[0], "multiple"))) {
            return List.of("@s", "@p", "@a", "@e", "@e[type=player]", "@e[type=!player]");
        }
        return List.of();
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return sender.hasPermission("lws.motion");
    }

    @Override
    public String permission() {
        return "lws.motion";
    }

    // ==== subcommands ====

    private void execSet(CommandSourceStack source, String[] args) {
        if (args.length != 5) {
            source.getSender().sendMessage(Component.text("§cИспользование: /motion set <x> <y> <z> <цели>"));
            return;
        }
        String sx = args[1], sy = args[2], sz = args[3];
        String targetArg = args[4];

        Collection<Entity> targets = resolveTargets(source, targetArg);
        if (targets.isEmpty()) {
            source.getSender().sendMessage(Component.text("§cЦели не найдены: " + targetArg));
            return;
        }

        Basis basis = basisFromSource(source);

        for (Entity ent : targets) {
            if (!(ent instanceof LivingEntity))
                continue;

            Vector current = ent.getVelocity();
            Vector finalVec;

            if (isCaret(sx) || isCaret(sy) || isCaret(sz)) {
                double lx = parseCaret(sx);
                double ly = parseCaret(sy);
                double lz = parseCaret(sz);
                finalVec = localToWorld(basis, lx, ly, lz);
                //debug(source, "SET(^): %s -> local=(%.3f,%.3f,%.3f) world=%s",
                //        entInfo(ent), lx, ly, lz, vecStr(finalVec));
            } else {
                double x = parseSetComponent(sx, current.getX());
                double y = parseSetComponent(sy, current.getY());
                double z = parseSetComponent(sz, current.getZ());
                finalVec = new Vector(x, y, z);
                //debug(source, "SET(~/abs): %s -> cur=%s final=%s",
                //        entInfo(ent), vecStr(current), vecStr(finalVec));
            }

            finalVec = capVector(finalVec);
            ent.setVelocity(finalVec);
        }
    }

    private void execAdd(CommandSourceStack source, String[] args) {
        if (args.length != 5) {
            source.getSender().sendMessage(Component.text("§cИспользование: /motion add <x> <y> <z> <цели>"));
            return;
        }
        String sx = args[1], sy = args[2], sz = args[3];
        String targetArg = args[4];

        Collection<Entity> targets = resolveTargets(source, targetArg);
        if (targets.isEmpty()) {
            source.getSender().sendMessage(Component.text("§cЦели не найдены: " + targetArg));
            return;
        }

        Basis basis = basisFromSource(source);

        for (Entity ent : targets) {
            if (!(ent instanceof LivingEntity))
                continue;

            Vector current = ent.getVelocity();
            Vector delta;

            if (isCaret(sx) || isCaret(sy) || isCaret(sz)) {
                double lx = parseCaret(sx);
                double ly = parseCaret(sy);
                double lz = parseCaret(sz);
                delta = localToWorld(basis, lx, ly, lz);
                //debug(source, "ADD(^): %s -> local=(%.3f,%.3f,%.3f) delta=%s",
                //        entInfo(ent), lx, ly, lz, vecStr(delta));
            } else {
                double dx = parseAddComponent(sx);
                double dy = parseAddComponent(sy);
                double dz = parseAddComponent(sz);
                delta = new Vector(dx, dy, dz);
                //debug(source, "ADD(~/abs): %s -> cur=%s delta=%s",
                //        entInfo(ent), vecStr(current), vecStr(delta));
            }

            Vector result = current.clone().add(delta);
            result = capVector(result);
            ent.setVelocity(result);
        }
    }

    private void execMultiple(CommandSourceStack source, String[] args) {
        if (args.length != 3) {
            source.getSender().sendMessage(Component.text("§cИспользование: /motion multiple <scalar> <цели>"));
            return;
        }
        double scalar;
        try {
            scalar = Double.parseDouble(args[1]);
        } catch (NumberFormatException ex) {
            warn(source, "invalid scalar: '%s'", args[1]);
            source.getSender().sendMessage(Component.text("§cНеверное число: " + args[1]));
            return;
        }
        String targetArg = args[2];

        Collection<Entity> targets = resolveTargets(source, targetArg);
        if (targets.isEmpty()) {
            source.getSender().sendMessage(Component.text("§cЦели не найдены: " + targetArg));
            return;
        }

        for (Entity ent : targets) {
            if (!(ent instanceof LivingEntity))
                continue;
            Vector current = ent.getVelocity();
            Vector result = current.clone().multiply(scalar);
            result = capVector(result);
            ent.setVelocity(result);
            //debug(source, "MULT: %s -> cur=%s * %.3f = %s",
            //        entInfo(ent), vecStr(current), scalar, vecStr(result));
        }
    }

    // ==== parsing/selectors ====

    private Collection<Entity> resolveTargets(CommandSourceStack source, String targetArg) {
        // Спец-кейс: '@s' — это ИМЕННО executor (а не sender)
        if ("@s".equals(targetArg)) {
            Entity exec = source.getExecutor();
            if (exec != null) {
                info(source, "executor '@s' -> %s", entInfo(exec));
                return Collections.singleton(exec);
            } else if (source.getSender() instanceof Entity e) {
                info(source, "sender-as '@s' -> %s", entInfo(e));
                return Collections.singleton(e);
            } else {
                warn(source, "'@s' has no executor (sender=%s)", source.getSender().getClass().getSimpleName());
                // не return — дадим шанс дополнительному парсингу ниже
            }
        }

        // Сначала — как есть, с текущим sender (Console в твоём кейсе)
        if (targetArg.startsWith("@")) {
            try {
                Collection<Entity> sel = Bukkit.selectEntities(source.getSender(), targetArg);
                info(source, "selector '%s' by sender(%s) -> %d target(s)",
                        targetArg, source.getSender().getClass().getSimpleName(), sel.size());
                if (!sel.isEmpty())
                    return sel;
            } catch (IllegalArgumentException ex) {
                warn(source, "selector parse failed by sender for '%s': %s", targetArg, ex.getMessage());
            }

            // Повторяем с EXECUTOR как sender — это ключ для @s и многих execute-кейсов
            Entity exec = source.getExecutor();
            if (exec != null) {
                try {
                    Collection<Entity> sel = Bukkit.selectEntities(exec, targetArg);
                    info(source, "selector '%s' by executor(%s) -> %d target(s)",
                            targetArg, exec.getType().name(), sel.size());
                    if (!sel.isEmpty())
                        return sel;
                } catch (IllegalArgumentException ex) {
                    warn(source, "selector parse failed by executor for '%s': %s", targetArg, ex.getMessage());
                }
            }
        }

        // Имя игрока
        Player p = Bukkit.getPlayerExact(targetArg);
        if (p != null) {
            info(source, "name '%s' -> 1 player", targetArg);
            return Collections.singleton(p);
        }

        // UUID
        try {
            UUID uuid = UUID.fromString(targetArg);
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (uuid.equals(e.getUniqueId())) {
                        info(source, "uuid '%s' -> %s", targetArg, entInfo(e));
                        return Collections.singleton(e);
                    }
                }
            }
        } catch (IllegalArgumentException ignore) {
        }

        // numeric entityId
        try {
            int id = Integer.parseInt(targetArg);
            for (World w : Bukkit.getWorlds()) {
                for (Entity e : w.getEntities()) {
                    if (e.getEntityId() == id) {
                        info(source, "numeric id %d -> %s", id, entInfo(e));
                        return Collections.singleton(e);
                    }
                }
            }
        } catch (NumberFormatException ignore) {
        }

        warn(source, "targets not found for '%s'", targetArg);
        return Collections.emptyList();
    }

    private boolean isCaret(String s) {
        return s.startsWith("^");
    }

    private double parseSetComponent(String arg, double currentAxis) {
        if (arg.startsWith("~")) {
            if (arg.equals("~"))
                return currentAxis;
            try {
                return currentAxis + Double.parseDouble(arg.substring(1));
            } catch (NumberFormatException ignore) {
                return currentAxis;
            }
        }
        if (NUMERIC.matcher(arg).matches())
            return Double.parseDouble(arg);
        return currentAxis;
    }

    private double parseAddComponent(String arg) {
        if (arg.startsWith("~")) {
            if (arg.equals("~"))
                return 0.0;
            try {
                return Double.parseDouble(arg.substring(1));
            } catch (NumberFormatException ignore) {
                return 0.0;
            }
        }
        if (NUMERIC.matcher(arg).matches())
            return Double.parseDouble(arg);
        return 0.0;
    }

    private double parseCaret(String arg) {
        if (!arg.startsWith("^") || arg.equals("^"))
            return 0.0;
        try {
            return Double.parseDouble(arg.substring(1));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    // ==== basis / math ====

    private static final class Basis {
        final Vector origin;
        final Vector forward;
        final Vector right;
        final Vector up;

        Basis(Vector origin, Vector forward, Vector right, Vector up) {
            this.origin = origin;
            this.forward = forward;
            this.right = right;
            this.up = up;
        }
    }

    private Basis basisFromSource(CommandSourceStack source) {
        // позиция/поворот полностью из execute-контекста
        Location loc = source.getLocation();
        float yaw = loc.getYaw();
        float pitch = loc.getPitch();

        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);

        Vector forward = new Vector(
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch);
        if (forward.lengthSquared() == 0)
            forward = new Vector(0, 0, 1);
        forward.normalize();

        Vector worldUp = new Vector(0, 1, 0);
        Vector right = forward.clone().crossProduct(worldUp);
        if (right.lengthSquared() == 0)
            right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = worldUp;

        Vector origin = new Vector(loc.getX(), loc.getY(), loc.getZ());
        return new Basis(origin, forward, right, up);
    }

    private Vector localToWorld(Basis b, double localX, double localY, double localZ) {
        Vector result = new Vector();
        result.add(b.right.clone().multiply(localX));
        result.add(b.up.clone().multiply(localY));
        result.add(b.forward.clone().multiply(localZ));
        return result;
    }

    private Vector capVector(Vector v) {
        double ax = Math.abs(v.getX()), ay = Math.abs(v.getY()), az = Math.abs(v.getZ());
        double max = Math.max(ax, Math.max(ay, az));
        if (max <= 10.0)
            return v;
        double factor = 10.0 / max;
        return new Vector(v.getX() * factor, v.getY() * factor, v.getZ() * factor);
    }

    // ==== logging ====

    private void logInvoker(CommandSourceStack source, String label, String[] args) {
        String where = switch (source.getSender()) {
            case Entity e -> {
                Location l = e.getLocation();
                yield String.format("%s at %s %s (%.2f, %.2f, %.2f)",
                        entInfo(e),
                        (l.getWorld() != null ? l.getWorld().getName() : "null"),
                        e.getType(),
                        l.getX(), l.getY(), l.getZ());
            }
            case ConsoleCommandSender ignored -> "Console";
            default -> source.getSender().getClass().getSimpleName();
        };
        String joined = String.join(" ", Arrays.asList(args));
        Bukkit.getLogger().info(LOG_PREFIX + "invoker=" + where + " cmd=/" + label + " args=[" + joined + "]");

        Location l = source.getLocation();
        Entity exec = source.getExecutor();
        Bukkit.getLogger().info(LOG_PREFIX + String.format(
                "execute-context pos=(%.2f,%.2f,%.2f) rot=(pitch=%.2f, yaw=%.2f), world=%s, executor=%s",
                l.getX(), l.getY(), l.getZ(), l.getPitch(), l.getYaw(),
                (l.getWorld() != null ? l.getWorld().getName() : "null"),
                (exec == null ? "null" : exec.getType().name() + "#" + exec.getEntityId())));
    }

    private void info(CommandSourceStack source, String fmt, Object... args) {
        String msg = LOG_PREFIX + String.format(fmt, args);
        Bukkit.getLogger().info(msg);
        if (!(source.getSender() instanceof ConsoleCommandSender))
            source.getSender().sendMessage(Component.text("§7" + msg));
    }

    private void warn(CommandSourceStack source, String fmt, Object... args) {
        String msg = LOG_PREFIX + String.format(fmt, args);
        Bukkit.getLogger().warning(msg);
        if (!(source.getSender() instanceof ConsoleCommandSender))
            source.getSender().sendMessage(Component.text("§e" + msg));
    }

    private void debug(CommandSourceStack source, String fmt, Object... args) {
        String msg = LOG_PREFIX + String.format(fmt, args);
        Bukkit.getLogger().info(msg);
    }

    private String entInfo(Entity e) {
        String name = (e instanceof Player p) ? p.getName() : e.getType().name();
        Location l = e.getLocation();
        String world = l.getWorld() != null ? l.getWorld().getName() : "null";
        return String.format("%s#%d@%s(%.1f,%.1f,%.1f)", name, e.getEntityId(), world, l.getX(), l.getY(), l.getZ());
    }

    private String vecStr(Vector v) {
        return String.format("(%.3f, %.3f, %.3f)", v.getX(), v.getY(), v.getZ());
    }

    private static boolean eq(String a, String b) {
        return a.equalsIgnoreCase(b);
    }
}
