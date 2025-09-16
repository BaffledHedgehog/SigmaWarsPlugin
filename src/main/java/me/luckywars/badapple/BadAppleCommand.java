// src/main/java/me/luckywars/badapple/BadAppleCommand.java
package me.luckywars.badapple;

import java.util.Locale;

import org.bukkit.Location;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * Paper Brigadier command entrypoint: /badapple start | stop
 */
public final class BadAppleCommand implements BasicCommand {
    private final BadAppleService service;

    public BadAppleCommand(BadAppleService service) {
        this.service = service;
    }

    @Override
    public void execute(CommandSourceStack src, String[] args) {
        if (args.length == 0) {
            src.getSender().sendMessage("§7/badapple §fstart §7| §fstop");
            return;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start" -> {
                final Location origin = src.getLocation();
                service.start(origin);
            }
            case "stop" -> service.stop();
            default -> src.getSender().sendMessage("§cНеизвестная подкоманда: §f" + sub);
        }
    }
}




