package me.foesio.foDiscordBot.editor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.EditorDialogInputs;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.gui.GuiSlots;
import me.foesio.core.gui.EntryBrowserClick;
import me.foesio.core.gui.EntryBrowserHolder;
import me.foesio.core.gui.EntryBrowserMenus;
import me.foesio.core.gui.EntryBrowserRequest;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.message.FoStyle;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.core.text.PromptNormalizer;
import me.foesio.foDiscordBot.FoDiscordBot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public final class ConfigEditorService implements Listener {

    private static final Pattern HEX_COLOR = Pattern.compile("^#?[0-9a-fA-F]{6}$");
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int RANK_SYNC_SLOT = 46;
    private static final int ADD_RANK_SLOT = 47;
    private static final int ADD_PROFILE_FIELD_SLOT = 47;
    private static final int ADD_COMMAND_SLOT = 47;
    private static final int LEADERBOARD_EMBED_COLOR_SLOT = 46;
    private static final int ADD_BOARD_SLOT = 47;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int[] PAGED_ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int ENTRIES_PER_PAGE = PAGED_ENTRY_SLOTS.length;
    private static final String CONTEXT_SEPARATOR = "\t";

    private final FoDiscordBot plugin;
    private final Map<UUID, DeleteRequest> pendingDeletes = new ConcurrentHashMap<>();

    public ConfigEditorService(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    public boolean openEditor(Player player) {
        if (!player.hasPermission(FoDiscordBot.ADMIN_PERMISSION)) {
            plugin.messages().sendConfigured(player, "ingame.no-permission");
            return false;
        }
        if (!plugin.syncConfigFromDisk()) {
            plugin.messages().sendConfigured(player, "ingame.reload.error");
            return false;
        }

        openMainMenu(player);
        plugin.messages().sendConfigured(player, "ingame.editor.open");
        return true;
    }

    // Handle editor clicks even if another inventory plugin cancelled the event first.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof EntryBrowserHolder entryBrowserHolder) {
            event.setCancelled(true);
            if (!player.hasPermission(FoDiscordBot.ADMIN_PERMISSION)) {
                player.closeInventory();
                plugin.messages().sendConfigured(player, "ingame.no-permission");
                return;
            }
            handleEntryBrowserClick(player, event.getRawSlot(), event.getClick(), entryBrowserHolder);
            return;
        }
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) {
            return;
        }
        if (!holder.ownerUuid().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!player.hasPermission(FoDiscordBot.ADMIN_PERMISSION)) {
            event.setCancelled(true);
            player.closeInventory();
            plugin.messages().sendConfigured(player, "ingame.no-permission");
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) {
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() == null) {
            return;
        }

        if (holder.view() == EditorView.CONFIRM_DELETE) {
            handleConfirmDeleteClick(player, rawSlot);
            return;
        }
        if (handleCommonNavigation(player, rawSlot, holder)) {
            return;
        }

        switch (holder.view()) {
            case MAIN -> handleMainClick(player, rawSlot);
            case DISCORD -> handleDiscordClick(player, rawSlot);
            case CHAT_BRIDGE -> handleChatBridgeClick(player, rawSlot);
            case LINKING -> handleLinkingClick(player, rawSlot);
            case BOOSTER -> handleBoosterClick(player, rawSlot);
            case NETWORK -> handleNetworkClick(player, rawSlot);
            case RANK_SYNC -> handleRankSyncClick(player, rawSlot, event.getClick(), holder.context());
            case PROFILE_FIELDS -> handleProfileFieldsClick(player, rawSlot, event.getClick(), holder.context());
            case LEADERBOARDS -> handleLeaderboardsClick(player, rawSlot, event.getClick(), holder.context());
            case BOARD -> handleBoardClick(player, rawSlot, holder.context());
            case COMMAND_LIST -> handleCommandListClick(player, rawSlot, event.getClick(), holder.context());
            case CONFIRM_DELETE -> {
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player
                && plugin.getCore() != null
                && plugin.getCore().inventoryCloseSuppressor().consumeSuppressedClose(player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof EditorHolder holder
                && holder.view() == EditorView.CONFIRM_DELETE
                && event.getPlayer() instanceof Player player
                && holder.ownerUuid().equals(player.getUniqueId())) {
            pendingDeletes.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingDeletes.remove(event.getPlayer().getUniqueId());
        if (plugin.getCore() != null) {
            plugin.getCore().inventoryCloseSuppressor().clear(event.getPlayer());
        }
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 10 -> openDiscordPage(player);
            case 12 -> openChatBridgePage(player);
            case 13 -> openLinkingPage(player);
            case 15 -> openBoosterPage(player);
            case 16 -> openNetworkPage(player);
            case 11 -> openRankSyncPage(player);
            case 14 -> openLeaderboardsPage(player);
            default -> {
            }
        }
    }

    private void handleDiscordClick(Player player, int slot) {
        switch (slot) {
            case 10 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.DISCORD, "",
                    "discord.token", "Discord bot token", "token text, or clear");
            case 11 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.DISCORD, "",
                    "discord.command-guild-id", "Discord command guild ID", "Discord server ID, or clear");
            case 12 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.DISCORD, "",
                    "discord.invite-url", "Discord invite URL", "https://discord.gg/example");
            case 13 -> toggleBoolean(player, "server-ip.enabled", EditorView.DISCORD, "");
            case 14 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.DISCORD, "",
                    "server-ip.ip", "server IP", "play.example.net or example.net:25565");
            case 15 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.DISCORD, "",
                    "profile.footer", "profile footer", "text, none, or clear");
            case 16 -> beginTextInput(player, PendingInputType.SET_HEX, -1, EditorView.DISCORD, "",
                    "profile.embed-color", "profile embed color", "#RRGGBB");
            case 19 -> openProfileFieldsPage(player);
            case 20 -> toggleBoolean(player, "advancement.enabled", EditorView.DISCORD, "");
            default -> {
            }
        }
    }

    private void handleChatBridgeClick(Player player, int slot) {
        switch (slot) {
            case 10 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.CHAT_BRIDGE, "",
                    "chat-bridge.channel-id", "chat bridge channel ID", "Discord channel ID, or clear");
            case 11 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.CHAT_BRIDGE, "",
                    "chat-bridge.webhook-name", "webhook name", "name shown by Discord webhook");
            case 12 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.CHAT_BRIDGE, "",
                    "chat-bridge.relay-name-format", "relay name format", "{player_name} and {gamemode} allowed");
            case 13 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.CHAT_BRIDGE, "",
                    "chat-bridge.avatar-url-template", "avatar URL template", "URL with {player_uuid}, {player_name}, {skin_texture_url}, or {skin_texture_hash}");
            default -> {
            }
        }
    }

    private void handleLinkingClick(Player player, int slot) {
        switch (slot) {
            case 10 -> beginIntInput(player, EditorView.LINKING, "linking.code-length", "link code length", "4-12", 4, 12);
            case 11 -> beginIntInput(player, EditorView.LINKING, "linking.code-expiry-seconds", "link code expiry", "seconds, 60-86400", 60, 86400);
            case 12 -> beginIntInput(player, EditorView.LINKING, "linking.ingame-command-cooldown-seconds", "in-game cooldown", "seconds, 1-3600", 1, 3600);
            case 13 -> beginIntInput(player, EditorView.LINKING, "linking.discord-command-cooldown-seconds", "Discord cooldown", "seconds, 1-300", 1, 300);
            case 14 -> beginIntInput(player, EditorView.LINKING, "linking.cleanup-interval-minutes", "cleanup interval", "minutes, 1-120", 1, 120);
            case 15 -> toggleBoolean(player, "linking.remove-link-message-after-success", EditorView.LINKING, "");
            case 16 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.LINKING, "",
                    "linking.linked-role-id", "linked role ID", "Discord role ID, none, or clear");
            case 19 -> openCommandList(player, "linking.always-reward-commands", EditorView.LINKING, "");
            case 20 -> openCommandList(player, "linking.one-time-reward-commands", EditorView.LINKING, "");
            case 21 -> openCommandList(player, "linking.unlink-commands", EditorView.LINKING, "");
            default -> {
            }
        }
    }

    private void handleBoosterClick(Player player, int slot) {
        switch (slot) {
            case 10 -> toggleBoolean(player, "booster.enabled", EditorView.BOOSTER, "");
            case 11 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.BOOSTER, "",
                    "booster.role-id", "booster role ID", "Discord role ID, or clear");
            case 12 -> openCommandList(player, "booster.always-reward-commands", EditorView.BOOSTER, "");
            case 13 -> openCommandList(player, "booster.one-time-reward-commands", EditorView.BOOSTER, "");
            case 14 -> openCommandList(player, "booster.removal-commands", EditorView.BOOSTER, "");
            default -> {
            }
        }
    }

    private void handleNetworkClick(Player player, int slot) {
        switch (slot) {
            case 10 -> toggleBoolean(player, "network.enabled", EditorView.NETWORK, "");
            case 11 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.NETWORK, "",
                    "network.gamemode-id", "network gamemode ID", "lowercase id, example hub");
            case 12 -> toggleBoolean(player, "network.primary-discord-node", EditorView.NETWORK, "");
            case 13 -> beginIntInput(player, EditorView.NETWORK, "network.sync.interval-seconds", "network sync interval", "seconds, 5-3600", 5, 3600);
            case 14 -> beginIntInput(player, EditorView.NETWORK, "network.sync.profile-cache-seconds", "profile cache", "seconds, 0-3600", 0, 3600);
            case 15 -> toggleBoolean(player, "network.mysql.use-ssl", EditorView.NETWORK, "");
            case 16 -> beginIntInput(player, EditorView.NETWORK, "network.mysql.connection-timeout-seconds", "MySQL connection timeout", "seconds, 5-120", 5, 120);
            case 19 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.NETWORK, "",
                    "network.mysql.host", "MySQL host", "hostname or IP");
            case 20 -> beginIntInput(player, EditorView.NETWORK, "network.mysql.port", "MySQL port", "1-65535", 1, 65535);
            case 21 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.NETWORK, "",
                    "network.mysql.database", "MySQL database", "database name");
            case 22 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.NETWORK, "",
                    "network.mysql.username", "MySQL username", "username");
            case 23 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.NETWORK, "",
                    "network.mysql.password", "MySQL password", "password text, or clear");
            case 24 -> beginIntInput(player, EditorView.NETWORK, "network.mysql.pool-size", "MySQL pool size", "connections, 2-50", 2, 50);
            default -> {
            }
        }
    }

    private void handleRankSyncClick(Player player, int slot, ClickType clickType, String context) {
        List<String> keys = rankKeys();
        int page = currentPage(context, maxPage(keys.size()));
        int maxPage = maxPage(keys.size());
        if (slot == PREVIOUS_PAGE_SLOT && page > 0) {
            openRankSyncPage(player, page - 1);
            return;
        }
        if (slot == NEXT_PAGE_SLOT && page < maxPage) {
            openRankSyncPage(player, page + 1);
            return;
        }
        if (slot == RANK_SYNC_SLOT) {
            toggleBoolean(player, "rank-sync.enabled", EditorView.RANK_SYNC, Integer.toString(page));
            return;
        }
        if (slot == ADD_RANK_SLOT) {
            beginTextInput(player, PendingInputType.ADD_RANK, -1, EditorView.RANK_SYNC, Integer.toString(page),
                    "", "rank mapping", "key | permission | role-id");
            return;
        }

        int entryOffset = pagedEntryOffset(slot);
        int index = page * ENTRIES_PER_PAGE + entryOffset;
        if (entryOffset < 0 || index < 0 || index >= keys.size()) {
            return;
        }

        String key = keys.get(index);
        if (clickType.isRightClick()) {
            openConfirmDelete(player, DeleteRequest.configPath(EditorView.RANK_SYNC, Integer.toString(page),
                    "rank-sync.ranks." + key, "rank " + key));
            return;
        }

        beginTextInput(player, PendingInputType.EDIT_RANK, -1, EditorView.RANK_SYNC, Integer.toString(page),
                "", "rank mapping " + key, "key | permission | role-id", key);
    }

    private void handleProfileFieldsClick(Player player, int slot, ClickType clickType, String context) {
        List<Map<?, ?>> fields = plugin.getConfig().getMapList("profile.fields");
        int page = currentPage(context, maxPage(fields.size()));
        int maxPage = maxPage(fields.size());
        if (slot == PREVIOUS_PAGE_SLOT && page > 0) {
            openProfileFieldsPage(player, page - 1);
            return;
        }
        if (slot == NEXT_PAGE_SLOT && page < maxPage) {
            openProfileFieldsPage(player, page + 1);
            return;
        }
        if (slot == ADD_PROFILE_FIELD_SLOT) {
            beginTextInput(player, PendingInputType.ADD_PROFILE_FIELD, -1, EditorView.PROFILE_FIELDS, Integer.toString(page),
                    "", "profile field", "name | value | inline true/false | same-line true/false");
            return;
        }

        int entryOffset = pagedEntryOffset(slot);
        int index = page * ENTRIES_PER_PAGE + entryOffset;
        if (entryOffset < 0 || index < 0 || index >= fields.size()) {
            return;
        }

        if (clickType.isRightClick()) {
            openConfirmDelete(player, DeleteRequest.listIndex(EditorView.PROFILE_FIELDS, Integer.toString(page), "profile.fields", index,
                    "profile field #" + (index + 1)));
            return;
        }

        beginTextInput(player, PendingInputType.EDIT_PROFILE_FIELD, index, EditorView.PROFILE_FIELDS, Integer.toString(page),
                "", "profile field #" + (index + 1), "name | value | inline true/false | same-line true/false");
    }

    private void handleLeaderboardsClick(Player player, int slot, ClickType clickType, String context) {
        List<String> aliases = leaderboardAliases();
        int page = currentPage(context, maxPage(aliases.size()));
        int maxPage = maxPage(aliases.size());
        if (slot == PREVIOUS_PAGE_SLOT && page > 0) {
            openLeaderboardsPage(player, page - 1);
            return;
        }
        if (slot == NEXT_PAGE_SLOT && page < maxPage) {
            openLeaderboardsPage(player, page + 1);
            return;
        }
        if (slot == LEADERBOARD_EMBED_COLOR_SLOT) {
            beginTextInput(player, PendingInputType.SET_HEX, -1, EditorView.LEADERBOARDS, Integer.toString(page),
                    "leaderboards.embed-color", "leaderboard embed color", "#RRGGBB");
            return;
        }
        if (slot == ADD_BOARD_SLOT) {
            beginTextInput(player, PendingInputType.ADD_BOARD, -1, EditorView.LEADERBOARDS, Integer.toString(page),
                    "", "leaderboard board", "alias | title");
            return;
        }

        int entryOffset = pagedEntryOffset(slot);
        int index = page * ENTRIES_PER_PAGE + entryOffset;
        if (entryOffset < 0 || index < 0 || index >= aliases.size()) {
            return;
        }

        String alias = aliases.get(index);
        if (clickType.isRightClick()) {
            openConfirmDelete(player, DeleteRequest.configPath(EditorView.LEADERBOARDS, Integer.toString(page),
                    "leaderboards.boards." + alias, "board " + alias));
            return;
        }

        openBoardPage(player, alias);
    }

    private void handleBoardClick(Player player, int slot, String alias) {
        if (alias == null || alias.isBlank() || !plugin.getConfig().contains("leaderboards.boards." + alias, true)) {
            plugin.messages().sendConfigured(player, "ingame.editor.invalid-input");
            openLeaderboardsPage(player);
            return;
        }

        switch (slot) {
            case 10 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.BOARD, alias,
                    "leaderboards.boards." + alias + ".title", "board title", "display title");
            case 11 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.BOARD, alias,
                    "leaderboards.boards." + alias + ".footer", "board footer", "text, none, or clear");
            case 12 -> beginTextInput(player, PendingInputType.SET_TEXT, -1, EditorView.BOARD, alias,
                    "leaderboards.boards." + alias + ".empty-text", "empty text", "message shown when no entries exist");
            case 13 -> openCommandList(player, "leaderboards.boards." + alias + ".lines", EditorView.BOARD, alias);
            case 16 -> openConfirmDelete(player, DeleteRequest.configPath(EditorView.BOARD, alias,
                    "leaderboards.boards." + alias, "board " + alias, EditorView.LEADERBOARDS, ""));
            default -> {
            }
        }
    }

    private void handleCommandListClick(Player player, int slot, ClickType clickType, String encodedContext) {
        CommandListContext context = parseCommandListContext(encodedContext);
        if (context == null) {
            openMainMenu(player);
            return;
        }

        int page = currentPage(Integer.toString(context.page()), maxPage(plugin.getConfig().getStringList(context.path()).size()));
        int maxPage = maxPage(plugin.getConfig().getStringList(context.path()).size());
        if (slot == PREVIOUS_PAGE_SLOT && page > 0) {
            openCommandList(player, context.path(), context.returnView(), context.returnContext(), page - 1);
            return;
        }
        if (slot == NEXT_PAGE_SLOT && page < maxPage) {
            openCommandList(player, context.path(), context.returnView(), context.returnContext(), page + 1);
            return;
        }
        if (slot == ADD_COMMAND_SLOT) {
            beginTextInput(player, PendingInputType.ADD_COMMAND, -1, EditorView.COMMAND_LIST, encodedContext,
                    context.path(), commandListEntryName(context.path()), "plain text line");
            return;
        }

        List<String> values = plugin.getConfig().getStringList(context.path());
        int entryOffset = pagedEntryOffset(slot);
        int index = page * ENTRIES_PER_PAGE + entryOffset;
        if (entryOffset < 0 || index < 0 || index >= values.size()) {
            return;
        }

        if (clickType.isRightClick()) {
            openConfirmDelete(player, DeleteRequest.listIndex(EditorView.COMMAND_LIST, encodedContext, context.path(), index,
                    commandListEntryName(context.path()) + " #" + (index + 1)));
            return;
        }

        beginTextInput(player, PendingInputType.EDIT_COMMAND, index, EditorView.COMMAND_LIST, encodedContext,
                context.path(), commandListEntryName(context.path()) + " #" + (index + 1), "plain text line");
    }

    private void handleConfirmDeleteClick(Player player, int slot) {
        if (slot == 11) {
            DeleteRequest request = pendingDeletes.remove(player.getUniqueId());
            player.closeInventory();
            if (request != null) {
                reopen(player, request.returnView(), request.returnContext());
            }
            return;
        }

        if (slot != 15) {
            return;
        }

        DeleteRequest request = pendingDeletes.remove(player.getUniqueId());
        player.closeInventory();
        if (request == null) {
            return;
        }

        updateConfig(player, request.successView(), request.successContext(), config -> {
            if (request.kind() == DeleteKind.CONFIG_PATH) {
                config.set(request.path(), null);
                return;
            }
            List<?> rawList = config.getList(request.path(), List.of());
            List<Object> updated = new ArrayList<>(rawList);
            if (request.index() >= 0 && request.index() < updated.size()) {
                updated.remove(request.index());
            }
            config.set(request.path(), updated);
        }, "editor.deleted", Map.of("target", request.label()));
    }

    private void handlePendingInput(Player player, PendingInput pendingInput, String message) {
        if (PromptNormalizer.isCancel(message)) {
            cancelPendingInput(player, pendingInput);
            return;
        }

        if (message.isBlank()) {
            plugin.messages().sendConfigured(player, "ingame.editor.input-empty");
            reopen(player, pendingInput.returnView(), pendingInput.returnContext());
            return;
        }

        switch (pendingInput.type()) {
            case SET_TEXT -> updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config ->
                    config.set(pendingInput.path(), normalizeClearableText(message)), "editor.saved");
            case SET_INT -> handleIntInput(player, pendingInput, message);
            case SET_HEX -> handleColorInput(player, pendingInput, message);
            case ADD_COMMAND -> updateCommand(player, pendingInput, message, false);
            case EDIT_COMMAND -> updateCommand(player, pendingInput, message, true);
            case ADD_RANK -> updateRank(player, pendingInput, message, false);
            case EDIT_RANK -> updateRank(player, pendingInput, message, true);
            case ADD_PROFILE_FIELD -> updateProfileField(player, pendingInput, message, false);
            case EDIT_PROFILE_FIELD -> updateProfileField(player, pendingInput, message, true);
            case ADD_BOARD -> addLeaderboardBoard(player, pendingInput, message);
        }
    }

    private void handleIntInput(Player player, PendingInput pendingInput, String message) {
        IntRange range = parseRange(pendingInput.context());
        int value;
        try {
            value = LargeNumberParser.parse(message).orElseThrow().intValueExact();
        } catch (RuntimeException exception) {
            sendInvalidAndReopen(player, pendingInput);
            return;
        }
        if (value < range.min() || value > range.max()) {
            sendInvalidAndReopen(player, pendingInput);
            return;
        }

        int finalValue = value;
        updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config ->
                config.set(pendingInput.path(), finalValue), "editor.saved");
    }

    private void handleColorInput(Player player, PendingInput pendingInput, String message) {
        if (!HEX_COLOR.matcher(message).matches()) {
            plugin.messages().sendConfigured(player, "ingame.editor.invalid-color");
            reopen(player, pendingInput.returnView(), pendingInput.returnContext());
            return;
        }

        String normalized = message.startsWith("#") ? message : "#" + message;
        updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config ->
                config.set(pendingInput.path(), normalized), "editor.saved");
    }

    private void updateCommand(Player player, PendingInput pendingInput, String message, boolean edit) {
        updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config -> {
            List<String> values = new ArrayList<>(config.getStringList(pendingInput.path()));
            if (edit) {
                if (pendingInput.index() >= 0 && pendingInput.index() < values.size()) {
                    values.set(pendingInput.index(), message);
                }
            } else {
                values.add(message);
            }
            config.set(pendingInput.path(), values);
        }, "editor.saved");
    }

    private void updateRank(Player player, PendingInput pendingInput, String message, boolean edit) {
        RankInput input = parseRankInput(message);
        if (input == null) {
            sendInvalidAndReopen(player, pendingInput);
            return;
        }

        updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config -> {
            if (edit && pendingInput.context() != null && !pendingInput.context().isBlank()
                    && !pendingInput.context().equals(input.key())) {
                config.set("rank-sync.ranks." + pendingInput.context(), null);
            }
            config.set("rank-sync.ranks." + input.key() + ".permission", input.permission());
            config.set("rank-sync.ranks." + input.key() + ".role-id", input.roleId());
        }, "editor.saved");
    }

    private void updateProfileField(Player player, PendingInput pendingInput, String message, boolean edit) {
        ProfileFieldInput input = parseProfileFieldInput(message);
        if (input == null) {
            sendInvalidAndReopen(player, pendingInput);
            return;
        }

        updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config -> {
            List<Map<?, ?>> rawFields = config.getMapList("profile.fields");
            List<Map<String, Object>> fields = new ArrayList<>();
            for (Map<?, ?> rawField : rawFields) {
                Map<String, Object> copied = new LinkedHashMap<>();
                copied.put("name", String.valueOf(mapValue(rawField, "name", "Field")));
                copied.put("value", String.valueOf(mapValue(rawField, "value", "N/A")));
                copied.put("inline", Boolean.parseBoolean(String.valueOf(mapValue(rawField, "inline", false))));
                copied.put("same-line", Boolean.parseBoolean(String.valueOf(mapValue(rawField, "same-line", false))));
                fields.add(copied);
            }

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", input.name());
            field.put("value", input.value());
            field.put("inline", input.inline());
            field.put("same-line", input.sameLine());

            if (edit && pendingInput.index() >= 0 && pendingInput.index() < fields.size()) {
                fields.set(pendingInput.index(), field);
            } else {
                fields.add(field);
            }
            config.set("profile.fields", fields);
        }, "editor.saved");
    }

    private void addLeaderboardBoard(Player player, PendingInput pendingInput, String message) {
        String[] parts = split(message);
        if (parts.length < 2) {
            sendInvalidAndReopen(player, pendingInput);
            return;
        }

        String alias = normalizeKey(parts[0]);
        String title = parts[1].trim();
        if (alias.isBlank() || title.isBlank() || plugin.getConfig().contains("leaderboards.boards." + alias, true)) {
            sendInvalidAndReopen(player, pendingInput);
            return;
        }

        updateConfig(player, pendingInput.returnView(), pendingInput.returnContext(), config -> {
            String basePath = "leaderboards.boards." + alias;
            config.set(basePath + ".title", title);
            config.set(basePath + ".footer", "none");
            config.set(basePath + ".lines", List.of("`#1` **%player_name%** - `%value%`"));
            config.set(basePath + ".empty-text", "No entries found.");
        }, "editor.saved");
    }

    private void sendInvalidAndReopen(Player player, PendingInput pendingInput) {
        plugin.messages().sendConfigured(player, "ingame.editor.invalid-input");
        reopen(player, pendingInput.returnView(), pendingInput.returnContext());
    }

    private void beginIntInput(Player player, EditorView returnView, String path, String fieldName, String format, int min, int max) {
        beginTextInput(player, PendingInputType.SET_INT, -1, returnView, range(min, max), path, fieldName, format);
    }

    private void beginTextInput(Player player, PendingInputType type, int index, EditorView returnView, String returnContext,
                                String path, String fieldName, String format) {
        beginTextInput(player, type, index, returnView, returnContext, path, fieldName, format, "");
    }

    private void beginTextInput(Player player, PendingInputType type, int index, EditorView returnView, String returnContext,
                                String path, String fieldName, String format, String inputContext) {
        PendingInput pendingInput = new PendingInput(type, index, returnView, returnContext, path, inputContext);
        if (plugin.getCore() == null) {
            player.closeInventory();
            plugin.messages().sendConfigured(player, "ingame.editor.save-error");
            return;
        }

        TextDialogRequest request = textDialogRequest(pendingInput, fieldName, format);
        EditorDialogInputs.openTextFromInventory(
                plugin,
                plugin.getCore().inventoryCloseSuppressor(),
                plugin.getCore().dialogService(),
                player,
                request,
                value -> handlePendingInput(player, pendingInput, value == null ? "" : value.trim()),
                () -> cancelPendingInput(player, pendingInput)
        );
    }

    private TextDialogRequest textDialogRequest(PendingInput pendingInput, String fieldName, String format) {
        String currentValue = currentInputValue(pendingInput);
        String safeFieldName = protectLiteralFormatting(fieldName);
        String safeCurrentValue = protectLiteralFormatting(currentValue);
        String safeFormat = protectLiteralFormatting(format);
        List<String> body = List.of(
                FoStyle.THEME + safeFieldName,
                FoStyle.WHITE + "Current value: " + FoStyle.THEME + safeCurrentValue,
                FoStyle.WHITE + "Expected input: " + safeFormat
        );

        return switch (pendingInput.type()) {
            case SET_INT -> TextDialogRequest.number(body, currentValue, safeFormat);
            case ADD_COMMAND, EDIT_COMMAND -> TextDialogRequest.command(body, currentValue, safeFormat);
            case SET_HEX -> new TextDialogRequest(
                    FoStyle.THEME + "Text Input",
                    body,
                    FoStyle.WHITE + "Text",
                    currentValue,
                    safeFormat,
                    DialogButton.save(),
                    DialogButton.cancel(),
                    320,
                    300,
                    16,
                    true,
                    true,
                    false
            );
            default -> TextDialogRequest.text(body, currentValue, safeFormat);
        };
    }

    private void cancelPendingInput(Player player, PendingInput pendingInput) {
        plugin.messages().sendConfigured(player, "ingame.editor.input-cancelled");
        reopen(player, pendingInput.returnView(), pendingInput.returnContext());
    }

    private String currentInputValue(PendingInput pendingInput) {
        if (pendingInput.path() != null && isSensitivePath(pendingInput.path())) {
            return "";
        }

        return switch (pendingInput.type()) {
            case SET_TEXT, SET_HEX -> currentConfigValue(pendingInput.path());
            case SET_INT -> plugin.getConfig().contains(pendingInput.path(), true)
                    ? String.valueOf(plugin.getConfig().getInt(pendingInput.path()))
                    : "";
            case EDIT_COMMAND -> currentListValue(pendingInput.path(), pendingInput.index());
            case EDIT_RANK -> currentRankValue(pendingInput.context());
            case EDIT_PROFILE_FIELD -> currentProfileFieldValue(pendingInput.index());
            case ADD_COMMAND, ADD_RANK, ADD_PROFILE_FIELD, ADD_BOARD -> "";
        };
    }

    private String currentConfigValue(String path) {
        if (path == null || path.isBlank() || !plugin.getConfig().contains(path, true)) {
            return "";
        }
        Object value = plugin.getConfig().get(path);
        return value == null ? "" : String.valueOf(value);
    }

    private String currentListValue(String path, int index) {
        List<String> values = plugin.getConfig().getStringList(path);
        if (index < 0 || index >= values.size()) {
            return "";
        }
        return values.get(index);
    }

    private String currentRankValue(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String path = "rank-sync.ranks." + key;
        String permission = plugin.getConfig().getString(path + ".permission", "");
        String roleId = plugin.getConfig().getString(path + ".role-id", "");
        return key + " | " + permission + " | " + roleId;
    }

    private String currentProfileFieldValue(int index) {
        List<Map<?, ?>> fields = plugin.getConfig().getMapList("profile.fields");
        if (index < 0 || index >= fields.size()) {
            return "";
        }
        Map<?, ?> field = fields.get(index);
        return mapValue(field, "name", "Field")
                + " | " + mapValue(field, "value", "N/A")
                + " | " + mapValue(field, "inline", false)
                + " | " + mapValue(field, "same-line", false);
    }

    private boolean isSensitivePath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("token") || normalized.contains("password");
    }

    private void toggleBoolean(Player player, String path, EditorView returnView, String returnContext) {
        updateConfig(player, returnView, returnContext, config -> config.set(path, !config.getBoolean(path, false)), "editor.saved");
    }

    private void updateConfig(Player player, EditorView returnView, String returnContext, Consumer<FileConfiguration> mutator, String messagePath) {
        updateConfig(player, returnView, returnContext, mutator, messagePath, Map.of());
    }

    private void updateConfig(Player player, EditorView returnView, String returnContext, Consumer<FileConfiguration> mutator,
                              String messagePath, Map<String, String> placeholders) {
        boolean success = false;
        try {
            mutator.accept(plugin.getConfig());
            success = plugin.saveAndApplyConfig();
        } catch (RuntimeException exception) {
            plugin.logWarning("Failed to apply editor change: " + exception.getMessage());
        }

        if (!success) {
            plugin.reloadConfig();
            plugin.messages().sendConfigured(player, "ingame.editor.save-error");
        } else {
            String path = "ingame." + messagePath;
            plugin.messages().send(player, path, FoMessageService.missingMessageFallback(path), placeholders);
        }
        reopen(player, returnView, returnContext);
    }

    private void reopen(Player player, EditorView view, String context) {
        if (!player.isOnline()) {
            return;
        }
        switch (view) {
            case MAIN -> openMainMenu(player);
            case DISCORD -> openDiscordPage(player);
            case CHAT_BRIDGE -> openChatBridgePage(player);
            case LINKING -> openLinkingPage(player);
            case BOOSTER -> openBoosterPage(player);
            case NETWORK -> openNetworkPage(player);
            case RANK_SYNC -> openRankSyncPage(player, currentPage(context, maxPage(rankKeys().size())));
            case PROFILE_FIELDS -> openProfileFieldsPage(player, currentPage(context, maxPage(plugin.getConfig().getMapList("profile.fields").size())));
            case LEADERBOARDS -> openLeaderboardsPage(player, currentPage(context, maxPage(leaderboardAliases().size())));
            case BOARD -> openBoardPage(player, context);
            case COMMAND_LIST -> reopenCommandList(player, context);
            case CONFIRM_DELETE -> openMainMenu(player);
        }
    }

    private void openMainMenu(Player player) {
        Inventory inventory = createInventory(player, EditorView.MAIN, "", "Editor", 27);
        inventory.setItem(10, pageItem(Material.COMMAND_BLOCK, "Discord", "Bot token, guild, invite."));
        inventory.setItem(11, pageItem(Material.NAME_TAG, "Rank Sync", "Minecraft permission to Discord role."));
        inventory.setItem(12, pageItem(Material.NOTE_BLOCK, "Chat Bridge", "Channel, webhook, relay names."));
        inventory.setItem(13, pageItem(Material.IRON_BARS, "Linking", "Codes, cooldowns, linked role."));
        inventory.setItem(14, pageItem(Material.OAK_SIGN, "Leaderboards", "Boards and embed settings."));
        inventory.setItem(15, pageItem(Material.NETHER_STAR, "Booster", "Booster role and reward commands."));
        inventory.setItem(16, pageItem(Material.MAP, "Network", "Multi-server MySQL settings."));
        addFooter(inventory, false);
        player.openInventory(inventory);
    }

    private void openDiscordPage(Player player) {
        Inventory inventory = createInventory(player, EditorView.DISCORD, "", "Discord", 36);
        inventory.setItem(10, valueItem(Material.REDSTONE, "Bot Token", masked(plugin.getConfig().getString("discord.token", "")),
                "Click to type token or clear."));
        inventory.setItem(11, valueItem(Material.COMPASS, "Command Guild ID",
                blankAsNone(plugin.getConfig().getString("discord.command-guild-id", "")), "Click to type guild ID."));
        inventory.setItem(12, valueItem(Material.OAK_SIGN, "Invite URL",
                plugin.getConfig().getString("discord.invite-url", ""), "Click to type invite URL."));
        inventory.setItem(13, EditorItemFactory.toggle("Discord /ip Command", plugin.getConfig().getBoolean("server-ip.enabled", false)));
        inventory.setItem(14, valueItem(Material.ENDER_PEARL, "Server IP",
                plugin.getConfig().getString("server-ip.ip", ""), "Click to type server address."));
        inventory.setItem(15, valueItem(Material.BOOK, "Footer", blankAsNone(plugin.getConfig().getString("profile.footer", "")), "Click to type footer."));
        inventory.setItem(16, valueItem(Material.FIREWORK_STAR, "Embed Color", displayHex(plugin.getConfig().getString("profile.embed-color", FoStyle.THEME)), "Click to type hex color."));
        inventory.setItem(19, listPageItem(Material.WRITABLE_BOOK, "Profile Fields", "profile.fields"));
        inventory.setItem(20, EditorItemFactory.toggle("FoAdvancements Lookup", plugin.getConfig().getBoolean("advancement.enabled", false)));
        addFooter(inventory, true);
        player.openInventory(inventory);
    }

    private void openChatBridgePage(Player player) {
        Inventory inventory = createInventory(player, EditorView.CHAT_BRIDGE, "", "Chat Bridge", 27);
        inventory.setItem(10, valueItem(Material.NOTE_BLOCK, "Channel ID",
                blankAsNone(plugin.getConfig().getString("chat-bridge.channel-id", "")), "Click to type channel ID."));
        inventory.setItem(11, valueItem(Material.WRITABLE_BOOK, "Webhook Name",
                plugin.getConfig().getString("chat-bridge.webhook-name", ""), "Click to type webhook name."));
        inventory.setItem(12, valueItem(Material.NAME_TAG, "Relay Name Format",
                plugin.getConfig().getString("chat-bridge.relay-name-format", ""), "Click to type relay name format."));
        inventory.setItem(13, valueItem(Material.PLAYER_HEAD, "Avatar URL Template",
                plugin.getConfig().getString("chat-bridge.avatar-url-template", ""), "Click to type avatar URL template."));
        addFooter(inventory, true);
        player.openInventory(inventory);
    }

    private void openLinkingPage(Player player) {
        Inventory inventory = createInventory(player, EditorView.LINKING, "", "Linking", 36);
        inventory.setItem(10, valueItem(Material.NAME_TAG, "Code Length", plugin.getConfig().getInt("linking.code-length"), "Click to type exact value."));
        inventory.setItem(11, valueItem(Material.CLOCK, "Code Expiry", plugin.getConfig().getInt("linking.code-expiry-seconds") + "s", "Click to type seconds."));
        inventory.setItem(12, valueItem(Material.REPEATER, "In-Game Cooldown", plugin.getConfig().getInt("linking.ingame-command-cooldown-seconds") + "s", "Click to type seconds."));
        inventory.setItem(13, valueItem(Material.COMPARATOR, "Discord Cooldown", plugin.getConfig().getInt("linking.discord-command-cooldown-seconds") + "s", "Click to type seconds."));
        inventory.setItem(14, valueItem(Material.HOPPER, "Cleanup Interval", plugin.getConfig().getInt("linking.cleanup-interval-minutes") + "m", "Click to type minutes."));
        inventory.setItem(15, EditorItemFactory.toggle("Remove Link Message", plugin.getConfig().getBoolean("linking.remove-link-message-after-success", false)));
        inventory.setItem(16, valueItem(Material.PAPER, "Linked Role ID",
                blankAsNone(plugin.getConfig().getString("linking.linked-role-id", "")), "Click to type role ID, none, or clear."));
        inventory.setItem(19, listPageItem(Material.DIAMOND, "Always Link Commands", "linking.always-reward-commands"));
        inventory.setItem(20, listPageItem(Material.EMERALD, "First-Time Link Commands", "linking.one-time-reward-commands"));
        inventory.setItem(21, listPageItem(Material.REDSTONE, "Unlink Commands", "linking.unlink-commands"));
        addFooter(inventory, true);
        player.openInventory(inventory);
    }

    private void openBoosterPage(Player player) {
        Inventory inventory = createInventory(player, EditorView.BOOSTER, "", "Booster", 27);
        inventory.setItem(10, EditorItemFactory.toggle("Booster Rewards", plugin.getConfig().getBoolean("booster.enabled", false)));
        inventory.setItem(11, valueItem(Material.NETHER_STAR, "Booster Role ID",
                blankAsNone(plugin.getConfig().getString("booster.role-id", "")), "Click to type role ID."));
        inventory.setItem(12, listPageItem(Material.DIAMOND, "Always Reward Commands", "booster.always-reward-commands"));
        inventory.setItem(13, listPageItem(Material.EMERALD, "One-Time Reward Commands", "booster.one-time-reward-commands"));
        inventory.setItem(14, listPageItem(Material.REDSTONE, "Removal Commands", "booster.removal-commands"));
        addFooter(inventory, true);
        player.openInventory(inventory);
    }

    private void openNetworkPage(Player player) {
        Inventory inventory = createInventory(player, EditorView.NETWORK, "", "Network", 36);
        inventory.setItem(10, EditorItemFactory.toggle("Network Mode", plugin.getConfig().getBoolean("network.enabled", false)));
        inventory.setItem(11, valueItem(Material.MAP, "Gamemode ID", plugin.getConfig().getString("network.gamemode-id", ""), "Click to type gamemode ID."));
        inventory.setItem(12, EditorItemFactory.toggle("Primary Discord Node", plugin.getConfig().getBoolean("network.primary-discord-node", true)));
        inventory.setItem(13, valueItem(Material.CLOCK, "Sync Interval", plugin.getConfig().getInt("network.sync.interval-seconds") + "s", "Click to type seconds."));
        inventory.setItem(14, valueItem(Material.PAPER, "Profile Cache", plugin.getConfig().getInt("network.sync.profile-cache-seconds", 300) + "s", "Click to type seconds. 0 disables cache."));
        inventory.setItem(15, EditorItemFactory.toggle("MySQL SSL", plugin.getConfig().getBoolean("network.mysql.use-ssl", false)));
        inventory.setItem(16, valueItem(Material.COMPARATOR, "MySQL Timeout", plugin.getConfig().getInt("network.mysql.connection-timeout-seconds", 30) + "s", "Click to type seconds."));
        inventory.setItem(19, valueItem(Material.COMPASS, "MySQL Host", plugin.getConfig().getString("network.mysql.host", ""), "Click to type host."));
        inventory.setItem(20, valueItem(Material.REPEATER, "MySQL Port", plugin.getConfig().getInt("network.mysql.port"), "Click to type port."));
        inventory.setItem(21, valueItem(Material.BOOK, "MySQL Database", plugin.getConfig().getString("network.mysql.database", ""), "Click to type database."));
        inventory.setItem(22, valueItem(Material.PLAYER_HEAD, "MySQL Username", plugin.getConfig().getString("network.mysql.username", ""), "Click to type username."));
        inventory.setItem(23, valueItem(Material.TRIPWIRE_HOOK, "MySQL Password", masked(plugin.getConfig().getString("network.mysql.password", "")), "Click to type password or clear."));
        inventory.setItem(24, valueItem(Material.HOPPER, "MySQL Pool Size", plugin.getConfig().getInt("network.mysql.pool-size", 8), "Click to type max connections."));
        addFooter(inventory, true);
        player.openInventory(inventory);
    }

    private void openRankSyncPage(Player player) {
        openRankSyncPage(player, 0);
    }

    private void openRankSyncPage(Player player, int requestedPage) {
        List<String> keys = rankKeys();
        openRankSyncBrowser(player, "", requestedPage);
    }

    private void openRankSyncBrowser(Player player, String filter, int requestedPage) {
        String normalized = normalizeFilter(filter);
        List<EntryBrowserRequest.Entry> entries = new ArrayList<>();
        for (String key : rankKeys()) {
            if (!matchesFilter(key, normalized)) {
                continue;
            }
            String path = "rank-sync.ranks." + key;
            entries.add(EntryBrowserRequest.Entry.of(key, EditorItemFactory.item(Material.PAPER, FoStyle.THEME + "Rank " + key, List.of(
                    FoStyle.WHITE + "Permission: " + value(plugin.getConfig().getString(path + ".permission", "")),
                    FoStyle.WHITE + "Role ID: " + value(blankAsNone(plugin.getConfig().getString(path + ".role-id", ""))),
                    FoStyle.WHITE + "Click: " + value("edit"),
                    FoStyle.WHITE + "Right click: " + FoStyle.BAD + "delete"
            ))));
        }
        openEntryBrowser(player, new DiscordBrowserContext(EditorView.RANK_SYNC, "", EditorView.MAIN, ""), normalized, requestedPage, entries,
                "Rank Sync", EditorItemFactory.toggle("Rank Sync", plugin.getConfig().getBoolean("rank-sync.enabled", false)),
                EditorItemFactory.item(Material.ANVIL, FoStyle.THEME + "Add Rank", List.of(FoStyle.WHITE + "Click to type a new rank mapping.")));
    }

    private void openProfileFieldsPage(Player player) {
        openProfileFieldsPage(player, 0);
    }

    private void openProfileFieldsPage(Player player, int requestedPage) {
        List<Map<?, ?>> fields = plugin.getConfig().getMapList("profile.fields");
        openProfileFieldsBrowser(player, "", requestedPage);
    }

    private void openProfileFieldsBrowser(Player player, String filter, int requestedPage) {
        String normalized = normalizeFilter(filter);
        List<EntryBrowserRequest.Entry> entries = new ArrayList<>();
        List<Map<?, ?>> fields = plugin.getConfig().getMapList("profile.fields");
        for (int index = 0; index < fields.size(); index++) {
            Map<?, ?> field = fields.get(index);
            String name = trim(String.valueOf(mapValue(field, "name", "Field")));
            if (!matchesFilter(name, normalized)) {
                continue;
            }
            entries.add(EntryBrowserRequest.Entry.of(String.valueOf(index), EditorItemFactory.item(Material.PAPER, FoStyle.THEME + "Field #" + (index + 1), List.of(
                    FoStyle.WHITE + "Name: " + value(name),
                    FoStyle.WHITE + "Value: " + value(trim(String.valueOf(mapValue(field, "value", "N/A")))),
                    FoStyle.WHITE + "Inline: " + (Boolean.parseBoolean(String.valueOf(mapValue(field, "inline", false))) ? FoStyle.GOOD + "Enabled" : FoStyle.BAD + "Disabled"),
                    FoStyle.WHITE + "Same line: " + (Boolean.parseBoolean(String.valueOf(mapValue(field, "same-line", false))) ? FoStyle.GOOD + "Enabled" : FoStyle.BAD + "Disabled"),
                    FoStyle.WHITE + "Click: " + value("edit"),
                    FoStyle.WHITE + "Right click: " + FoStyle.BAD + "delete"
            ))));
        }
        openEntryBrowser(player, new DiscordBrowserContext(EditorView.PROFILE_FIELDS, "", EditorView.DISCORD, ""), normalized, requestedPage, entries,
                "Profile Fields", null,
                EditorItemFactory.item(Material.ANVIL, FoStyle.THEME + "Add Field", List.of(FoStyle.WHITE + "Click to type a new field.")));
    }

    private void openLeaderboardsPage(Player player) {
        openLeaderboardsPage(player, 0);
    }

    private void openLeaderboardsPage(Player player, int requestedPage) {
        List<String> aliases = leaderboardAliases();
        openLeaderboardsBrowser(player, "", requestedPage);
    }

    private void openLeaderboardsBrowser(Player player, String filter, int requestedPage) {
        String normalized = normalizeFilter(filter);
        List<EntryBrowserRequest.Entry> entries = new ArrayList<>();
        for (String alias : leaderboardAliases()) {
            if (!matchesFilter(alias, normalized)) {
                continue;
            }
            String basePath = "leaderboards.boards." + alias;
            entries.add(EntryBrowserRequest.Entry.of(alias, EditorItemFactory.item(Material.OAK_SIGN, FoStyle.THEME + "Board " + alias, List.of(
                    FoStyle.WHITE + "Title: " + value(trim(plugin.getConfig().getString(basePath + ".title", alias))),
                    FoStyle.WHITE + "Lines: " + value(plugin.getConfig().getStringList(basePath + ".lines").size()),
                    FoStyle.WHITE + "Click: " + value("edit"),
                    FoStyle.WHITE + "Right click: " + FoStyle.BAD + "delete"
            ))));
        }
        openEntryBrowser(player, new DiscordBrowserContext(EditorView.LEADERBOARDS, "", EditorView.MAIN, ""), normalized, requestedPage, entries,
                "Leaderboards", valueItem(Material.GLOW_INK_SAC, "Embed Color",
                        displayHex(plugin.getConfig().getString("leaderboards.embed-color", FoStyle.THEME)), "Click to type hex color."),
                EditorItemFactory.item(Material.ANVIL, FoStyle.THEME + "Add Board", List.of(FoStyle.WHITE + "Click to type a new board.")));
    }

    private void openBoardPage(Player player, String alias) {
        if (alias == null || alias.isBlank()) {
            openLeaderboardsPage(player);
            return;
        }

        String basePath = "leaderboards.boards." + alias;
        Inventory inventory = createInventory(player, EditorView.BOARD, alias, "Board " + alias, 27);
        inventory.setItem(10, valueItem(Material.OAK_SIGN, "Title", plugin.getConfig().getString(basePath + ".title", alias), "Click to type title."));
        inventory.setItem(11, valueItem(Material.PAPER, "Footer", blankAsNone(plugin.getConfig().getString(basePath + ".footer", "none")), "Click to type footer or none."));
        inventory.setItem(12, valueItem(Material.BOOK, "Empty Text", plugin.getConfig().getString(basePath + ".empty-text", ""), "Click to type empty text."));
        inventory.setItem(13, listPageItem(Material.WRITABLE_BOOK, "Lines", basePath + ".lines"));
        inventory.setItem(16, EditorItemFactory.item(Material.LAVA_BUCKET, FoStyle.BAD + "Delete Board", List.of(
                FoStyle.WHITE + "Opens confirmation.",
                FoStyle.WHITE + "Board: " + value(alias)
        )));
        addFooter(inventory, true);
        player.openInventory(inventory);
    }

    private void openCommandList(Player player, String path, EditorView returnView, String returnContext) {
        openCommandList(player, path, returnView, returnContext, 0);
    }

    private void openCommandList(Player player, String path, EditorView returnView, String returnContext, int requestedPage) {
        List<String> values = plugin.getConfig().getStringList(path);
        String filter = "";
        openCommandBrowser(player, path, returnView, returnContext, filter, requestedPage);
    }

    private void openCommandBrowser(Player player, String path, EditorView returnView, String returnContext, String filter, int requestedPage) {
        String normalized = normalizeFilter(filter);
        List<String> values = plugin.getConfig().getStringList(path);
        List<EntryBrowserRequest.Entry> entries = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (!matchesFilter(values.get(index), normalized)) {
                continue;
            }
            entries.add(EntryBrowserRequest.Entry.of(String.valueOf(index), EditorItemFactory.item(Material.PAPER, FoStyle.THEME + commandListEntryName(path) + " #" + (index + 1), List.of(
                    FoStyle.WHITE + trim(values.get(index)),
                    FoStyle.WHITE + "Click: " + value("edit"),
                    FoStyle.WHITE + "Right click: " + FoStyle.BAD + "delete"
            ))));
        }
        openEntryBrowser(player, new DiscordBrowserContext(EditorView.COMMAND_LIST, path, returnView, returnContext), normalized, requestedPage, entries,
                commandListTitle(path), null,
                EditorItemFactory.item(Material.ANVIL, FoStyle.THEME + "Add " + commandListEntryName(path), List.of(FoStyle.WHITE + "Click to type a new line.")));
    }

    private void openEntryBrowser(Player player, DiscordBrowserContext context, String filter, int page,
                                  List<EntryBrowserRequest.Entry> entries, String title, ItemStack extraButton, ItemStack addButton) {
        EntryBrowserMenus.open(player, EntryBrowserRequest.builder()
                .title(title)
                .entries(entries)
                .page(page)
                .filter(filter)
                .buttons(GuiButtonConfig.defaults())
                .showBack(true)
                .context(context)
                .extraButton(extraButton)
                .addButton(addButton)
                .build());
    }

    private void handleEntryBrowserClick(Player player, int slot, ClickType clickType, EntryBrowserHolder holder) {
        if (!(holder.request().context() instanceof DiscordBrowserContext context)) {
            return;
        }
        EntryBrowserClick click = EntryBrowserMenus.handleClick(slot, holder, clickType);
        int page = holder.request().page();
        String filter = holder.request().filter();
        switch (click.action()) {
            case ENTRY -> handleEntryBrowserEntry(player, context, click, page, filter);
            case ADD -> handleEntryBrowserAdd(player, context, page);
            case EXTRA -> handleEntryBrowserExtra(player, context, page);
            case BACK -> reopen(player, context.parent(), context.parentContext());
            case SEARCH -> beginEntryBrowserSearch(player, context, page, filter);
            case CLEAR_SEARCH -> openEntryBrowserForContext(player, context, "", 0);
            case PREVIOUS_PAGE -> openEntryBrowserForContext(player, context, filter, page - 1);
            case NEXT_PAGE -> openEntryBrowserForContext(player, context, filter, page + 1);
            case NONE -> {
            }
        }
    }

    private void handleEntryBrowserEntry(Player player, DiscordBrowserContext context, EntryBrowserClick click, int page, String filter) {
        String entryId = click.entryId();
        if (context.view() == EditorView.RANK_SYNC) {
            if (click.clickType() != null && click.clickType().isRightClick()) {
                openConfirmDelete(player, DeleteRequest.configPath(EditorView.RANK_SYNC, String.valueOf(page), "rank-sync.ranks." + entryId, "rank " + entryId));
            } else {
                beginTextInput(player, PendingInputType.EDIT_RANK, -1, EditorView.RANK_SYNC, String.valueOf(page), "", "rank mapping " + entryId, "key | permission | role-id", entryId);
            }
            return;
        }
        if (context.view() == EditorView.PROFILE_FIELDS) {
            int index = parseIndex(entryId);
            if (index < 0) {
                return;
            }
            if (click.clickType() != null && click.clickType().isRightClick()) {
                openConfirmDelete(player, DeleteRequest.listIndex(EditorView.PROFILE_FIELDS, String.valueOf(page), "profile.fields", index, "profile field #" + (index + 1)));
            } else {
                beginTextInput(player, PendingInputType.EDIT_PROFILE_FIELD, index, EditorView.PROFILE_FIELDS, String.valueOf(page), "", "profile field #" + (index + 1), "name | value | inline true/false | same-line true/false");
            }
            return;
        }
        if (context.view() == EditorView.LEADERBOARDS) {
            if (click.clickType() != null && click.clickType().isRightClick()) {
                openConfirmDelete(player, DeleteRequest.configPath(EditorView.LEADERBOARDS, String.valueOf(page), "leaderboards.boards." + entryId, "board " + entryId));
            } else {
                openBoardPage(player, entryId);
            }
            return;
        }
        if (context.view() == EditorView.COMMAND_LIST) {
            int index = parseIndex(entryId);
            String encoded = encodeCommandListContext(context.path(), context.parent(), context.parentContext(), page);
            if (index < 0) {
                return;
            }
            if (click.clickType() != null && click.clickType().isRightClick()) {
                openConfirmDelete(player, DeleteRequest.listIndex(EditorView.COMMAND_LIST, encoded, context.path(), index, commandListEntryName(context.path()) + " #" + (index + 1)));
            } else {
                beginTextInput(player, PendingInputType.EDIT_COMMAND, index, EditorView.COMMAND_LIST, encoded, context.path(), commandListEntryName(context.path()) + " #" + (index + 1), "plain text line");
            }
        }
    }

    private void handleEntryBrowserExtra(Player player, DiscordBrowserContext context, int page) {
        if (context.view() == EditorView.RANK_SYNC) {
            toggleBoolean(player, "rank-sync.enabled", EditorView.RANK_SYNC, String.valueOf(page));
        } else if (context.view() == EditorView.LEADERBOARDS) {
            beginTextInput(player, PendingInputType.SET_HEX, -1, EditorView.LEADERBOARDS, String.valueOf(page),
                    "leaderboards.embed-color", "leaderboard embed color", "#RRGGBB");
        }
    }

    private void handleEntryBrowserAdd(Player player, DiscordBrowserContext context, int page) {
        String pageContext = String.valueOf(page);
        switch (context.view()) {
            case RANK_SYNC -> beginTextInput(player, PendingInputType.ADD_RANK, -1, EditorView.RANK_SYNC, pageContext, "", "rank mapping", "key | permission | role-id");
            case PROFILE_FIELDS -> beginTextInput(player, PendingInputType.ADD_PROFILE_FIELD, -1, EditorView.PROFILE_FIELDS, pageContext, "", "profile field", "name | value | inline true/false | same-line true/false");
            case LEADERBOARDS -> beginTextInput(player, PendingInputType.ADD_BOARD, -1, EditorView.LEADERBOARDS, pageContext, "", "leaderboard board", "alias | title");
            case COMMAND_LIST -> {
                String encoded = encodeCommandListContext(context.path(), context.parent(), context.parentContext(), page);
                beginTextInput(player, PendingInputType.ADD_COMMAND, -1, EditorView.COMMAND_LIST, encoded, context.path(), commandListEntryName(context.path()), "plain text line");
            }
            default -> {
            }
        }
    }

    private void beginEntryBrowserSearch(Player player, DiscordBrowserContext context, int page, String filter) {
        TextDialogRequest request = new TextDialogRequest(
                "Search",
                List.of(FoStyle.WHITE + "Filter entries by name or value."),
                "Search",
                filter,
                "entry text",
                DialogButton.search("Search", "", 100),
                DialogButton.cancel("Back", "", 100),
                320,
                300,
                64,
                true,
                true,
                false
        );
        EditorDialogInputs.openTextFromInventory(
                plugin,
                plugin.getCore().inventoryCloseSuppressor(),
                plugin.getCore().dialogService(),
                player,
                request,
                value -> openEntryBrowserForContext(player, context, normalizeFilter(value), 0),
                () -> openEntryBrowserForContext(player, context, filter, page)
        );
    }

    private void openEntryBrowserForContext(Player player, DiscordBrowserContext context, String filter, int page) {
        switch (context.view()) {
            case RANK_SYNC -> openRankSyncBrowser(player, filter, page);
            case PROFILE_FIELDS -> openProfileFieldsBrowser(player, filter, page);
            case LEADERBOARDS -> openLeaderboardsBrowser(player, filter, page);
            case COMMAND_LIST -> openCommandBrowser(player, context.path(), context.parent(), context.parentContext(), filter, page);
            default -> reopen(player, context.parent(), context.parentContext());
        }
    }

    private int parseIndex(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private String normalizeFilter(String filter) {
        return filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesFilter(String value, String filter) {
        return filter == null || filter.isBlank() || value.toLowerCase(Locale.ROOT).contains(filter);
    }

    private void reopenCommandList(Player player, String encodedContext) {
        CommandListContext context = parseCommandListContext(encodedContext);
        if (context == null) {
            openMainMenu(player);
            return;
        }
        openCommandList(player, context.path(), context.returnView(), context.returnContext(), context.page());
    }

    private void openConfirmDelete(Player player, DeleteRequest request) {
        pendingDeletes.put(player.getUniqueId(), request);
        Inventory inventory = createInventory(player, EditorView.CONFIRM_DELETE, "", "Confirm Delete", 27);
        inventory.setItem(11, EditorItemFactory.cancel());
        inventory.setItem(15, EditorItemFactory.confirm());
        player.openInventory(inventory);
    }

    private Inventory createInventory(Player player, EditorView view, String context, String title, int size) {
        EditorHolder holder = new EditorHolder(player.getUniqueId(), view, context);
        String rawTitle = title;
        if (rawTitle.length() > 32) {
            rawTitle = rawTitle.substring(0, 32);
        }
        Inventory inventory = Bukkit.createInventory(holder, size, GuiTitles.format(rawTitle));
        holder.setInventory(inventory);
        fillBackground(inventory);
        return inventory;
    }

    private int backSlot(Inventory inventory) {
        return GuiSlots.bottomMiddleSlot(inventory.getSize() / 9);
    }

    private void addPageButtons(Inventory inventory, int page, int maxPage) {
        GuiButtonConfig buttons = GuiButtonConfig.defaults();
        if (page > 0) {
            inventory.setItem(PREVIOUS_PAGE_SLOT, buttons.previousPage(page, maxPage));
        }
        if (page < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, buttons.nextPage(page, maxPage));
        }
    }

    private ItemStack emptyEntryFiller() {
        return EditorItemFactory.item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, " ", List.of());
    }

    private int pagedEntryOffset(int slot) {
        for (int offset = 0; offset < PAGED_ENTRY_SLOTS.length; offset++) {
            if (PAGED_ENTRY_SLOTS[offset] == slot) {
                return offset;
            }
        }
        return -1;
    }

    private int maxPage(int entryCount) {
        return entryCount <= 0 ? 0 : (entryCount - 1) / ENTRIES_PER_PAGE;
    }

    private int currentPage(String context, int maxPage) {
        int requestedPage;
        try {
            requestedPage = Integer.parseInt(context == null ? "0" : context);
        } catch (NumberFormatException exception) {
            requestedPage = 0;
        }
        return Math.max(0, Math.min(requestedPage, maxPage));
    }

    private void addFooter(Inventory inventory, boolean back) {
        if (back) {
            inventory.setItem(backSlot(inventory), EditorItemFactory.back());
        }
    }

    private boolean handleCommonNavigation(Player player, int slot, EditorHolder holder) {
        if (holder.view() == EditorView.MAIN) {
            return false;
        }
        if (slot != backSlot(holder.getInventory())) {
            return false;
        }

        switch (holder.view()) {
            case MAIN -> player.closeInventory();
            case PROFILE_FIELDS -> openDiscordPage(player);
            case BOARD -> openLeaderboardsPage(player);
            case COMMAND_LIST -> {
                CommandListContext context = parseCommandListContext(holder.context());
                if (context == null) {
                    openMainMenu(player);
                } else {
                    reopen(player, context.returnView(), context.returnContext());
                }
            }
            default -> openMainMenu(player);
        }
        return true;
    }

    private ItemStack pageItem(Material material, String name, String description) {
        return EditorItemFactory.item(material, FoStyle.THEME + name, List.of(FoStyle.WHITE + description, FoStyle.WHITE + "Click to open."));
    }

    private ItemStack valueItem(Material material, String name, Object current, String action) {
        return EditorItemFactory.item(material, FoStyle.THEME + name, List.of(
                FoStyle.WHITE + "Current: " + value(trim(String.valueOf(current))),
                FoStyle.WHITE + action
        ));
    }

    private ItemStack listPageItem(Material material, String name, String path) {
        int count = plugin.getConfig().isList(path)
                ? plugin.getConfig().getList(path, List.of()).size()
                : plugin.getConfig().getKeys(true).stream().filter(key -> key.startsWith(path + ".")).toList().size();
        return EditorItemFactory.item(material, FoStyle.THEME + name, List.of(
                FoStyle.WHITE + "Entries: " + value(count),
                FoStyle.WHITE + "Click to edit."
        ));
    }

    private void fillBackground(Inventory inventory) {
        ItemStack filler = EditorItemFactory.filler();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private String value(Object value) {
        return FoStyle.THEME + protectLiteralFormatting(String.valueOf(value));
    }

    private String protectLiteralFormatting(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // Core text rendering has no literal-formatting escape API; invisible separators keep user values literal in editor text.
        return value.replace("#", "#\u200B").replace("&", "&\u200B");
    }

    private String displayHex(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.startsWith("#") ? value : "#" + value;
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) {
            return "None";
        }
        return value.length() > 54 ? value.substring(0, 51) + "..." : value;
    }

    private String blankAsNone(String value) {
        return value == null || value.isBlank() ? "None" : value;
    }

    private String masked(String value) {
        return value == null || value.isBlank() || "PUT_BOT_TOKEN_HERE".equalsIgnoreCase(value) ? "None" : "Set";
    }

    private String normalizeClearableText(String value) {
        if (value.equalsIgnoreCase("clear")) {
            return "";
        }
        if (value.equalsIgnoreCase("none")) {
            return "none";
        }
        if (value.startsWith(" ")) {
            return value.trim();
        }
        return value;
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private String[] split(String message) {
        return Arrays.stream(message.split("\\|", -1))
                .map(String::trim)
                .toArray(String[]::new);
    }

    private Object mapValue(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private RankInput parseRankInput(String message) {
        String[] parts = split(message);
        if (parts.length < 3) {
            return null;
        }
        String key = normalizeKey(parts[0]);
        String permission = parts[1];
        String roleId = parts[2];
        if (key.isBlank() || permission.isBlank()) {
            return null;
        }
        return new RankInput(key, permission, roleId);
    }

    private ProfileFieldInput parseProfileFieldInput(String message) {
        String[] parts = split(message);
        if (parts.length < 2) {
            return null;
        }
        String name = parts[0];
        String value = parts[1];
        boolean inline = parts.length >= 3 && parseBoolean(parts[2]);
        boolean sameLine = parts.length >= 4 && parseBoolean(parts[3]);
        if (name.isBlank() || value.isBlank()) {
            return null;
        }
        return new ProfileFieldInput(name, value, inline, sameLine);
    }

    private boolean parseBoolean(String value) {
        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on")
                || value.equalsIgnoreCase("enabled");
    }

    private List<String> rankKeys() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("rank-sync.ranks");
        if (section == null) {
            return List.of();
        }
        return section.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private List<String> leaderboardAliases() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("leaderboards.boards");
        if (section == null) {
            return List.of();
        }
        return section.getKeys(false).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private String commandListTitle(String path) {
        if (path.endsWith(".lines")) {
            return "Board Lines";
        }
        return commandListEntryName(path) + "s";
    }

    private String commandListEntryName(String path) {
        if (path.endsWith(".lines")) {
            return "Line";
        }
        if (path.contains("always")) {
            return "Always Command";
        }
        if (path.contains("one-time")) {
            return "One-Time Command";
        }
        if (path.contains("removal")) {
            return "Removal Command";
        }
        return "Unlink Command";
    }

    private String encodeCommandListContext(String path, EditorView returnView, String returnContext, int page) {
        return path + CONTEXT_SEPARATOR + returnView.name() + CONTEXT_SEPARATOR
                + (returnContext == null ? "" : returnContext) + CONTEXT_SEPARATOR + page;
    }

    private CommandListContext parseCommandListContext(String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        String[] parts = context.split(CONTEXT_SEPARATOR, -1);
        if (parts.length < 2) {
            return null;
        }
        try {
            int page = parts.length >= 4 ? Integer.parseInt(parts[3]) : 0;
            return new CommandListContext(parts[0], EditorView.valueOf(parts[1]), parts.length >= 3 ? parts[2] : "", page);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String range(int min, int max) {
        return min + ":" + max;
    }

    private IntRange parseRange(String context) {
        String[] parts = context.split(":", -1);
        if (parts.length != 2) {
            return new IntRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        return new IntRange(
                LargeNumberParser.parse(parts[0]).orElseThrow().intValueExact(),
                LargeNumberParser.parse(parts[1]).orElseThrow().intValueExact()
        );
    }

    private enum EditorView {
        MAIN,
        DISCORD,
        CHAT_BRIDGE,
        LINKING,
        BOOSTER,
        NETWORK,
        RANK_SYNC,
        PROFILE_FIELDS,
        LEADERBOARDS,
        BOARD,
        COMMAND_LIST,
        CONFIRM_DELETE
    }

    private enum PendingInputType {
        SET_TEXT,
        SET_INT,
        SET_HEX,
        ADD_COMMAND,
        EDIT_COMMAND,
        ADD_RANK,
        EDIT_RANK,
        ADD_PROFILE_FIELD,
        EDIT_PROFILE_FIELD,
        ADD_BOARD
    }

    private enum DeleteKind {
        CONFIG_PATH,
        LIST_INDEX
    }

    private record PendingInput(
            PendingInputType type,
            int index,
            EditorView returnView,
            String returnContext,
            String path,
            String context
    ) {
    }

    private record DeleteRequest(
            DeleteKind kind,
            EditorView returnView,
            String returnContext,
            String path,
            int index,
            String label,
            EditorView successView,
            String successContext
    ) {

        private static DeleteRequest configPath(EditorView returnView, String returnContext, String path, String label) {
            return new DeleteRequest(DeleteKind.CONFIG_PATH, returnView, returnContext, path, -1, label,
                    returnView, returnContext);
        }

        private static DeleteRequest configPath(EditorView returnView, String returnContext, String path, String label,
                                                EditorView successView, String successContext) {
            return new DeleteRequest(DeleteKind.CONFIG_PATH, returnView, returnContext, path, -1, label,
                    successView, successContext);
        }

        private static DeleteRequest listIndex(EditorView returnView, String returnContext, String path, int index, String label) {
            return new DeleteRequest(DeleteKind.LIST_INDEX, returnView, returnContext, path, index, label,
                    returnView, returnContext);
        }
    }

    private record IntRange(int min, int max) {
    }

    private record RankInput(String key, String permission, String roleId) {
    }

    private record ProfileFieldInput(String name, String value, boolean inline, boolean sameLine) {
    }

    private record CommandListContext(String path, EditorView returnView, String returnContext, int page) {
    }

    private record DiscordBrowserContext(EditorView view, String path, EditorView parent, String parentContext) {
    }

    private static final class EditorHolder implements InventoryHolder {

        private final UUID ownerUuid;
        private final EditorView view;
        private final String context;
        private Inventory inventory;

        private EditorHolder(UUID ownerUuid, EditorView view, String context) {
            this.ownerUuid = ownerUuid;
            this.view = view;
            this.context = context == null ? "" : context;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private UUID ownerUuid() {
            return ownerUuid;
        }

        private EditorView view() {
            return view;
        }

        private String context() {
            return context;
        }
    }
}
