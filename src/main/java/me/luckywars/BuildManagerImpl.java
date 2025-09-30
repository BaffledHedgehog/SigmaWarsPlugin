package me.luckywars;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import io.papermc.paper.connection.PlayerCommonConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.datacomponent.DataComponentTypes;
//import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.StorageNBTComponent;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

// !!! Проверь версию пакета CraftBukkit ниже (v1_21_R1 может отличаться на твоём билдe)


import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
//import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.*;
import org.bukkit.entity.Entity;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BuildManagerImpl implements Listener, CommandExecutor {

    // === Конфиг ===
    private static final String CMD = "buildmanager";
    public static final String PASSWORD_OBJECTIVE = "lbc.math";
    public static final String PASSWORD_ENTRY = "ticker";

    private static final int SLOTS = 24;
    // 4 колонки «больших» слотов + рядом маленькая X → по факту 8 колонок (парами:
    // [slot][X])
    private static final int COLUMNS = 6;
    private static final int BTN_WIDTH_MAIN = 150;
    private static final int BTN_WIDTH_X = 20;

    // translate-ключи
    private static final String TL_TITLE_MAIN = "build_manager";
    private static final String TL_TITLE_SAVE = "build_manager_save";
    private static final String TL_EMPTY = "empty";
    private static final String TL_SAVE = "save";
    private static final String TL_DELETE = "delete";
    private static final String TL_DELETE_TOOLTIP = "delete_tooltip";
    public static final String TL_WRONG_PASSWORD = "wrong_password";

    private static final boolean DEBUG_ICONS = false;

    private void log(String msg) {
        if (DEBUG_ICONS)
            plugin.getLogger().info("[BuildIcons] " + msg);
    }

    // Глобальные скорборды, которые мы сохраняем/восстанавливаем
    private static final List<String> OBJECTIVES = List.of(
            "swrg.kit", "swrg.skill",
            "lbc.kit", "lbc.skill",
            "lbc.trinket1", "lbc.trinket2", "lbc.trinket3",
            "lbc.levelup1", "lbc.levelup2", "lbc.levelup3",
            "lbc.challenge.kit", "lbc.challenge.skill",
            "lbc.challenge.trinket1", "lbc.challenge.trinket2", "lbc.challenge.trinket3",
            "lbc.challenge.levelup1", "lbc.challenge.levelup2", "lbc.challenge.levelup3");

    private final Plugin plugin;
    private final File jsonFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // playerName -> список из 24 слотов (null = пусто)
    private final Map<String, List<BuildRecord>> db = new ConcurrentHashMap<>();

    public BuildManagerImpl(Plugin plugin) {
        this.plugin = plugin;
        // server/plugins/lws/builds.json
        File pluginsDir = plugin.getDataFolder().getParentFile(); // .../plugins
        File lwsDir = new File(pluginsDir, "lws");
        if (!lwsDir.exists()) {
            // noinspection ResultOfMethodCallIgnored
            lwsDir.mkdirs();
        }
        this.jsonFile = new File(lwsDir, "builds.json");
        loadDb();
        // Регистрация
        Objects.requireNonNull(plugin.getServer().getPluginCommand(CMD)).setExecutor(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    // === Команда /buildmanager <password> ===
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.translatable(TL_WRONG_PASSWORD).color(NamedTextColor.RED));
            return true;
        }

        // 1) пароль
        final int passArg;
        try {
            passArg = Integer.parseInt(args[0]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Component.translatable(TL_WRONG_PASSWORD).color(NamedTextColor.RED));
            return true;
        }

        // 2) найти "эффективного" игрока (чата / execute / datapack / селектор)
        Player p = null;

        if (sender instanceof Player sp) {
            p = sp;
        } else if (sender instanceof ProxiedCommandSender pcs) {
            if (pcs.getCallee() instanceof Player cp)
                p = cp;
            else if (pcs.getCaller() instanceof Player caller)
                p = caller;
        }

        // если дали целевой аргумент — пробуем как селектор/ник
        if (p == null && args.length >= 2) {
            try {
                List<Entity> targets = Bukkit.selectEntities(sender, args[1]); // поддерживает @s, @p, @a, @e[..]
                for (Entity e : targets)
                    if (e instanceof Player) {
                        p = (Player) e;
                        break;
                    }
            } catch (IllegalArgumentException ignored) {
            }
            if (p == null) {
                Player byName = Bukkit.getPlayerExact(args[1]);
                if (byName != null)
                    p = byName;
            }
        }

        // последний шанс — попробуем @s из контекста
        if (p == null) {
            try {
                for (Entity e : Bukkit.selectEntities(sender, "@s")) {
                    if (e instanceof Player) {
                        p = (Player) e;
                        break;
                    }
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (p == null) {
            sender.sendMessage(Component.text("Only players.", NamedTextColor.RED));
            return true;
        }

        // 3) проверяем пароль по глобальному scoreboard'у
        Integer current = getScoreValue(
                Bukkit.getScoreboardManager().getMainScoreboard(),
                PASSWORD_OBJECTIVE, PASSWORD_ENTRY, 0);
        if (current == null || current != passArg) {
            p.sendMessage(Component.translatable(TL_WRONG_PASSWORD).color(NamedTextColor.RED));
            return true;
        }

        openMainDialog(p);
        return true;
    }

    // === Главный диалог со слотами ===
    void openMainDialog(Player player) {
        List<ActionButton> buttons = new ArrayList<>(SLOTS * 2);
        String name = player.getName();
        List<BuildRecord> list = db.computeIfAbsent(name, n -> new ArrayList<>(Collections.nCopies(SLOTS, null)));
        for (int i = 0; i < SLOTS; i++) {
            BuildRecord rec = (i < list.size()) ? list.get(i) : null;

            Component label = (rec == null)
                    ? Component.translatable(TL_EMPTY)
                    : Component.text(rec.name());

            // tooltip: для сохранённых слотов — превью предметов из сохранённых значений
            Component tooltip = null;
            if (rec != null) {
                tooltip = buildItemsTooltip(player, rec.scores());
            }

            // Главная кнопка слота
            ActionButton mainBtn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .width(BTN_WIDTH_MAIN)
                    .action(DialogAction.customClick(Key.key("lws:build/open/" + i), null))
                    .build();

            // Кнопка удаления X
            ActionButton delBtn = ActionButton.builder(Component.translatable(TL_DELETE))
                    .tooltip(Component.translatable(TL_DELETE_TOOLTIP))
                    .width(BTN_WIDTH_X)
                    .action(DialogAction.customClick(Key.key("lws:build/delete/" + i), null))
                    .build();

            buttons.add(mainBtn);
            buttons.add(delBtn);
        }

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.translatable(TL_TITLE_MAIN))
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons, null, COLUMNS)));

        // Audience#showDialog(DialogLike)
        player.showDialog(dialog); // см. доки Paper про Audience#showDialog(DialogLike).
                                   // :contentReference[oaicite:1]{index=1}
    }

    // === Диалог сохранения слота ===
    private void openSaveDialog(Player player, int slot) {
        // соберём иконки по текущим значениям игрока
        Map<String, Integer> scores = readAllScoresFor(player);
        List<DialogBody> bodies = new ArrayList<>();
        for (var pair : resolveIconItems(player, scores)) {
            ItemStack it = pair.getKey(); // вот он, сам предмет
            bodies.add(
                    DialogBody.item(it)
                            .showTooltip(true)
                            .showDecorations(false)
                            .width(16).height(16)
                            .build());
        }

        // поле ввода имени билда
        DialogInput nameInput = DialogInput.text("build_name", Component.translatable("build_name"))
                .initial("")
                .width(300)
                .build();

        ActionButton saveBtn = ActionButton.create(
                Component.translatable(TL_SAVE),
                Component.text(""),
                120,
                DialogAction.customClick(Key.key("lws:build/save/" + slot), null));

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.translatable(TL_TITLE_SAVE))
                        .canCloseWithEscape(true)
                        .body(bodies)
                        .inputs(List.of(nameInput))
                        .build())
                .type(DialogType.notice(saveBtn)));

        player.showDialog(dialog);
    }

    // === Обработка кликов по кастом-кнопкам ===
    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier(); // идентификатор действия кнопки :contentReference[oaicite:2]{index=2}
        String key = id.asString();

        // Нам нужен игрок
        Player player = null;
        PlayerCommonConnection conn = event.getCommonConnection();
        if (conn instanceof PlayerGameConnection pgc) {
            player = pgc.getPlayer(); // как получить Player из события — см. доки
                                      // :contentReference[oaicite:3]{index=3}
        }
        if (player == null)
            return;

        // Разбор ключа
        if (key.startsWith("lws:build/open/")) {
            int slot = parseSlot(key, "lws:build/open/");
            handleOpenSlot(player, slot);
        } else if (key.startsWith("lws:build/delete/")) {
            int slot = parseSlot(key, "lws:build/delete/");
            handleDeleteSlot(player, slot);
        } else if (key.startsWith("lws:build/save/")) {
            int slot = parseSlot(key, "lws:build/save/");
            // достаём значение инпута из DialogResponseView
            var view = event.getDialogResponseView(); // :contentReference[oaicite:4]{index=4}
            String buildName = (view == null) ? "" : Optional.ofNullable(view.getText("build_name")).orElse("").trim();
            if (buildName.isEmpty())
                buildName = "build_" + (slot + 1);
            handleSaveSlot(player, slot, buildName);
        }
    }

    private int parseSlot(String key, String prefix) {
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private void handleOpenSlot(Player player, int slot) {
        if (slot < 0 || slot >= SLOTS)
            return;
        List<BuildRecord> list = db.computeIfAbsent(player.getName(),
                n -> new ArrayList<>(Collections.nCopies(SLOTS, null)));
        BuildRecord rec = (slot < list.size()) ? list.get(slot) : null;
        if (rec == null) {
            openSaveDialog(player, slot);
            return;
        }
        // восстановить сохранённые значения
        writeAllScoresFor(player, rec.scores());
        // вернуть в главный диалог
        openMainDialog(player);
    }

    private void handleDeleteSlot(Player player, int slot) {
        if (slot < 0 || slot >= SLOTS)
            return;
        List<BuildRecord> list = db.computeIfAbsent(player.getName(),
                n -> new ArrayList<>(Collections.nCopies(SLOTS, null)));
        if (slot >= list.size()) {
            padTo(list, SLOTS);
        }
        list.set(slot, null);
        saveDb();
        openMainDialog(player);
    }

    private void handleSaveSlot(Player player, int slot, String buildName) {
        if (slot < 0 || slot >= SLOTS)
            return;
        Map<String, Integer> scores = readAllScoresFor(player);
        List<BuildRecord> list = db.computeIfAbsent(player.getName(),
                n -> new ArrayList<>(Collections.nCopies(SLOTS, null)));
        if (slot >= list.size()) {
            padTo(list, SLOTS);
        }
        list.set(slot, new BuildRecord(buildName, scores));
        saveDb();
        openMainDialog(player);
    }

    // === Работа с JSON ===
    private void loadDb() {
        if (!jsonFile.exists())
            return;
        try (Reader r = new InputStreamReader(new FileInputStream(jsonFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, List<BuildRecord>>>() {
            }.getType();
            Map<String, List<BuildRecord>> loaded = gson.fromJson(r, type);
            if (loaded != null)
                db.putAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load builds.json: " + e.getMessage());
        }
    }

    private synchronized void saveDb() {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(jsonFile), StandardCharsets.UTF_8)) {
            gson.toJson(db, w);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save builds.json: " + e.getMessage());
        }
    }

    private static void padTo(List<?> list, int size) {
        while (list.size() < size)
            ((List<Object>) list).add(null);
    }

    // === Скораборды ===
    private Map<String, Integer> readAllScoresFor(Player player) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = player.getName();
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String obj : OBJECTIVES) {
            int def = endsWithDigit123(obj) ? 100_000_000 : 0;
            Integer v = getScoreValue(main, obj, entry, def);
            map.put(obj, v == null ? def : v);
        }
        return map;
    }

    private void writeAllScoresFor(Player player, Map<String, Integer> values) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        String entry = player.getName();
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            Objective o = main.getObjective(e.getKey());
            if (o == null) {
                // регистрируем dummy objective для «глобальных» значений
                try {
                    o = main.registerNewObjective(e.getKey(), Criteria.DUMMY, Component.text(e.getKey()));
                } catch (IllegalArgumentException ignored) {
                    o = main.getObjective(e.getKey());
                }
            }
            if (o != null) {
                o.getScore(entry).setScore(e.getValue());
            }
        }
    }

    static Integer getScoreValue(Scoreboard board, String objective, String entry, int defaultIfUnset) {
        Objective o = board.getObjective(objective);
        if (o == null)
            return defaultIfUnset;
        Score s = o.getScore(entry);
        if (!s.isScoreSet())
            return defaultIfUnset; // Score#isScoreSet существует в 1.21.x. :contentReference[oaicite:5]{index=5}
        return s.getScore();
    }

    private static boolean endsWithDigit123(String name) {
        return name.endsWith("1") || name.endsWith("2") || name.endsWith("3");
    }

    private Component asItemIconComponent(ItemStack it) {
        return it.displayName().hoverEvent(it.asHoverEvent());
    }

    private Component buildItemsTooltip(Player viewer, Map<String, Integer> scores) {
        List<Map.Entry<ItemStack, IconKind>> items = resolveIconItems(viewer, scores);
        List<Component> parts = new ArrayList<>(items.size());
        for (var e : items) {
            ItemStack it = e.getKey();
            NamedTextColor color = colorOf(e.getValue());
            // имя предмета + hover предмета, но с «дефолтным» цветом категории
            parts.add(asItemIconComponent(it).applyFallbackStyle(Style.style(color)));
        }
        return Component.join(JoinConfiguration.separator(Component.space()), parts);
    }

    private List<Map.Entry<ItemStack, IconKind>> resolveIconItems(Player viewer, Map<String, Integer> sc) {
        // сначала — список "что хотим показать" (ключ/барьер + тип)
        List<IconEntry> plan = new ArrayList<>();

        // --- levelup1..3: пары 2-3, 4-5, 6-7, 8-9 → idx-1, barrier если нет пары/idx<0
        for (String lvl : List.of("lbc.levelup1", "lbc.levelup2", "lbc.levelup3")) {
            int v = sc.getOrDefault(lvl, 0);
            int len = String.valueOf(v).length();
            addPairEntry(plan, "lbc:gui/page/8000/", v, len - 7, len - 6, IconKind.LBC_LEVELUP, "levelup");
            addPairEntry(plan, "lbc:gui/page/8000/", v, len - 5, len - 4, IconKind.LBC_LEVELUP, "levelup");
            addPairEntry(plan, "lbc:gui/page/8000/", v, len - 3, len - 2, IconKind.LBC_LEVELUP, "levelup");
            addPairEntry(plan, "lbc:gui/page/8000/", v, len - 1, len, IconKind.LBC_LEVELUP, "levelup");
        }

        // --- swrg: сырые kit/skill → (value-1), barrier если <0
        int kitPlainIdx = sc.getOrDefault("swrg.kit", 0) - 1;
        plan.add(new IconEntry(kitPlainIdx >= 0 ? "swrg:gui/page/3000/" + kitPlainIdx : null, IconKind.SWRG_KIT));
        //log(kitPlainIdx >= 0 ? "key swrg kit -> swrg:gui/page/3000/" + kitPlainIdx : "barrier swrg kit (idx<0)");

        int skillPlainIdx = sc.getOrDefault("swrg.skill", 0) - 1;
        plan.add(new IconEntry(skillPlainIdx >= 0 ? "swrg:gui/page/4000/" + skillPlainIdx : null, IconKind.SWRG_SKILL));
        //log(skillPlainIdx >= 0 ? "key swrg skill -> swrg:gui/page/4000/" + skillPlainIdx
        //        : "barrier swrg skill (idx<0)");

        // --- lbc.kit / lbc.skill (как было, но с явным barrier)
        int kitIdx = sc.getOrDefault("lbc.kit", sc.getOrDefault("kit", 0)) - 1;
        plan.add(new IconEntry(kitIdx >= 0 ? "lbc:gui/page/9000/" + kitIdx : null, IconKind.LBC_KIT));
        //log(kitIdx >= 0 ? "key lbc.kit -> lbc:gui/page/9000/" + kitIdx : "barrier lbc.kit (idx<0)");

        int skill = sc.getOrDefault("lbc.skill", sc.getOrDefault("skill", 0));
        if (skill < 27) {
            int idx = skill - 1;
            plan.add(new IconEntry(idx >= 0 ? "lbc:gui/page/10000/" + idx : null, IconKind.LBC_SKILL));
            //log(idx >= 0 ? "key lbc.skill<27 -> lbc:gui/page/10000/" + idx : "barrier lbc.skill<27 (idx<0)");
        } else {
            int idx = skill - 28;
            plan.add(new IconEntry(idx >= 0 ? "lbc:gui/page/10100/" + idx : null, IconKind.LBC_SKILL));
            //log(idx >= 0 ? "key lbc.skill>=27 -> lbc:gui/page/10100/" + idx : "barrier lbc.skill>=27 (idx<0)");
        }

        // --- trinket1..3
        for (String tr : List.of("lbc.trinket1", "lbc.trinket2", "lbc.trinket3")) {
            int v = sc.getOrDefault(tr, 0);
            int len = String.valueOf(v).length();
            addPairEntry(plan, "lbc:gui/page/11000/", v, len - 7, len - 6, IconKind.LBC_TRINKET, "trinket");
            addPairEntry(plan, "lbc:gui/page/11000/", v, len - 5, len - 4, IconKind.LBC_TRINKET, "trinket");
            addPairEntry(plan, "lbc:gui/page/11000/", v, len - 3, len - 2, IconKind.LBC_TRINKET, "trinket");
            addPairEntry(plan, "lbc:gui/page/11000/", v, len - 1, len, IconKind.LBC_TRINKET, "trinket");
        }

        // --- challenge.kit (.../_completed)
        int chKit = sc.getOrDefault("lbc.challenge.kit", 0) - 1;
        plan.add(new IconEntry(chKit >= 0 ? "lbc:gui/page/12000/" + chKit + "_completed" : null,
                IconKind.LBC_CHALLENGE_KIT));
        //log(chKit >= 0 ? "key challenge.kit -> lbc:gui/page/12000/" + chKit + "_completed"
        //        : "barrier challenge.kit (idx<0)");

        // теперь — резолвим ключи в ItemStack’и
        List<Map.Entry<ItemStack, IconKind>> out = new ArrayList<>(plan.size());
        for (IconEntry e : plan) {
            ItemStack stack;
            if (e.lootKey() == null) {
                stack = makeBarrier();
            } else {
                ItemStack got = tryFirstLootItem(viewer, e.lootKey());
                if (got == null || got.getType() == Material.AIR) {
                    stack = makeBarrier();
                } else {
                    stack = ensurePreviewNameVisible(got, e.lootKey(), viewer); // стало

                }
            }
            out.add(Map.entry(stack, e.kind()));
        }
        return out;
    }

    private ItemStack makeBarrier() {
        ItemStack barrier = new ItemStack(Material.BARRIER);
        var meta = barrier.getItemMeta();
        meta.displayName(Component.translatable(TL_EMPTY));
        barrier.setItemMeta(meta);
        return barrier;
    }

    private void addPairEntry(List<IconEntry> out, String prefix, int value, int from, int to, IconKind kind,
            String tag) {
        OptionalInt pair = digitsPairOpt(value, from, to);
        int idx = pair.isEmpty() ? -1 : (pair.getAsInt() - 1);
        if (idx >= 0) {
            String k = prefix + idx;
            out.add(new IconEntry(k, kind));
            //log("key " + tag + " " + from + "-" + to + " -> " + k);
        } else {
            out.add(new IconEntry(null, kind)); // явный barrier
            //log("barrier " + tag + " " + from + "-" + to + " (idx<0)");
        }
    }

    // Было digitsPair(...). Теперь — безопасная версия: OptionalInt (пусто, если
    // пары нет совсем).
    private static OptionalInt digitsPairOpt(int value, int from, int to) {
        String s = String.valueOf(Math.max(0, value));
        if (from > s.length())
            return OptionalInt.empty();
        int end = Math.min(to, s.length());
        String sub = s.substring(from - 1, end).replaceFirst("^0+(?!$)", ""); // убрать лидирующие нули
        if (sub.isEmpty())
            return OptionalInt.of(0); // «00» → 0
        try {
            return OptionalInt.of(Integer.parseInt(sub));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private ItemStack tryFirstLootItem(Player viewer, String namespaced) {
        NamespacedKey key = NamespacedKey.fromString(namespaced);
        if (key == null) {
            //log("bad key: " + namespaced);
            return null;
        }

        LootTable table = Bukkit.getLootTable(key);
        if (table == null) {
            //log("no table: " + namespaced);
            return null;
        }

        LootContext.Builder b = new LootContext.Builder(viewer.getLocation()).luck(0.0f);
        try {
            b = b.lootedEntity(viewer); // мапится на minecraft:this_entity
        } catch (Throwable ignored) {
        }

        LootContext ctx = b.build();

        Collection<ItemStack> generated;
        try {
            generated = table.populateLoot(new java.util.Random(), ctx);
        } catch (Throwable t) {
            //log("[LOOT] " + key + " error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }

        if (generated == null || generated.isEmpty())
            return null;

        ItemStack first = generated.stream()
                .filter(it -> it != null && it.getType() != Material.AIR)
                .findFirst().orElse(null);

        if (first == null)
            return null;

        // твоя диагностика (оставил)
        var meta = first.getItemMeta();
        net.kyori.adventure.key.Key modelKey = null;
        try {
            if (first.hasData(DataComponentTypes.ITEM_MODEL)) {
                modelKey = first.getData(DataComponentTypes.ITEM_MODEL);
            }
        } catch (Throwable ignored) {
        }
        org.bukkit.NamespacedKey metaModel = (meta != null && meta.hasItemModel()) ? meta.getItemModel() : null;
        var iname = (meta != null && meta.hasItemName()) ? meta.itemName()
                : (meta != null && meta.hasCustomName()) ? meta.customName() : null;
        var modelStr = modelKey != null ? modelKey.asString() : (metaModel != null ? metaModel.asString() : null);

       // log("[LOOT] got item " + first.getType()
       //         + (modelStr != null ? " item_model=" + modelStr : " (no item_model)")
       //         + (iname != null ? " item_name=" + iname : " (empty name)"));

        return first;
    }

    // === Модель данных ===
    public record BuildRecord(String name, Map<String, Integer> scores) {
    }

    private enum IconKind {
        SWRG_SKILL, LBC_SKILL, SWRG_KIT, LBC_KIT,
        LBC_LEVELUP, LBC_TRINKET, LBC_CHALLENGE_KIT
    }

    private static NamedTextColor colorOf(final IconKind k) {
        return switch (k) {
            case SWRG_SKILL -> NamedTextColor.BLUE; // синий
            case LBC_SKILL -> NamedTextColor.AQUA; // голубой
            case SWRG_KIT -> NamedTextColor.YELLOW; // жёлтый
            case LBC_KIT -> NamedTextColor.GOLD; // золотой
            case LBC_LEVELUP -> NamedTextColor.GREEN; // зелёный
            case LBC_TRINKET -> NamedTextColor.WHITE; // белый
            case LBC_CHALLENGE_KIT -> NamedTextColor.RED;// красный
        };
    }

    private static boolean componentLooksEmpty(final Component c) {
        if (c == null)
            return true;
        String plain = PlainTextComponentSerializer.plainText().serialize(c).trim();
        return plain.isEmpty();
    }

    private ItemStack ensurePreviewNameVisible(ItemStack it, String lootKey) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return it;

        Component name = meta.hasItemName() ? meta.itemName()
                : (meta.hasCustomName() ? meta.customName() : null);

        boolean needsFallback = false;
        if (name == null || componentLooksEmpty(name)) {
            needsFallback = true;
        } else if (name instanceof StorageNBTComponent) {
            // именно твой кейс: клиент не дорендерит StorageNBTComponent из storage
            needsFallback = true;
        }

        if (needsFallback) {
            meta.displayName(Component.text(lootKey, NamedTextColor.GRAY));
            it.setItemMeta(meta);
            //log("[LOOT][preview-name] fallback to loot key: " + lootKey);
        }
        return it;
    }

    private ItemStack ensurePreviewNameVisible(ItemStack it, String lootKey, Player viewer) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return it;

        // --- DISPLAY NAME ---
        Component name = meta.hasItemName() ? meta.itemName()
                : (meta.hasCustomName() ? meta.customName() : null);

        boolean needFallback = false;

        if (name instanceof StorageNBTComponent snbt) {
            Component resolved = resolveStorageComponent(snbt);
            if (resolved != null && !componentLooksEmpty(resolved)) {
                meta.displayName(resolved);
                it.setItemMeta(meta);
                //log("[LOOT][preview-name] resolved from storage: " + snbt.storage().asString() + " :: "
                //        + snbt.nbtPath());
                // также попробуем резолвнуть лор
                resolveLoreFromStorage(meta);
                it.setItemMeta(meta);
                return it;
            } else {
                needFallback = true;
            }
        } else if (name == null || componentLooksEmpty(name)) {
            needFallback = true;
        }

        // --- LORE (по возможности тоже разворачиваем storage → текст) ---
        resolveLoreFromStorage(meta);
        it.setItemMeta(meta);

        if (needFallback) {
            meta.displayName(Component.text(lootKey, NamedTextColor.GRAY));
            it.setItemMeta(meta);
           // log("[LOOT][preview-name] fallback to loot key: " + lootKey);
        }
        return it;
    }

    private @org.jetbrains.annotations.Nullable Component resolveStorageComponent(final StorageNBTComponent snbt) {
        try {
            // 1) сервер и хранилище команд
            final MinecraftServer nms = ((CraftServer) Bukkit.getServer()).getServer();
            final var cmdStorage = nms.getCommandStorage();

            // 2) id стораджа
            final String id = snbt.storage().asString(); // "swrg:lang"
            final ResourceLocation rl = ResourceLocation.parse(id);

            // 3) корневой Compound из стораджа
            final CompoundTag root = cmdStorage.get(rl);
            if (root == null)
                return null;

            // 4) парсим nbt-path и достаём значение(я)
            final String path = snbt.nbtPath(); // например "skill.a2" или "lore.skill.a1.Lore[0]"
            final NbtPathArgument.NbtPath nbtPath = NbtPathArgument.nbtPath().parse(new StringReader(path));
            final java.util.List<Tag> hits = nbtPath.get(root);
            if (hits.isEmpty())
                return null;

            // 5) берём первое совпадение как строку
            final String raw = hits.get(0).toString();

            // 6) interpret: true → пробуем распарсить JSON-компонент; иначе — plain
            if (Boolean.TRUE.equals(snbt.interpret())) {
                try {
                    return net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(raw);
                } catch (Throwable ignored) {
                    // не JSON → упадём в plain
                }
            }
            return Component.text(raw);
        } catch (Throwable t) {
            //log("[STORAGE] resolve failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private void resolveLoreFromStorage(final ItemMeta meta) {
        try {
            final java.util.List<Component> lore = meta.lore();
            if (lore == null || lore.isEmpty())
                return;

            boolean changed = false;
            final java.util.List<Component> out = new java.util.ArrayList<>(lore.size());
            for (Component line : lore) {
                if (line instanceof StorageNBTComponent snbt) {
                    final Component resolved = resolveStorageComponent(snbt);
                    if (resolved != null && !componentLooksEmpty(resolved)) {
                        out.add(resolved);
                        changed = true;
                        continue;
                    }
                }
                out.add(line); // без изменений
            }
            if (changed)
                meta.lore(out);
        } catch (Throwable t) {
            //log("[STORAGE] lore resolve failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private record IconEntry(@org.jetbrains.annotations.Nullable String lootKey, IconKind kind) {
    }

}
