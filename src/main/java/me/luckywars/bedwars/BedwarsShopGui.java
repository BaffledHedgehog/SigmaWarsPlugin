package me.luckywars.bedwars;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
//import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
//import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import me.luckywars.bedwars.util.CurrencyUtil;
import me.luckywars.bedwars.util.DyeColorOfTeam;
import me.luckywars.bedwars.util.EnchantOps;
import me.luckywars.bedwars.util.InventoryUtil;
import me.luckywars.bedwars.util.ItemTag;
import me.luckywars.bedwars.util.LootUtil;

import java.util.*;
import java.util.function.BiFunction;

public final class BedwarsShopGui {

    public enum Category {
        ARMOR, WEAPONS, TOOLS, FOOD, BLOCKS, SPECIAL, LUCKY, ENCHANTS, BEACONS
    }

    public interface ShopAction {
        /** @return true если покупка/действие состоялось */
        boolean execute(Player p, boolean shift);
    }

    private final Inventory inv;
    private final Player player;
    private Category current = Category.ARMOR;
    private final Map<Integer, ShopAction> actions = new HashMap<>();

    private static final int SIZE = 54;

    public BedwarsShopGui(JavaPlugin plugin, Player player) {
        this.player = player;
        this.inv = Bukkit.createInventory(player, SIZE, Component.translatable("bedwars.shop.title"));
        build();
    }

    public Inventory inventory() {
        return inv;
    }

    public void handleCategoryClick(int topRowSlot) {
        switch (topRowSlot) {
            case 0 -> current = Category.ARMOR;
            case 1 -> current = Category.WEAPONS;
            case 2 -> current = Category.TOOLS;
            case 3 -> current = Category.FOOD;
            case 4 -> current = Category.BLOCKS;
            case 5 -> current = Category.SPECIAL;
            case 6 -> current = Category.LUCKY;
            case 7 -> current = Category.ENCHANTS;
            case 8 -> current = Category.BEACONS;
            default -> {
            }
        }
        build();
    }

    public ShopAction actionAt(int slot) {
        return actions.get(slot);
    }

    public void refresh() {
        build();
    }

    /* ===================== BUILD ===================== */

    private void build() {
        actions.clear();
        for (int i = 0; i < inv.getSize(); i++)
            inv.setItem(i, null);

        // Категории (верхняя строка)
        inv.setItem(0, icon(Material.LEATHER_CHESTPLATE, "category_armor"));
        inv.setItem(1, icon(Material.GOLDEN_SWORD, "category_weapons"));
        inv.setItem(2, icon(Material.IRON_PICKAXE, "category_tools"));
        inv.setItem(3, icon(Material.COOKED_BEEF, "category_food"));
        inv.setItem(4, icon(Material.SANDSTONE, "category_blocks"));
        inv.setItem(5, icon(Material.TNT, "special_items"));

        // ItemStack icon = iconWithModel(Material.STICK, "category_lucky_blocks",
        // "lbc:lucky_block");
        // var im = icon.getItemMeta();
        // Bukkit.getLogger().info("[Shop] item_model=" + (im != null ?
        // im.getItemModel() : null));

        inv.setItem(6, iconWithModel(Material.STICK, "category_lucky_blocks", "lbc:lucky_block"));
        inv.setItem(7, icon(Material.ENCHANTING_TABLE, "category_enchantments"));
        inv.setItem(8, icon(Material.BEACON, "category_beacons"));

        // Перегородка — ряд VINE с пустым названием
        for (int s = 9; s < 18; s++)
            inv.setItem(s, separator());

        switch (current) {
            case ARMOR -> buildArmor();
            case WEAPONS -> buildWeapons();
            case TOOLS -> buildTools();
            case FOOD -> buildFood();
            case BLOCKS -> buildBlocks();
            case SPECIAL -> buildSpecial();
            case LUCKY -> buildLucky();
            case ENCHANTS -> buildEnchants();
            case BEACONS -> buildBeacons();
        }
    }

    /* ===================== CATEGORIES ===================== */

    private void buildArmor() {
        addLeatherSet(18, 19, 20, 21, DyeColorOfTeam.get(player), 1,
                price().copper(1), price().copper(2), price().copper(1), price().copper(1));

        addItem(23, enchanted(new ItemStack(Material.CHAINMAIL_CHESTPLATE), Enchantment.PROTECTION, 1),
                "armor_chain_p1", price().iron(1), 1);
        addItem(24, enchanted(new ItemStack(Material.CHAINMAIL_CHESTPLATE), Enchantment.PROTECTION, 2),
                "armor_chain_p2", price().iron(3), 1);
        addItem(25, enchanted(new ItemStack(Material.CHAINMAIL_CHESTPLATE), Enchantment.PROTECTION, 3),
                "armor_chain_p3", price().iron(5), 1);
        ItemStack heavy = new ItemStack(Material.CHAINMAIL_CHESTPLATE);
        heavy.addUnsafeEnchantment(Enchantment.PROTECTION, 3);
        heavy.addUnsafeEnchantment(Enchantment.FIRE_PROTECTION, 3);
        heavy.addUnsafeEnchantment(Enchantment.BLAST_PROTECTION, 3);
        addItem(26, heavy, "armor_chain_heavy", price().iron(5).gold(2), 1);

        addItem(36, enchanted(new ItemStack(Material.IRON_HELMET), Enchantment.PROTECTION, 1), "iron_helm_p1",
                price().iron(5).gold(5), 1);
        addItem(37, enchanted(new ItemStack(Material.IRON_CHESTPLATE), Enchantment.PROTECTION, 1), "iron_chest_p1",
                price().iron(8).gold(8), 1);
        addItem(38, enchanted(new ItemStack(Material.IRON_LEGGINGS), Enchantment.PROTECTION, 1), "iron_legs_p1",
                price().iron(7).gold(7), 1);
        addItem(39, enchanted(new ItemStack(Material.IRON_BOOTS), Enchantment.PROTECTION, 1), "iron_boots_p1",
                price().iron(4).gold(4), 1);

        addItem(41, enchanted(new ItemStack(Material.DIAMOND_HELMET), Enchantment.PROTECTION, 1), "dia_helm_p1",
                price().gold(5), 1);
        addItem(42, enchanted(new ItemStack(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION, 1), "dia_chest_p1",
                price().diamond(5), 1);
        addItem(43, enchanted(new ItemStack(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION, 1), "dia_legs_p1",
                price().gold(8).diamond(8), 1);
        addItem(44, enchanted(new ItemStack(Material.DIAMOND_BOOTS), Enchantment.PROTECTION, 1), "dia_boots_p1",
                price().gold(4).diamond(4), 1);
    }

    private void buildWeapons() {
        addItem(18, new ItemStack(Material.WOODEN_AXE), "wep_wood_axe", price().copper(5), 1);
        ItemStack kbStick = new ItemStack(Material.STICK);
        kbStick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        addItem(19, kbStick, "wep_kbstick1", price().copper(64), 1);

        addItem(20, enchanted(new ItemStack(Material.GOLDEN_SWORD), Enchantment.SHARPNESS, 1),
                "wep_gsword_s1", price().iron(1), 1);
        addItem(21, enchanted(new ItemStack(Material.GOLDEN_SWORD), Enchantment.SHARPNESS, 2),
                "wep_gsword_s2", price().iron(3), 1);
        addItem(22, enchanted(new ItemStack(Material.GOLDEN_SWORD), Enchantment.SHARPNESS, 3),
                "wep_gsword_s3", price().iron(5), 1);

        ItemStack is1k1 = new ItemStack(Material.IRON_SWORD);
        is1k1.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);
        is1k1.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        addItem(23, is1k1, "wep_iron_s1k1", price().iron(5).gold(3), 1);

        ItemStack ds1k1 = new ItemStack(Material.DIAMOND_SWORD);
        ds1k1.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);
        ds1k1.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        addItem(24, ds1k1, "wep_dia_s1k1", price().gold(10).diamond(5), 1);

        ItemStack mace = new ItemStack(Material.MACE);
        mace.addUnsafeEnchantment(Enchantment.DENSITY, 5);
        mace.addUnsafeEnchantment(Enchantment.WIND_BURST, 1);
        addItem(25, mace, "wep_mace_d5w1", price().gold(15).diamond(7), 1);

        addItem(26, new ItemStack(Material.WIND_CHARGE, 8), price().gold(8), 8);

        addItem(38, new ItemStack(Material.BOW, 1),
                "bow_p1", price().gold(3), 1);
        addItem(39, enchanted(new ItemStack(Material.BOW), Enchantment.POWER, 1),
                "bow_p2", price().gold(5), 1);
        addItem(40, enchanted(new ItemStack(Material.BOW), Enchantment.POWER, 2),
                "bow_p3", price().gold(5), 1);
        ItemStack bowK = new ItemStack(Material.BOW);
        bowK.addUnsafeEnchantment(Enchantment.POWER, 2);
        bowK.addUnsafeEnchantment(Enchantment.PUNCH, 1);
        addItem(41, bowK, "bow_p3_k1", price().gold(10).diamond(2), 1);
        ItemStack bowMax = new ItemStack(Material.BOW);
        bowMax.addUnsafeEnchantment(Enchantment.POWER, 3);
        bowMax.addUnsafeEnchantment(Enchantment.PUNCH, 1);
        bowMax.addUnsafeEnchantment(Enchantment.FLAME, 1);
        addItem(42, bowMax, "bow_p4_k1_f1", price().gold(15).diamond(10), 1);

        addItem(49, new ItemStack(Material.ARROW, 8), price().gold(1), 8);
    }

    private void buildTools() {
        addItem(19, new ItemStack(Material.SHEARS), "tool_shears", price().copper(4), 1);
        addItem(20, new ItemStack(Material.WOODEN_PICKAXE), "tool_wood_pick", price().copper(7), 1);
        addItem(21, new ItemStack(Material.IRON_PICKAXE), "tool_iron_pick", price().iron(2), 1);
        addItem(22, enchant(new ItemStack(Material.IRON_PICKAXE), Enchantment.EFFICIENCY, 2), "tool_iron_pick_e2",
                price().iron(5).gold(2), 1);
        addItem(23, enchant(new ItemStack(Material.IRON_PICKAXE), Enchantment.EFFICIENCY, 4), "tool_iron_pick_e4",
                price().iron(10).gold(5), 1);
        addItem(24, enchant(new ItemStack(Material.DIAMOND_PICKAXE), Enchantment.EFFICIENCY, 3), "tool_dia_pick_e3",
                price().gold(7).diamond(5), 1);
        addItem(25, enchant(new ItemStack(Material.GOLDEN_AXE), Enchantment.EFFICIENCY, 5), "tool_gold_axe_e5",
                price().gold(1), 1);
    }

    private void buildFood() {
        addItem(20, new ItemStack(Material.BREAD), price().copper(2), 1);
        addItem(21, new ItemStack(Material.COOKED_BEEF), price().copper(7), 1);
        addItem(22, new ItemStack(Material.GOLDEN_CARROT, 2), price().iron(1), 2);
        addItem(23, new ItemStack(Material.GOLDEN_APPLE), price().gold(3), 1);
        addItem(24, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), price().gold(10).diamond(2), 1);
    }

    private void buildBlocks() {
        DyeColor color = DyeColorOfTeam.get(player);
        Material wool = switch (color) {
            case RED -> Material.RED_WOOL;
            case BLUE -> Material.BLUE_WOOL;
            case GREEN -> Material.GREEN_WOOL;
            case YELLOW -> Material.YELLOW_WOOL;
            case PURPLE -> Material.PURPLE_WOOL;
            case ORANGE -> Material.ORANGE_WOOL;
            case WHITE -> Material.WHITE_WOOL;
            case BLACK -> Material.BLACK_WOOL;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_WOOL;
            case LIME -> Material.LIME_WOOL;
            case PINK -> Material.PINK_WOOL;
            case CYAN -> Material.CYAN_WOOL;
            case GRAY -> Material.GRAY_WOOL;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_WOOL;
            case BROWN -> Material.BROWN_WOOL;
            case MAGENTA -> Material.MAGENTA_WOOL;
        };
        Material concrete = switch (color) {
            case RED -> Material.RED_CONCRETE;
            case BLUE -> Material.BLUE_CONCRETE;
            case GREEN -> Material.GREEN_CONCRETE;
            case YELLOW -> Material.YELLOW_CONCRETE;
            case PURPLE -> Material.PURPLE_CONCRETE;
            case ORANGE -> Material.ORANGE_CONCRETE;
            case WHITE -> Material.WHITE_CONCRETE;
            case BLACK -> Material.BLACK_CONCRETE;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_CONCRETE;
            case LIME -> Material.LIME_CONCRETE;
            case PINK -> Material.PINK_CONCRETE;
            case CYAN -> Material.CYAN_CONCRETE;
            case GRAY -> Material.GRAY_CONCRETE;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_CONCRETE;
            case BROWN -> Material.BROWN_CONCRETE;
            case MAGENTA -> Material.MAGENTA_CONCRETE;
        };

        addItem(19, new ItemStack(wool, 3), price().copper(1), 3);
        addItem(20, new ItemStack(Material.SANDSTONE, 2), price().copper(3), 2);
        addItem(21, new ItemStack(concrete, 1), price().copper(5), 1);
        addItem(22, new ItemStack(Material.END_STONE, 1), price().copper(7), 1);
        addItem(23, new ItemStack(Material.GLASS, 10), price().iron(1), 10);
        addItem(24, new ItemStack(Material.SLIME_BLOCK, 2), price().iron(4), 2);
        addItem(25, new ItemStack(Material.DARK_OAK_FENCE, 4), price().iron(6), 4);
        addItem(31, new ItemStack(Material.OBSIDIAN, 24), price().gold(30).diamond(15), 24);
    }

    private void buildSpecial() {
        addItem(18, new ItemStack(Material.CHEST), price().iron(2), 1);
        addItem(19, new ItemStack(Material.ENDER_CHEST), price().gold(6), 1);

        final ItemStack teamChest = ItemTag.markTeamChest(new ItemStack(Material.ENDER_CHEST));
        teamChest.editMeta(m -> m.itemName(Component.translatable("team_chest")));
        addItem(20, teamChest, "team_chest", price().gold(6), 1,
                (p, shift) -> CurrencyUtil.tryBuy(p, price().gold(6), 1, (count) -> {
                    ItemStack give = teamChest.clone();
                    give.setAmount(count);
                    InventoryUtil.giveOrDrop(p, give);
                }));

        addItem(21, new ItemStack(Material.TNT), price().iron(2).gold(1), 1);
        addItem(22, new ItemStack(Material.COMPASS), price().gold(3).diamond(1), 1);
        addItem(23, new ItemStack(Material.FLINT_AND_STEEL), price().gold(2), 1);
        addItem(24, new ItemStack(Material.COBWEB), price().iron(4), 1);

        addItem(25, new ItemStack(Material.LADDER, 6), price().copper(3), 6);

        // стало:
        final ItemStack teamZombie = ItemTag.markTeamZombie(new ItemStack(Material.ZOMBIE_SPAWN_EGG));
        teamZombie.editMeta(m -> m.itemName(Component.translatable("team_zombie")));
        addItem(26, teamZombie, "team_zombie", price().iron(12), 1);

        addItem(29, potion(Material.POTION, PotionType.REGENERATION, false, false), price().gold(5),
                1);
        addItem(30, potion(Material.POTION, PotionType.STRENGTH, false, false),
                price().gold(10).diamond(2), 1);
        ItemStack str2 = potion(Material.POTION, PotionType.STRENGTH, true, false);
        addItem(31, str2, price().diamond(6).netherStar(6), 1);
        addItem(32, potion(Material.POTION, PotionType.TURTLE_MASTER, false, false),
                price().iron(16), 1);
        ItemStack fire8 = potion(Material.POTION, PotionType.FIRE_RESISTANCE, false, true);
        addItem(33, fire8, price().gold(3), 1);
    }

    private void buildLucky() {
        addLootTable(29, "lbc:luckyblock_block_only", price().copper(16).iron(4));
        addLootTable(31, "lbc:luckyblock_neko_block", price().gold(8).iron(4));
        addLootTable(33, "lbc:luckyblock_loli_block", price().diamond(8).gold(4));
    }

    private void buildEnchants() {
        addButton(29, new ItemStack(Material.IRON_CHESTPLATE), "enchant_armor",
                price().netherStar(10),
                (p, times) -> EnchantOps.enchantArmorAll(p, times));

        addButton(30, new ItemStack(Material.IRON_SWORD), "enchant_weapon",
                price().netherStar(10),
                (p, times) -> EnchantOps.enchantWeapons(p, times));

        addButton(31, new ItemStack(Material.IRON_PICKAXE), "enchant_tool",
                price().netherStar(5),
                (p, times) -> EnchantOps.enchantToolsEfficiency(p, times));

        addButton(32, new ItemStack(Material.BOW), "enchant_bow",
                price().netherStar(25),
                (p, times) -> EnchantOps.enchantBowsStrong(p, times));

        addButton(33, new ItemStack(Material.STICK), "upgrade_knockback",
                price().netherStar(10),
                (p, times) -> EnchantOps.upgradeKnockback(p, times));
    }

    private void buildBeacons() {
        addBeacon(19, new ItemStack(Material.SUGAR), "beacon_speed2_5m", price().netherStar(5),
                BeaconEffectsManager.Buff.SPEED2, 5 * 60);
        addBeacon(20, new ItemStack(Material.RABBIT_FOOT), "beacon_jump2_5m", price().netherStar(5),
                BeaconEffectsManager.Buff.JUMP2, 5 * 60);
        addBeacon(21, new ItemStack(Material.MAGMA_CREAM), "beacon_fire_15m", price().netherStar(5),
                BeaconEffectsManager.Buff.FIRE_RES, 15 * 60);
        addBeacon(22, new ItemStack(Material.BLAZE_POWDER), "beacon_strength2_2m", price().netherStar(5),
                BeaconEffectsManager.Buff.STRENGTH2, 2 * 60);
        addBeacon(23, new ItemStack(Material.GOLDEN_PICKAXE), "beacon_haste2_10m", price().netherStar(5),
                BeaconEffectsManager.Buff.HASTE2, 10 * 60);
        addBeacon(24, new ItemStack(Material.GHAST_TEAR), "beacon_regen2_3m", price().netherStar(5),
                BeaconEffectsManager.Buff.REGEN2, 3 * 60);
        addBeacon(25, new ItemStack(Material.SHIELD), "beacon_resistance1_5m", price().netherStar(5),
                BeaconEffectsManager.Buff.RESISTANCE1, 5 * 60);
    }
    /* ===================== HELPERS ===================== */

    private ItemStack icon(Material m, String key) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> meta.itemName(Component.translatable(key)));
        return it;
    }

    private ItemStack iconWithModel(Material m, String key, String itemModel) {
        ItemStack it = icon(m, key);
        it = ItemTag.withModel(it, itemModel);
        return it;
    }

    private ItemStack separator() {
        ItemStack it = new ItemStack(Material.PALE_HANGING_MOSS);
        it.editMeta(meta -> meta.itemName(Component.text("")));
        it.editMeta(meta -> meta.setHideTooltip(true));
        return it;
    }

    private static ItemStack enchanted(ItemStack base, Enchantment ench, int lvl) {
        base.addUnsafeEnchantment(ench, lvl);
        return base;
    }

    private static ItemStack enchant(ItemStack base, Enchantment ench, int lvl) {
        base.addUnsafeEnchantment(ench, lvl);
        return base;
    }

    // ======= POTIONS (без deprecated API) =======
    private static ItemStack potion(Material type, PotionType base, boolean upgraded, boolean extended) {
        ItemStack it = new ItemStack(type);
        it.editMeta(m -> {
            if (m instanceof PotionMeta pm) {
                pm.setBasePotionType(resolvePotionType(base, upgraded, extended));
            }
        });
        return it;
    }

    private static PotionType resolvePotionType(PotionType base, boolean upgraded, boolean extended) {
        if (upgraded) {
            PotionType strong = valueOfOrNull("STRONG_" + base.name());
            if (strong != null)
                return strong;
        }
        if (extended) {
            PotionType longer = valueOfOrNull("LONG_" + base.name());
            if (longer != null)
                return longer;
        }
        return base;
    }

    private static PotionType valueOfOrNull(String name) {
        try {
            return PotionType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void addLeatherSet(int slotHelm, int slotChest, int slotLegs, int slotBoots,
            DyeColor color, int prot,
            Price pHelm, Price pChest, Price pLegs, Price pBoots) {
        addItem(slotHelm, leather(Material.LEATHER_HELMET, color, prot), "leather_helm_p1", pHelm, 1);
        addItem(slotChest, leather(Material.LEATHER_CHESTPLATE, color, prot), "leather_chest_p1", pChest, 1);
        addItem(slotLegs, leather(Material.LEATHER_LEGGINGS, color, prot), "leather_legs_p1", pLegs, 1);
        addItem(slotBoots, leather(Material.LEATHER_BOOTS, color, prot), "leather_boots_p1", pBoots, 1);
    }

    private ItemStack leather(Material m, DyeColor color, int protectionLvl) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            if (meta instanceof LeatherArmorMeta lam)
                lam.setColor(color.getColor());
            meta.addEnchant(Enchantment.PROTECTION, protectionLvl, true);
        });
        return it;
    }

    /* ======= ПЕРЕГРУЗКИ addItem ======= */
    private void addItem(int slot, ItemStack item, String translateKey, Price price, int gives) {
        // что показываем в GUI
        ItemStack shown = item.clone();
        if (translateKey != null && !translateKey.isEmpty()) {
            shown.editMeta(m -> m.itemName(Component.translatable(translateKey)));
        }
        setPriceLore(shown, price);
        inv.setItem(slot, shown);

        // что реально выдаём при покупке (с автоэкипом брони, если слот пуст)
        actions.put(slot, (p, shift) -> CurrencyUtil.tryBuy(p, price,
                shift ? stackPurchases(item, gives) : 1,
                (count) -> {
                    ItemStack base = item.clone();
                    if (translateKey != null && !translateKey.isEmpty()) {
                        base.editMeta(m -> m.itemName(Component.translatable(translateKey)));
                    }
                    int total = Math.min(base.getMaxStackSize(), count * gives);

                    // сначала пытаемся надеть 1 шт. брони, если это броня и слот пуст
                    if (isArmor(base.getType()) && tryAutoEquipArmor(p, base)) {
                        total -= 1;
                    }

                    if (total > 0) {
                        ItemStack give = base.clone();
                        give.setAmount(total);
                        InventoryUtil.giveOrDrop(p, give);
                    }
                }));
    }

    private void addItem(int slot, ItemStack item, Price price, int gives) {
        addItem(slot, item, null, price, gives,
                (p, shift) -> CurrencyUtil.tryBuy(p, price,
                        shift ? stackPurchases(item, gives) : 1,
                        (count) -> {
                            ItemStack base = item.clone();
                            int total = Math.min(base.getMaxStackSize(), count * gives);

                            if (isArmor(base.getType()) && tryAutoEquipArmor(p, base)) {
                                total -= 1;
                            }

                            if (total > 0) {
                                ItemStack give = base.clone();
                                give.setAmount(total);
                                InventoryUtil.giveOrDrop(p, give);
                            }
                        }));
    }

    private void addItem(int slot, ItemStack item, String translateKey, Price price, int gives, ShopAction handler) {
        ItemStack shown = item.clone();
        if (translateKey != null && !translateKey.isEmpty()) {
            shown.editMeta(m -> m.itemName(Component.translatable(translateKey)));
        }
        setPriceLore(shown, price);
        inv.setItem(slot, shown);
        actions.put(slot, handler);
    }

    /* ===== helpers: автоэкип брони ===== */

    private static boolean isEmpty(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    private static boolean isArmor(Material m) {
        return switch (m) {
            // helmets
            case LEATHER_HELMET, CHAINMAIL_HELMET, IRON_HELMET, GOLDEN_HELMET,
                    DIAMOND_HELMET, NETHERITE_HELMET, TURTLE_HELMET ->
                true;
            // chestplates
            case LEATHER_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_CHESTPLATE, GOLDEN_CHESTPLATE,
                    DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE ->
                true;
            // leggings
            case LEATHER_LEGGINGS, CHAINMAIL_LEGGINGS, IRON_LEGGINGS, GOLDEN_LEGGINGS,
                    DIAMOND_LEGGINGS, NETHERITE_LEGGINGS ->
                true;
            // boots
            case LEATHER_BOOTS, CHAINMAIL_BOOTS, IRON_BOOTS, GOLDEN_BOOTS,
                    DIAMOND_BOOTS, NETHERITE_BOOTS ->
                true;
            default -> false;
        };
    }

    private static boolean tryAutoEquipArmor(Player p, ItemStack item) {
        final var inv = p.getInventory();
        switch (item.getType()) {
            // helmet
            case LEATHER_HELMET, CHAINMAIL_HELMET, IRON_HELMET, GOLDEN_HELMET,
                    DIAMOND_HELMET, NETHERITE_HELMET, TURTLE_HELMET -> {
                if (isEmpty(inv.getHelmet())) {
                    inv.setHelmet(item.clone());
                    return true;
                }
            }
            // chest
            case LEATHER_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_CHESTPLATE, GOLDEN_CHESTPLATE,
                    DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> {
                if (isEmpty(inv.getChestplate())) {
                    inv.setChestplate(item.clone());
                    return true;
                }
            }
            // legs
            case LEATHER_LEGGINGS, CHAINMAIL_LEGGINGS, IRON_LEGGINGS, GOLDEN_LEGGINGS,
                    DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> {
                if (isEmpty(inv.getLeggings())) {
                    inv.setLeggings(item.clone());
                    return true;
                }
            }
            // boots
            case LEATHER_BOOTS, CHAINMAIL_BOOTS, IRON_BOOTS, GOLDEN_BOOTS,
                    DIAMOND_BOOTS, NETHERITE_BOOTS -> {
                if (isEmpty(inv.getBoots())) {
                    inv.setBoots(item.clone());
                    return true;
                }
            }
            default -> {
            }
        }
        return false;
    }

    private void addButton(int slot, ItemStack icon, String translateKey, Price price,
            BiFunction<Player, Integer, Boolean> fn) {
        ItemStack shown = icon.clone();
        shown.editMeta(m -> {
            m.itemName(Component.translatable(translateKey));
            m.lore(buildPriceLore(price));
        });
        inv.setItem(slot, shown);
        actions.put(slot, (p, shift) -> CurrencyUtil.tryBuy(p, price, 1, (ignored) -> fn.apply(p, 1)));
    }

    private void addBeacon(int slot, ItemStack icon, String translateKey, Price price,
            BeaconEffectsManager.Buff buff, int seconds) {
        ItemStack shown = icon.clone();
        shown.editMeta(m -> {
            m.itemName(Component.translatable(translateKey));
            m.lore(buildPriceLore(price));
        });
        inv.setItem(slot, shown);

        actions.put(slot, (p, shift) -> CurrencyUtil.tryBuy(p, price, 1, (count) -> {
            int total = Math.max(1, count) * Math.max(0, seconds);
            BeaconEffectsManager.addTimeForTeam(p, buff, total);
        }));
    }

    private void addLootTable(int slot, String lootTableKey, Price price) {
        ItemStack original = LootUtil.singleFrom(lootTableKey, player.getWorld(), player.getLocation());

        if (original == null) {
            ItemStack err = new ItemStack(Material.BARRIER);
            err.editMeta(m -> m.itemName(Component.text("Missing loot: " + lootTableKey)));
            inv.setItem(slot, err);
            return;
        }

        // Показ в GUI: сохраняем имя/мету из таблицы, НО лор очищаем и заменяем на
        // ценник
        ItemStack shown = original.clone();
        shown.editMeta(m -> m.lore(Collections.emptyList())); // вычищаем существующий лор
        setPriceLore(shown, price);

        inv.setItem(slot, shown);

        // Покупка: отдаём **ровно** оригинал из таблицы без ценника
        actions.put(slot, (p, shift) -> CurrencyUtil.tryBuy(p, price, 1, (ignored) -> {
            ItemStack give = original.clone(); // НИКАКИХ правок
            InventoryUtil.giveOrDrop(p, give);
        }));
    }

    private static int stackPurchases(ItemStack item, int givesPerPurchase) {
        int max = item.getMaxStackSize();
        return (int) Math.ceil((double) max / (double) givesPerPurchase);
    }

    /* ====== PRICE → LORE ====== */

    private static List<Component> buildPriceLore(Price price) {
        List<Component> lore = new ArrayList<>(5);
        if (price.copper > 0)
            lore.add(priceLine(price.copper, "copper_ingot", NamedTextColor.GOLD));
        if (price.iron > 0)
            lore.add(priceLine(price.iron, "iron_ingot", NamedTextColor.GRAY));
        if (price.gold > 0)
            lore.add(priceLine(price.gold, "gold_ingot", NamedTextColor.GOLD));
        if (price.diamond > 0)
            lore.add(priceLine(price.diamond, "diamond", NamedTextColor.AQUA));
        if (price.star > 0)
            lore.add(priceLine(price.star, "nether_star", NamedTextColor.RED));
        return lore;
    }

    /* ====== PRICES ====== */

    private static Price price() {
        return new Price();
    }

    public static final class Price {
        public int copper;
        public int iron;
        public int gold;
        public int diamond;
        public int star;

        public Price copper(int n) {
            this.copper += n;
            return this;
        }

        public Price iron(int n) {
            this.iron += n;
            return this;
        }

        public Price gold(int n) {
            this.gold += n;
            return this;
        }

        public Price diamond(int n) {
            this.diamond += n;
            return this;
        }

        public Price netherStar(int n) {
            this.star += n;
            return this;
        }
    }

    // ===== PRICE LORE (helpers) =====
    private static List<Component> priceLore(Price p) {
        List<Component> lines = new ArrayList<>(5);
        // Порядок как обсуждали
        if (p.copper > 0)
            lines.add(priceLine(p.copper, "item.minecraft.copper_ingot", NamedTextColor.GOLD)); // "оранжевая"
        if (p.iron > 0)
            lines.add(priceLine(p.iron, "item.minecraft.iron_ingot", NamedTextColor.GRAY)); // "светло-серая"
        if (p.gold > 0)
            lines.add(priceLine(p.gold, "item.minecraft.gold_ingot", NamedTextColor.GOLD)); // "золотая"
        if (p.diamond > 0)
            lines.add(priceLine(p.diamond, "item.minecraft.diamond", NamedTextColor.AQUA)); // "голубая"
        if (p.star > 0)
            lines.add(priceLine(p.star, "item.minecraft.nether_star", NamedTextColor.RED)); // "красная"
        return lines;
    }

    private static Component priceLine(int amount, String vanillaKey, NamedTextColor matColor) {
        // "N× " — белый + bold
        Component left = Component.text(amount + "× ", NamedTextColor.WHITE)
                .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true);
        // название валюты — ванильный translate + нужный цвет
        Component right = Component.translatable(vanillaKey).color(matColor);
        return left.append(right);
    }

    private static void setPriceLore(ItemStack stack, Price p) {
        stack.editMeta(m -> m.lore(priceLore(p)));
    }
}
