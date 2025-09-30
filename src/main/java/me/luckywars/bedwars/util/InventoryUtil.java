package me.luckywars.bedwars.util;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class InventoryUtil {
    private InventoryUtil() {
    }

    /**
     * Сколько предметов указанного типа ещё поместится в инвентарь (учтёт частично
     * заполненные стаки).
     */
    public static int freeSpaceFor(Inventory inv, ItemStack proto) {
        if (proto == null)
            return 0;
        int maxStack = proto.getMaxStackSize();
        int can = 0;
        ItemStack[] cont = inv.getStorageContents();
        for (ItemStack cur : cont) {
            if (cur == null)
                can += maxStack;
            else if (cur.isSimilar(proto))
                can += Math.max(0, maxStack - cur.getAmount());
        }
        return can;
    }

    /**
     * Положить предметы в инвентарь. Возвращает фактически помещённое количество.
     * Остаток дропает у ног.
     */
    public static int giveOrDrop(Player p, ItemStack proto, int amount) {
        if (proto == null || amount <= 0)
            return 0;
        int left = amount;
        int maxStack = proto.getMaxStackSize();
        while (left > 0) {
            int put = Math.min(left, maxStack);
            ItemStack batch = proto.clone();
            batch.setAmount(put);
            var rem = p.getInventory().addItem(batch);
            if (!rem.isEmpty()) {
                Location loc = p.getLocation();
                rem.values().forEach(it -> p.getWorld().dropItemNaturally(loc, it));
            }
            left -= put;
        }
        return amount;
    }

    /** Удобный перегруз: использовать amount из предмета. */
    public static int giveOrDrop(Player p, ItemStack stackWithAmount) {
        if (stackWithAmount == null)
            return 0;
        return giveOrDrop(p, stackWithAmount, stackWithAmount.getAmount());
    }

    /** Одноразовая выдача (влезет ли полностью). */
    public static boolean canGiveAll(Player p, ItemStack proto, int amount) {
        ItemStack test = proto.clone();
        test.setAmount(1);
        int space = freeSpaceFor(p.getInventory(), test);
        return space >= amount;
    }

    public static void playSuccess(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    public static void playFail(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }
}
