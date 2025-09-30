package me.luckywars.bedwars.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

import java.util.Collection;
import java.util.Random;

public final class LootUtil {
    private LootUtil() {
    }

    private static final Random RNG = new Random();

    /**
     * Достать один предмет из таблицы лута (таблица гарантировано выдаёт 1 предмет
     * по ТЗ).
     */
    public static ItemStack rollSingleItem(World world, NamespacedKey tableKey, Player looter) {
        LootTable table = Bukkit.getLootTable(tableKey);
        if (table == null)
            return null;

        Location center = looter != null ? looter.getLocation()
                : new Location(world, 0.5, world.getSpawnLocation().getY(), 0.5);
        LootContext ctx = new LootContext.Builder(center)
                .luck(0)
                .killer(looter)
                .build();

        Collection<ItemStack> out = table.populateLoot(RNG, ctx);
        if (out == null || out.isEmpty())
            return null;

        for (ItemStack it : out) {
            if (it != null && it.getType().isItem())
                return it;
        }
        return null;
    }

    /** Упрощённый вызов: ключ строкой и координата выдачи. */
    public static ItemStack singleFrom(String namespacedKey, World world, Location where) {
        NamespacedKey key = NamespacedKey.fromString(namespacedKey);
        if (key == null)
            return null;
        LootTable table = Bukkit.getLootTable(key);
        if (table == null)
            return null;

        LootContext ctx = new LootContext.Builder(where)
                .luck(0)
                .build();

        Collection<ItemStack> out = table.populateLoot(RNG, ctx);
        if (out == null || out.isEmpty())
            return null;
        for (ItemStack it : out) {
            if (it != null && it.getType().isItem())
                return it;
        }
        return null;
    }
}
