package com.govnoslav;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display.Brightness;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

public class AntigravityCommand implements CommandExecutor, BasicCommand {
    private final JavaPlugin plugin;

    public AntigravityCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Launches antigravity blocks around center.
     * 
     * @return number of blocks launched
     */
    private int runAntigravity(Location center) {
        int launched = 0;
        World world = center.getWorld();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Location loc = center.clone().add(dx-0.5, dy, dz-0.5);
                    Material mat = world.getBlockAt(loc).getType();
                    if (!mat.isSolid()) {
                        world.getBlockAt(loc).setType(Material.AIR);
                        continue;
                    }
                    // remove block and spawn display
                    world.getBlockAt(loc).setType(Material.AIR);
                    BlockDisplay display = world.spawn(loc, BlockDisplay.class);
                    display.setBlock(mat.createBlockData());
                    display.setViewRange(128f);
                    display.setShadowRadius(0f);
                    display.setShadowStrength(0f);
                    display.setBillboard(BlockDisplay.Billboard.FIXED);
                    display.setInterpolationDuration(1);
                    display.setTeleportDuration(1);
                    display.setBrightness(new Brightness(15, 15));
                    display.setRotation(0, 0);

                    launched++;
                    new BukkitRunnable() {
                        private double velocityY = 0;
                        private int ticks = 0;

                        @Override
                        public void run() {
                            if (display.isDead()) {
                                cancel();
                                return;
                            }
                            if (velocityY < 3)
                                velocityY += 0.04;
                            Location current = display.getLocation().clone().add(0, velocityY, 0);
                            display.teleport(current);

                            world.getNearbyEntities(current, 1, 1, 1).stream()
                                    .filter(e -> e instanceof Player)
                                    .map(e -> (Player) e)
                                    .forEach(p -> {
                                        Vector vel = p.getVelocity();
                                        vel.setY(velocityY+0.2);
                                        p.setVelocity(vel);
                                    });

                            ticks++;
                            boolean nearPlayer = display.getLocation()
                                    .getNearbyEntities(150, 300, 150)
                                    .stream().anyMatch(e -> e instanceof Player);
                            if (nearPlayer)
                                ticks = 0;
                            if (ticks >= 1 * 20) {
                                display.remove();
                                cancel();
                            }
                        }
                    }.runTaskTimer(plugin, 1L, 1L);
                }
            }
        }
        return launched;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            runAntigravity(player.getLocation());
        }
        // no removal logic for console or command blocks here
        return true;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        int count = runAntigravity(source.getLocation());
        Entity entity = source.getExecutor();
        // if not player source and no blocks launched, remove the source entity
        if (!(entity instanceof Player) && count == 0 && entity != null) {
            entity.remove();
        }
    }
}
