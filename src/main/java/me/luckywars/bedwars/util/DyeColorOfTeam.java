package me.luckywars.bedwars.util;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

public final class DyeColorOfTeam {
    private DyeColorOfTeam() {
    }

    /** Найти команду игрока по основному скорборду сервера. */
    public static Team teamOf(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : sb.getTeams()) {
            if (t.hasEntry(p.getName()))
                return t;
        }
        return null;
    }

    /** Alias для совместимости со старыми вызовами. */
    public static DyeColor get(Player p) {
        return dyeOf(p);
    }

    /** Цвет команды → DyeColor (наиболее близкий по Team.color()). */
    /** Цвет команды → DyeColor (строгое соответствие NamedTextColor команде). */
    /** Цвет команды → DyeColor (строгое соответствие, без приближений). */
    public static DyeColor dyeOf(Player p) {
        Team t = teamOf(p);
        if (t == null || !t.hasColor())
            return DyeColor.WHITE;

        TextColor tc = t.color();
        if (!(tc instanceof NamedTextColor ntc))
            return DyeColor.WHITE;

        // соответствие ровно как ты описал для команд 1..16
        if (ntc == NamedTextColor.AQUA)
            return DyeColor.LIGHT_BLUE; // team 1
        if (ntc == NamedTextColor.BLACK)
            return DyeColor.BLACK; // team 2
        if (ntc == NamedTextColor.BLUE)
            return DyeColor.BLUE; // team 3
        if (ntc == NamedTextColor.DARK_AQUA)
            return DyeColor.CYAN; // team 4
        if (ntc == NamedTextColor.DARK_BLUE)
            return DyeColor.PURPLE; // team 5
        if (ntc == NamedTextColor.DARK_GRAY)
            return DyeColor.GRAY; // team 6
        if (ntc == NamedTextColor.DARK_GREEN)
            return DyeColor.GREEN; // team 7
        if (ntc == NamedTextColor.DARK_PURPLE)
            return DyeColor.MAGENTA; // team 8
        if (ntc == NamedTextColor.DARK_RED)
            return DyeColor.BROWN; // team 9
        if (ntc == NamedTextColor.GOLD)
            return DyeColor.ORANGE; // team 10
        if (ntc == NamedTextColor.GRAY)
            return DyeColor.LIGHT_GRAY; // team 11
        if (ntc == NamedTextColor.GREEN)
            return DyeColor.LIME; // team 12
        if (ntc == NamedTextColor.LIGHT_PURPLE)
            return DyeColor.PINK; // team 13
        if (ntc == NamedTextColor.RED)
            return DyeColor.RED; // team 14
        if (ntc == NamedTextColor.WHITE)
            return DyeColor.WHITE; // team 15
        if (ntc == NamedTextColor.YELLOW)
            return DyeColor.YELLOW; // team 16

        return DyeColor.WHITE; // на всякий случай
    }

    // в DyeColorOfTeam.java
    /** NamedTextColor → DyeColor. */
    private static DyeColor dyeFromNamed(NamedTextColor c) {
        if (c == null)
            return DyeColor.WHITE;

        if (c == NamedTextColor.RED)
            return DyeColor.RED;
        if (c == NamedTextColor.BLUE)
            return DyeColor.BLUE;
        if (c == NamedTextColor.DARK_GREEN)
            return DyeColor.GREEN;
        if (c == NamedTextColor.GREEN)
            return DyeColor.LIME;
        if (c == NamedTextColor.AQUA)
            return DyeColor.LIGHT_BLUE;
        if (c == NamedTextColor.DARK_AQUA)
            return DyeColor.CYAN;
        if (c == NamedTextColor.YELLOW)
            return DyeColor.YELLOW;
        if (c == NamedTextColor.GOLD)
            return DyeColor.ORANGE;
        if (c == NamedTextColor.DARK_PURPLE)
            return DyeColor.MAGENTA;
        if (c == NamedTextColor.DARK_BLUE)
            return DyeColor.PURPLE;
        if (c == NamedTextColor.WHITE)
            return DyeColor.WHITE;
        if (c == NamedTextColor.GRAY)
            return DyeColor.LIGHT_GRAY;
        if (c == NamedTextColor.DARK_GRAY)
            return DyeColor.GRAY;
        if (c == NamedTextColor.BLACK)
            return DyeColor.BLACK;
        if (c == NamedTextColor.DARK_RED)
            return DyeColor.BROWN;
        if (c == NamedTextColor.LIGHT_PURPLE)
            return DyeColor.PINK;

        return DyeColor.WHITE;
    }

    public static NamedTextColor textColorOf(DyeColor d) {
        return switch (d) {
            case RED -> NamedTextColor.RED;
            case BLUE -> NamedTextColor.BLUE;
            case GREEN -> NamedTextColor.DARK_GREEN;
            case LIME -> NamedTextColor.GREEN;
            case LIGHT_BLUE -> NamedTextColor.AQUA;
            case CYAN -> NamedTextColor.DARK_AQUA;
            case YELLOW -> NamedTextColor.YELLOW;
            case ORANGE -> NamedTextColor.GOLD;
            case MAGENTA -> NamedTextColor.DARK_PURPLE;
            case PURPLE -> NamedTextColor.DARK_BLUE;
            case WHITE -> NamedTextColor.WHITE;
            case LIGHT_GRAY -> NamedTextColor.GRAY;
            case GRAY -> NamedTextColor.DARK_GRAY;
            case BLACK -> NamedTextColor.BLACK;
            case BROWN -> NamedTextColor.DARK_RED;
            case PINK -> NamedTextColor.LIGHT_PURPLE;
            default -> NamedTextColor.WHITE;
        };
    }

    public static Color leatherColor(DyeColor d) {
        return Color.fromRGB(d.getColor().getRed(), d.getColor().getGreen(), d.getColor().getBlue());
    }

    /** Покрасить кожаную броню в цвет команды. */
    public static ItemStack dyeLeather(ItemStack armor, Player p) {
        if (armor == null)
            return null;
        if (!(armor.getItemMeta() instanceof LeatherArmorMeta meta))
            return armor;
        meta.setColor(leatherColor(dyeOf(p)));
        armor.setItemMeta(meta);
        return armor;
    }

    /* Материалы по цвету команды (шерсть, бетон, стекло). */
    public static Material woolOf(DyeColor d) {
        return switch (d) {
            case WHITE -> Material.WHITE_WOOL;
            case ORANGE -> Material.ORANGE_WOOL;
            case MAGENTA -> Material.MAGENTA_WOOL;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_WOOL;
            case YELLOW -> Material.YELLOW_WOOL;
            case LIME -> Material.LIME_WOOL;
            case PINK -> Material.PINK_WOOL;
            case GRAY -> Material.GRAY_WOOL;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_WOOL;
            case CYAN -> Material.CYAN_WOOL;
            case PURPLE -> Material.PURPLE_WOOL;
            case BLUE -> Material.BLUE_WOOL;
            case BROWN -> Material.BROWN_WOOL;
            case GREEN -> Material.GREEN_WOOL;
            case RED -> Material.RED_WOOL;
            case BLACK -> Material.BLACK_WOOL;
        };
    }

    public static Material concreteOf(DyeColor d) {
        return switch (d) {
            case WHITE -> Material.WHITE_CONCRETE;
            case ORANGE -> Material.ORANGE_CONCRETE;
            case MAGENTA -> Material.MAGENTA_CONCRETE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_CONCRETE;
            case YELLOW -> Material.YELLOW_CONCRETE;
            case LIME -> Material.LIME_CONCRETE;
            case PINK -> Material.PINK_CONCRETE;
            case GRAY -> Material.GRAY_CONCRETE;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_CONCRETE;
            case CYAN -> Material.CYAN_CONCRETE;
            case PURPLE -> Material.PURPLE_CONCRETE;
            case BLUE -> Material.BLUE_CONCRETE;
            case BROWN -> Material.BROWN_CONCRETE;
            case GREEN -> Material.GREEN_CONCRETE;
            case RED -> Material.RED_CONCRETE;
            case BLACK -> Material.BLACK_CONCRETE;
        };
    }

    public static Material stainedGlassOf(DyeColor d) {
        return switch (d) {
            case WHITE -> Material.WHITE_STAINED_GLASS;
            case ORANGE -> Material.ORANGE_STAINED_GLASS;
            case MAGENTA -> Material.MAGENTA_STAINED_GLASS;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_STAINED_GLASS;
            case YELLOW -> Material.YELLOW_STAINED_GLASS;
            case LIME -> Material.LIME_STAINED_GLASS;
            case PINK -> Material.PINK_STAINED_GLASS;
            case GRAY -> Material.GRAY_STAINED_GLASS;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_STAINED_GLASS;
            case CYAN -> Material.CYAN_STAINED_GLASS;
            case PURPLE -> Material.PURPLE_STAINED_GLASS;
            case BLUE -> Material.BLUE_STAINED_GLASS;
            case BROWN -> Material.BROWN_STAINED_GLASS;
            case GREEN -> Material.GREEN_STAINED_GLASS;
            case RED -> Material.RED_STAINED_GLASS;
            case BLACK -> Material.BLACK_STAINED_GLASS;
        };
    }
}
