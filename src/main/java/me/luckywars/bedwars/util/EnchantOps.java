package me.luckywars.bedwars.util;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

public final class EnchantOps {
    private EnchantOps() {
    }

    private static final Set<Material> WEAPONS = EnumSet.of(
            Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD, Material.GOLDEN_SWORD,
            Material.DIAMOND_SWORD, Material.NETHERITE_SWORD,
            Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE,
            Material.NETHERITE_AXE,
            Material.TRIDENT, Material.MACE);
    private static final Set<Material> TOOLS = EnumSet.of(
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.GOLDEN_PICKAXE,
            Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE,
            Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE,
            Material.NETHERITE_AXE,
            Material.WOODEN_SHOVEL, Material.STONE_SHOVEL, Material.IRON_SHOVEL, Material.GOLDEN_SHOVEL,
            Material.DIAMOND_SHOVEL, Material.NETHERITE_SHOVEL,
            Material.WOODEN_HOE, Material.STONE_HOE, Material.IRON_HOE, Material.GOLDEN_HOE, Material.DIAMOND_HOE,
            Material.NETHERITE_HOE,
            Material.SHEARS);
    private static final Set<Material> BOWS = EnumSet.of(Material.BOW);

    /* ===== базовая операция ===== */

    public static boolean incEnchant(ItemStack it, Enchantment ench, int delta, int maxLevel, boolean ignoreCap) {
        if (it == null || it.getType().isAir() || ench == null)
            return false;
        int cur = it.getEnchantmentLevel(ench);
        int next = cur + delta;
        if (!ignoreCap && maxLevel > 0)
            next = Math.min(next, maxLevel);
        if (next <= cur)
            return false;
        it.addUnsafeEnchantment(ench, next);
        return true;
    }

    private static void forEach(Player p, Predicate<ItemStack> pred, java.util.function.Consumer<ItemStack> op) {
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && pred.test(it))
                op.accept(it);
        for (ItemStack it : p.getInventory().getArmorContents())
            if (it != null && pred.test(it))
                op.accept(it);
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && pred.test(off))
            op.accept(off);
    }

    /* ===== операции по ТЗ ===== */

    /** Броня на игроке: +1 к Fire/Projectile/Blast/Feather Falling (до 5). */
    public static int upgradeArmorOnPlayer(Player p) {
        final Enchantment[] list = {
                Enchantment.FIRE_PROTECTION,
                Enchantment.PROJECTILE_PROTECTION,
                Enchantment.BLAST_PROTECTION,
                Enchantment.FEATHER_FALLING
        };
        int changed = 0;
        for (ItemStack armor : p.getInventory().getArmorContents()) {
            if (armor == null)
                continue;
            for (Enchantment ench : list) {
                if (incEnchant(armor, ench, 1, 5, true))
                    changed++;
            }
        }
        return changed;
    }

    /** Все оружия в инвентаре: +1 к Sharpness (до 5). */
    public static int upgradeWeaponsSharpness(Player p) {
        return applyTo(p, it -> WEAPONS.contains(it.getType()),
                it -> incEnchant(it, Enchantment.SHARPNESS, 1, 10, true));
    }

    /** Все инструменты в инвентаре: +1 к Efficiency (до 5). */
    public static int upgradeToolsEfficiency(Player p) {
        return applyTo(p, it -> TOOLS.contains(it.getType()),
                it -> incEnchant(it, Enchantment.EFFICIENCY, 1, 10, true));
    }

    /** Все луки: +2 к Power (до 5) + Infinity/Flame/Punch >= 1. */
    public static int upgradeBows(Player p) {
        int changed = 0;
        changed += applyTo(p, it -> BOWS.contains(it.getType()),
                it -> incEnchant(it, Enchantment.POWER, 2, 10, true));
        changed += applyTo(p, it -> BOWS.contains(it.getType()),
                it -> ensureAtLeast(it, Enchantment.INFINITY, 1));
        changed += applyTo(p, it -> BOWS.contains(it.getType()),
                it -> ensureAtLeast(it, Enchantment.FLAME, 1));
        changed += applyTo(p, it -> BOWS.contains(it.getType()),
                it -> ensureAtLeast(it, Enchantment.PUNCH, 1));
        return changed;
    }

    /** +1 к Knockback на всех предметах, где уже есть Knockback (без лимита). */
    public static int upgradeKnockbackEverywhere(Player p) {
        return applyTo(p, it -> it.getEnchantmentLevel(Enchantment.KNOCKBACK) > 0,
                it -> incEnchant(it, Enchantment.KNOCKBACK, 1, Integer.MAX_VALUE, true));
    }

    /* ===== helpers ===== */

    private static int applyTo(Player p, Predicate<ItemStack> pred, java.util.function.Predicate<ItemStack> op) {
        final int[] cnt = { 0 };
        forEach(p, pred, it -> {
            if (op.test(it))
                cnt[0]++;
        });
        return cnt[0];
    }

    private static boolean ensureAtLeast(ItemStack it, Enchantment ench, int level) {
        int cur = it.getEnchantmentLevel(ench);
        if (cur >= level)
            return false;
        it.addUnsafeEnchantment(ench, level);
        return true;
    }

    /* ===== врапперы под вызовы из GUI ===== */

    public static boolean enchantArmorAll(Player p, int times) {
        boolean any = false;
        for (int i = 0; i < Math.max(1, times); i++)
            any |= upgradeArmorOnPlayer(p) > 0;
        return any;
    }

    public static boolean enchantWeapons(Player p, int times) {
        boolean any = false;
        for (int i = 0; i < Math.max(1, times); i++)
            any |= upgradeWeaponsSharpness(p) > 0;
        return any;
    }

    public static boolean enchantToolsEfficiency(Player p, int times) {
        boolean any = false;
        for (int i = 0; i < Math.max(1, times); i++)
            any |= upgradeToolsEfficiency(p) > 0;
        return any;
    }

    public static boolean enchantBowsStrong(Player p, int times) {
        boolean any = false;
        for (int i = 0; i < Math.max(1, times); i++)
            any |= upgradeBows(p) > 0;
        return any;
    }

    public static boolean upgradeKnockback(Player p, int times) {
        boolean any = false;
        for (int i = 0; i < Math.max(1, times); i++)
            any |= upgradeKnockbackEverywhere(p) > 0;
        return any;
    }
}
