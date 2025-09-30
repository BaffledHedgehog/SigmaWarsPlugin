package me.luckywars.bedwars.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Упрощённый доступ к PDC-метаданным предметов. */
public final class ItemTag {
    private ItemTag() {
    }

    private static final String NS = "bedwars";
    public static final NamespacedKey K_CATEGORY = new NamespacedKey(NS, "category");
    public static final NamespacedKey K_BUTTON_ID = new NamespacedKey(NS, "button_id");
    public static final NamespacedKey K_UNSTACK = new NamespacedKey(NS, "nonce");

    // Доп. ключи
    public static final NamespacedKey K_TEAM_CHEST = new NamespacedKey(NS, "team_chest");
    public static final NamespacedKey K_TEAM_ZOMBIE = new NamespacedKey(NS, "team_zombie");
    public static final NamespacedKey K_ITEM_MODEL = new NamespacedKey(NS, "item_model"); // строковый id модели

    public static ItemStack setCategory(ItemStack it, String cat) {
        return setString(it, K_CATEGORY, cat);
    }

    public static String getCategory(ItemStack it) {
        return getString(it, K_CATEGORY);
    }

    public static ItemStack setButtonId(ItemStack it, String id) {
        return setString(it, K_BUTTON_ID, id);
    }

    public static String getButtonId(ItemStack it) {
        return getString(it, K_BUTTON_ID);
    }

    public static ItemStack makeUnstackable(ItemStack it) {
        return setString(it, K_UNSTACK, UUID.randomUUID().toString());
    }

    /** Пометить предмет как командный сундук. */
    public static ItemStack markTeamChest(ItemStack it) {
        return setInt(it, K_TEAM_CHEST, 1);
    }

    public static boolean isTeamChest(ItemStack it) {
        return getInt(it, K_TEAM_CHEST) != null;
    }

    /** Пометить предмет как «яйцо зомби команды». */
    public static ItemStack markTeamZombie(ItemStack it) {
        return setInt(it, K_TEAM_ZOMBIE, 1);
    }

    public static boolean isTeamZombie(ItemStack it) {
        return getInt(it, K_TEAM_ZOMBIE) != null;
    }

    public static ItemStack withModel(ItemStack it, String namespacedModel) {
        if (it == null)
            return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return it;

        NamespacedKey key = NamespacedKey.fromString(namespacedModel); // например "lbc:lucky_block"
        if (key != null) {
            meta.setItemModel(key); // ⟵ это и есть item_model
            it.setItemMeta(meta);
        }
        return it;
    }

    public static String getModel(ItemStack it) {
        return getString(it, K_ITEM_MODEL);
    }

    /* ===== generic helpers ===== */

    public static ItemStack setString(ItemStack it, NamespacedKey key, String val) {
        if (it == null)
            return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return it;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (val == null)
            pdc.remove(key);
        else
            pdc.set(key, PersistentDataType.STRING, val);
        it.setItemMeta(meta);
        return it;
    }

    public static String getString(ItemStack it, NamespacedKey key) {
        if (it == null)
            return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return null;
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static ItemStack setInt(ItemStack it, NamespacedKey key, int val) {
        if (it == null)
            return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return it;
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, val);
        it.setItemMeta(meta);
        return it;
    }

    public static Integer getInt(ItemStack it, NamespacedKey key) {
        if (it == null)
            return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return null;
        return meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
    }
}
