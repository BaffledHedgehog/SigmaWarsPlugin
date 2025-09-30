package me.luckywars.bedwars;

import org.bukkit.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class BedwarsShopListener implements Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, BedwarsShopGui> openGuis = new HashMap<>();

    public BedwarsShopListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onInteractVillager(PlayerInteractEntityEvent e) {
        if (e.getRightClicked().getType() != EntityType.VILLAGER)
            return;
        Villager v = (Villager) e.getRightClicked();
        if (!v.getScoreboardTags().contains("bedwars_shop"))
            return;

        Player p = e.getPlayer();
        BedwarsShopGui gui = new BedwarsShopGui(plugin, p);
        openGuis.put(p.getUniqueId(), gui);
        p.openInventory(gui.inventory());
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        BedwarsShopGui gui = openGuis.get(p.getUniqueId());
        if (gui == null)
            return;

        Inventory top = e.getView().getTopInventory();
        if (!Objects.equals(top, gui.inventory()))
            return;

        Inventory clicked = e.getClickedInventory();
        Inventory bottom = e.getView().getBottomInventory();

        // Клики в НИЖНЕМ инвентаре игрока — разрешаем,
        // но запрещаем shift/двойной клик, чтобы не "затягивать" в GUI.
        if (clicked == bottom) {
            if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
                e.setCancelled(true);
            } else {
                e.setCancelled(false);
            }
            return;
        }

        // Клик вне инвентарей — разрешаем.
        if (clicked == null) {
            e.setCancelled(false);
            return;
        }

        // Всё, что в ВЕРХНЕМ инвентаре (наш магазин), — перехватываем.
        e.setCancelled(true);

        int slot = e.getRawSlot();
        ClickType click = e.getClick();

        // Верхняя строка — категории
        if (slot >= 0 && slot < 9) {
            gui.handleCategoryClick(slot);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            return;
        }

        // Перегородка из VINE
        if (slot >= 9 && slot < 18) {
            return;
        }

        // Товар / кнопка
        BedwarsShopGui.ShopAction action = gui.actionAt(slot);
        if (action == null)
            return;

        boolean shift = click.isShiftClick();
        boolean ok = action.execute(p, shift);

        if (ok) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        } else {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }

        // Обновим предметы в GUI (ценники/состояния)
        gui.refresh();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))
            return;

        BedwarsShopGui gui = openGuis.get(p.getUniqueId());
        if (gui == null)
            return;

        Inventory top = e.getView().getTopInventory();
        if (!Objects.equals(top, gui.inventory()))
            return;

        // Если хотя бы один из "сыраых" слотов — в верхнем инвентаре, блокируем.
        int topSize = top.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
        // Иначе разрешаем (всё внизу)
        e.setCancelled(false);
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        openGuis.remove(e.getPlayer().getUniqueId());
    }

    // На всякий случай — если торговца убили, ничего делать не надо.
    @EventHandler
    public void onVillagerDeath(EntityDeathEvent e) {
        if (e.getEntity().getType() != EntityType.VILLAGER)
            return;
        // no-op
    }
}
