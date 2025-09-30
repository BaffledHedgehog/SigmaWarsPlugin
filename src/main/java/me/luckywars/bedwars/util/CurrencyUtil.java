package me.luckywars.bedwars.util;

import me.luckywars.bedwars.BedwarsShopGui;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

public final class CurrencyUtil {
    private CurrencyUtil() {
    }

    /** Поддерживаемые валюты. */
    public static final Set<Material> CURRENCIES = Set.of(
            Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT,
            Material.DIAMOND, Material.NETHER_STAR);

    /** Сколько данной валюты у игрока (сумма по всем стакам). */
    public static int count(Player p, Material currency) {
        if (!CURRENCIES.contains(currency))
            return 0;
        int total = 0;
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && it.getType() == currency)
                total += it.getAmount();
        return total;
    }

    /** Проверить, хватает ли по карте цен. */
    public static boolean hasEnough(Player p, Map<Material, Integer> cost) {
        if (cost == null || cost.isEmpty())
            return true;
        for (Map.Entry<Material, Integer> e : cost.entrySet()) {
            final int need = Math.max(0, e.getValue());
            if (need == 0)
                continue;
            if (!CURRENCIES.contains(e.getKey()))
                return false;
            if (count(p, e.getKey()) < need)
                return false;
        }
        return true;
    }

    /** Списать цены ИЗ инвентаря. */
    public static boolean take(Player p, Map<Material, Integer> cost) {
        if (!hasEnough(p, cost))
            return false;

        Map<Material, AtomicInteger> remain = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Integer> e : cost.entrySet()) {
            if (e.getValue() > 0)
                remain.put(e.getKey(), new AtomicInteger(e.getValue()));
        }
        ItemStack[] cont = p.getInventory().getContents();
        for (int i = 0; i < cont.length; i++) {
            ItemStack it = cont[i];
            if (it == null)
                continue;
            AtomicInteger need = remain.get(it.getType());
            if (need == null)
                continue;

            int have = it.getAmount();
            if (have <= 0)
                continue;

            int take = Math.min(have, need.get());
            if (take > 0) {
                it.setAmount(have - take);
                if (it.getAmount() <= 0)
                    cont[i] = null;
                need.addAndGet(-take);
            }
        }
        p.getInventory().setContents(cont);

        for (AtomicInteger v : remain.values())
            if (v.get() > 0)
                return false;
        return true;
    }

    /** Максимум повторений покупки (для SHIFT), ограничение cap. */
    public static int maxAffordableRepeats(Player p, Map<Material, Integer> costPerOne, int cap) {
        if (costPerOne == null || costPerOne.isEmpty())
            return cap;
        int max = Integer.MAX_VALUE;
        for (Map.Entry<Material, Integer> e : costPerOne.entrySet()) {
            int per = Math.max(0, e.getValue());
            if (per == 0)
                continue;
            int have = count(p, e.getKey());
            max = Math.min(max, have / per);
            if (max == 0)
                return 0;
        }
        return Math.min(max, cap);
    }

    /** Билдер цен: costOf().c(IRON,3).c(GOLD,2).map(); */
    public static Builder costOf() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Material, Integer> m = new EnumMap<>(Material.class);

        public Builder c(Material mat, int amt) {
            if (amt > 0)
                m.merge(mat, amt, Integer::sum);
            return this;
        }

        public Map<Material, Integer> map() {
            return Collections.unmodifiableMap(m);
        }
    }

    /* ===== адаптер под GUI.Price ===== */

    private static Map<Material, Integer> toMap(BedwarsShopGui.Price price) {
        Map<Material, Integer> m = new EnumMap<>(Material.class);
        if (price.copper > 0)
            m.put(Material.COPPER_INGOT, price.copper);
        if (price.iron > 0)
            m.put(Material.IRON_INGOT, price.iron);
        if (price.gold > 0)
            m.put(Material.GOLD_INGOT, price.gold);
        if (price.diamond > 0)
            m.put(Material.DIAMOND, price.diamond);
        if (price.star > 0)
            m.put(Material.NETHER_STAR, price.star);
        return m;
    }

    private static Map<Material, Integer> multiplied(Map<Material, Integer> base, int k) {
        if (k <= 1)
            return base;
        Map<Material, Integer> m = new EnumMap<>(Material.class);
        for (var e : base.entrySet())
            m.put(e.getKey(), e.getValue() * k);
        return m;
    }

    /**
     * Универсальная покупка: пробуем купить до {@code maxRepeats} раз (SHIFT).
     * Если хватает на меньшее число — купим максимум. onSuccess принимает
     * фактическое количество.
     */
    public static boolean tryBuy(Player p, BedwarsShopGui.Price price, int maxRepeats, IntConsumer onSuccess) {
        Map<Material, Integer> perOne = toMap(price);
        if (perOne.isEmpty()) {
            onSuccess.accept(1);
            InventoryUtil.playSuccess(p);
            return true;
        }

        int can = maxAffordableRepeats(p, perOne, maxRepeats);
        if (can <= 0) {
            InventoryUtil.playFail(p);
            return false;
        }

        Map<Material, Integer> total = multiplied(perOne, can);
        if (!take(p, total)) {
            InventoryUtil.playFail(p);
            return false;
        }

        onSuccess.accept(can);
        InventoryUtil.playSuccess(p);
        return true;
    }
}
