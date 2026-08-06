package me.foesio.foDiscordBot;

import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.command.CommandVisibilityService;
import me.foesio.core.command.FoAdminCommand;
import me.foesio.core.config.FoConfigDefaults;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.message.FoStyle;
import me.foesio.core.plugin.FoPluginTitle;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.update.UpdateNoticeService;
import me.foesio.foDiscordBot.command.DiscordCommand;
import me.foesio.foDiscordBot.command.FoDiscordCommand;
import me.foesio.foDiscordBot.command.LinkCommand;
import me.foesio.foDiscordBot.command.UnlinkCommand;
import me.foesio.foDiscordBot.config.PluginConfig;
import me.foesio.foDiscordBot.editor.ConfigEditorService;
import me.foesio.foDiscordBot.listener.ChatRelayListener;
import me.foesio.foDiscordBot.listener.PlayerActivityListener;
import me.foesio.foDiscordBot.service.AdvancementService;
import me.foesio.foDiscordBot.service.BoosterService;
import me.foesio.foDiscordBot.service.DiscordBotManager;
import me.foesio.foDiscordBot.service.LeaderboardService;
import me.foesio.foDiscordBot.service.LinkRepository;
import me.foesio.foDiscordBot.service.LinkService;
import me.foesio.foDiscordBot.service.NetworkSyncService;
import me.foesio.foDiscordBot.service.ProfileService;
import me.foesio.foDiscordBot.service.RankSyncService;
import me.foesio.foDiscordBot.service.SkinAvatarService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public final class FoDiscordBot extends JavaPlugin {

    public static final String ADMIN_PERMISSION = "fodiscordbot.admin";
    public static final String LEGACY_ADMIN_PERMISSION = "fodiscord.admin";
    private static final Set<String> PREFIXED_INGAME_MESSAGES = Set.of(
            "no-permission",
            "players-only",
            "loading",
            "link.already-linked",
            "link.generated",
            "link.cooldown",
            "link.error",
            "unlink.success",
            "unlink.not-linked",
            "unlink.error",
            "rewards.success",
            "booster.success",
            "booster.removed",
            "discord.text",
            "discord.not-configured",
            "reload.success",
            "reload.error",
            "reload.usage",
            "version.author",
            "version.current",
            "reset-rewards.usage",
            "reset-rewards.players-only-all",
            "reset-rewards.unknown-player",
            "reset-rewards.confirm-open",
            "reset-rewards.cancelled",
            "reset-rewards.success",
            "reset-rewards.error",
            "reset-leaderboards.usage",
            "reset-leaderboards.network-disabled",
            "reset-leaderboards.players-only-all",
            "reset-leaderboards.confirm-open",
            "reset-leaderboards.cancelled",
            "reset-leaderboards.success",
            "reset-leaderboards.error",
            "editor.open",
            "editor.saved",
            "editor.save-error",
            "editor.input-cancelled",
            "editor.input-empty",
            "editor.invalid-input",
            "editor.deleted",
            "editor.invalid-color"
    );

    private PluginConfig pluginConfig;
    private FoMessageService messages;
    private LinkRepository linkRepository;
    private LinkService linkService;
    private ProfileService profileService;
    private LeaderboardService leaderboardService;
    private AdvancementService advancementService;
    private DiscordBotManager discordBotManager;
    private ConfigEditorService configEditorService;
    private NetworkSyncService networkSyncService;
    private BoosterService boosterService;
    private RankSyncService rankSyncService;
    private SkinAvatarService skinAvatarService;
    private FoCoreContext core;
    private UpdateNoticeService updateNoticeService;
    private CommandVisibilityService commandVisibilityService;

    @Override
    public void onEnable() {
        initializeConfig();
        this.messages = FoMessageService.load(this, messageMigrations());

        if (!hasPlaceholderApi()) {
            logWarning("PlaceholderAPI is not installed. Placeholder-based profile fields and leaderboards will not resolve.");
        }

        try {
            this.core = FoPluginCore.create(this);
            this.core.metrics(33179);
            this.core.warnIfNativeDialogsUnavailable();
            this.updateNoticeService = core.createUpdateNotices(messages, "fodiscordbot");
            reloadConfiguration();

            this.linkRepository = new LinkRepository(this);
            this.linkRepository.initialize();

            this.skinAvatarService = new SkinAvatarService(this);
            this.profileService = new ProfileService(this, linkRepository);
            this.linkService = new LinkService(this, linkRepository);
            this.leaderboardService = new LeaderboardService(this, linkRepository);
            this.advancementService = new AdvancementService(this, linkRepository);
            this.discordBotManager = new DiscordBotManager(this, linkService, profileService, leaderboardService, advancementService);
            this.configEditorService = new ConfigEditorService(this);
            this.networkSyncService = new NetworkSyncService(this, profileService, leaderboardService, advancementService);
            this.boosterService = new BoosterService(this, linkRepository);
            this.rankSyncService = new RankSyncService(this, linkRepository);

            registerCommands();
            getServer().getPluginManager().registerEvents(new PlayerActivityListener(this), this);
            getServer().getPluginManager().registerEvents(new ChatRelayListener(this), this);
            getServer().getPluginManager().registerEvents(configEditorService, this);

            linkService.start();
            discordBotManager.startAsync();
            networkSyncService.start();
            updateNoticeService.start();
        } catch (Exception exception) {
            logSevere("Failed to enable FoDiscordBot: " + exception.getMessage(), exception);
            getServer().getPluginManager().disablePlugin(this);
        }

    }

    @Override
    public void onDisable() {
        if (commandVisibilityService != null) {
            commandVisibilityService.close();
            commandVisibilityService = null;
        }
        if (core != null) {
            core.close();
            core = null;
            updateNoticeService = null;
        }
        if (discordBotManager != null) {
            discordBotManager.shutdown();
        }
        if (linkService != null) {
            linkService.shutdown();
        }
        if (networkSyncService != null) {
            networkSyncService.shutdown();
        }
        if (linkRepository != null) {
            linkRepository.close();
        }
    }

    public boolean reloadPlugin() {
        FoReloadResult result = createReloadRegistry(true, true, null).reload();
        if (!result.successful()) {
            logSevere("Failed to reload FoDiscordBot at step " + result.failedStep() + ": "
                    + result.errorMessage(), result.error());
            return false;
        }
        return true;
    }

    public boolean syncConfigFromDisk() {
        FoReloadResult result = createReloadRegistry(false, false, null).reload();
        if (!result.successful()) {
            logSevere("Failed to sync FoDiscordBot config at step " + result.failedStep() + ": "
                    + result.errorMessage(), result.error());
            return false;
        }
        return true;
    }

    public boolean saveAndApplyConfig() {
        try {
            PluginConfig previous = this.pluginConfig;
            migrateLegacyLeaderboardConfigs();
            migrateLegacyLinkRewardConfig();
            migrateLegacyBoosterRewardConfig();
            ensureCoreConfigDefaults();
            mergeMissingConfigDefaults();
            ensureLeaderboardBoardFooters();
            ensureAdvancementConfig();
            removeLegacyMessagesConfig();
            saveConfig();
            FoReloadResult result = createReloadRegistry(true, false, previous).reload();
            if (!result.successful()) {
                logSevere("Failed to apply FoDiscordBot config at step " + result.failedStep() + ": "
                        + result.errorMessage(), result.error());
                return false;
            }
            return true;
        } catch (Exception exception) {
            logSevere("Failed to save config changes: " + exception.getMessage(), exception);
            return false;
        }
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public FoMessageService messages() {
        return messages;
    }

    public String prefixLog(String message) {
        String text = message == null ? "" : message;
        String prefix = "[" + FoPluginTitle.resolve(this) + "] ";
        return text.startsWith(prefix) ? text : prefix + text;
    }

    public void logInfo(String message) {
        getLogger().info(prefixLog(message));
    }

    public void logWarning(String message) {
        getLogger().warning(prefixLog(message));
    }

    public void logSevere(String message) {
        getLogger().severe(prefixLog(message));
    }

    public void logSevere(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, prefixLog(message), throwable);
    }

    public LinkService getLinkService() {
        return linkService;
    }

    public ConfigEditorService getConfigEditorService() {
        return configEditorService;
    }

    public LeaderboardService getLeaderboardService() {
        return leaderboardService;
    }

    public DiscordBotManager getDiscordBotManager() {
        return discordBotManager;
    }

    public NetworkSyncService getNetworkSyncService() {
        return networkSyncService;
    }

    public BoosterService getBoosterService() {
        return boosterService;
    }

    public RankSyncService getRankSyncService() {
        return rankSyncService;
    }

    public SkinAvatarService getSkinAvatarService() {
        return skinAvatarService;
    }

    public AdvancementService getAdvancementService() {
        return advancementService;
    }

    public FoCoreContext getCore() {
        return core;
    }

    public UpdateNoticeService getUpdateNoticeService() {
        return updateNoticeService;
    }

    public boolean hasPlaceholderApi() {
        return getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    private void reloadConfiguration() {
        this.pluginConfig = PluginConfig.load(getConfig());
    }

    private void initializeConfig() {
        saveDefaultConfig();
        boolean coreDefaults = ensureCoreConfigDefaults();
        boolean migrated = migrateLegacyLeaderboardConfigs();
        boolean linkMigrated = migrateLegacyLinkRewardConfig();
        boolean boosterMigrated = migrateLegacyBoosterRewardConfig();
        boolean merged = mergeMissingConfigDefaults();
        boolean boardFooters = ensureLeaderboardBoardFooters();
        boolean advancementConfig = ensureAdvancementConfig();
        boolean messagesRemoved = removeLegacyMessagesConfig();
        if (coreDefaults || migrated || linkMigrated || boosterMigrated || merged
                || boardFooters || advancementConfig || messagesRemoved) {
            saveConfig();
        }
        reloadConfig();
        reloadConfiguration();
    }

    private boolean ensureCoreConfigDefaults() {
        boolean missingDefaults = !getConfig().contains("native-dialogs.enabled", true)
                || !getConfig().contains("native-dialogs.warn-on-fallback", true)
                || !getConfig().contains("discord-webhook.enabled", true)
                || getConfig().contains("plugin-title", true);
        boolean missingComments = getConfig().getComments("native-dialogs").isEmpty()
                || getConfig().getComments("native-dialogs.enabled").isEmpty()
                || getConfig().getComments("native-dialogs.warn-on-fallback").isEmpty()
                || getConfig().getComments("discord-webhook").isEmpty();

        FoConfigDefaults.addStandardDefaults(this);
        return missingDefaults || missingComments;
    }

    private FoReloadRegistry createReloadRegistry(boolean reloadServices, boolean forceDiscordReload,
                                                   PluginConfig previousConfig) {
        FoReloadRegistry registry = FoReloadRegistry.create()
                .add("config", this::reloadConfigAndApplyDefaults)
                .add("core-context", this::refreshCoreContext);
        if (messages != null) {
            registry.addMessages(messages);
        }
        if (!reloadServices) {
            return registry;
        }

        registry.add("link-repository", () -> {
            if (linkRepository != null) {
                linkRepository.initialize();
            }
        });
        registry.add("link-service", () -> {
            if (linkService != null) {
                linkService.reload();
            }
        });
        registry.add("discord", () -> {
            if (discordBotManager == null) {
                return;
            }
            if (forceDiscordReload || shouldRestartDiscord(previousConfig, pluginConfig)) {
                discordBotManager.reloadAsync();
            } else {
                discordBotManager.handleConfigReload();
            }
        });
        registry.add("network-sync", () -> {
            if (networkSyncService != null) {
                networkSyncService.reload();
            }
        });
        return registry;
    }

    private void reloadConfigAndApplyDefaults() {
        reloadConfig();
        boolean coreDefaults = ensureCoreConfigDefaults();
        boolean migrated = migrateLegacyLeaderboardConfigs();
        boolean linkMigrated = migrateLegacyLinkRewardConfig();
        boolean boosterMigrated = migrateLegacyBoosterRewardConfig();
        boolean merged = mergeMissingConfigDefaults();
        boolean boardFooters = ensureLeaderboardBoardFooters();
        boolean advancementConfig = ensureAdvancementConfig();
        boolean messagesRemoved = removeLegacyMessagesConfig();
        if (coreDefaults || migrated || linkMigrated || boosterMigrated || merged
                || boardFooters || advancementConfig || messagesRemoved) {
            saveConfig();
            reloadConfig();
        }
        reloadConfiguration();
    }

    private FoMessageMigrations messageMigrations() {
        return FoMessageMigrations.create()
                .add(this::migrateLegacyPrefix)
                .add(this::prefixLegacyIngameMessages)
                .remove("message-version")
                .remove("ingame.linkreward")
                .remove("ingame.native-dialogs-fallback")
                .remove("ingame.editor.input-start")
                .remove("ingame.editor.input-start-format")
                .remove("messages")
                .replaceExact("ingame.reload.usage", "#a7b8b0Use #03fc88/fodiscord <reload|editor|resetrewards>#a7b8b0.",
                        "#a7b8b0Use #03fc88/fodiscordbotadmin <version|reload|editor|resetrewards|resetleaderboards>#a7b8b0.")
                .replaceExact("ingame.reload.usage", "#a7b8b0Use #03fc88/fodiscordbotadmin <version|reload|editor|resetrewards>#a7b8b0.",
                        "#a7b8b0Use #03fc88/fodiscordbotadmin <version|reload|editor|resetrewards|resetleaderboards>#a7b8b0.")
                .replaceExact("ingame.reset-rewards.usage", "#a7b8b0Use #03fc88/fodiscord resetrewards <linked|booster> <player|all> [gamemode]#a7b8b0.",
                        "#a7b8b0Use #03fc88/fodiscordbotadmin resetrewards <linked|booster> <player|discord|all> [gamemode]#a7b8b0.")
                .build();
    }

    private boolean migrateLegacyPrefix(FileConfiguration config) {
        String legacyPrefix = config.getString("prefix");
        String currentPrefix = config.getString("tokens.prefix");
        boolean changed = false;
        if (legacyPrefix != null && (currentPrefix == null || FoStyle.defaultPrefixTemplate().equals(currentPrefix))) {
            config.set("tokens.prefix", legacyPrefix);
            changed = true;
        }
        if (config.contains("prefix", true)) {
            config.set("prefix", null);
            changed = true;
        }
        return changed;
    }

    private boolean prefixLegacyIngameMessages(FileConfiguration config) {
        boolean changed = false;
        for (String path : PREFIXED_INGAME_MESSAGES) {
            String fullPath = "ingame." + path;
            String value = config.getString(fullPath);
            if (value == null || value.contains("{prefix}")) {
                continue;
            }
            config.set(fullPath, "{prefix}" + value);
            changed = true;
        }
        return changed;
    }

    private void refreshCoreContext() {
        FoCoreContext previous = core;
        if (previous != null) {
            previous.close();
        }
        FoCoreContext next = FoPluginCore.create(this);
        next.metrics(33179);
        next.warnIfNativeDialogsUnavailable();
        core = next;
    }

    private boolean mergeMissingConfigDefaults() {
        YamlConfiguration defaults = loadBundledConfigDefaults();
        if (defaults == null) {
            getConfig().options().copyDefaults(true);
            return false;
        }

        boolean changed = copyMissingDefaults(defaults, getConfig(), "");
        if (changed) {
            logInfo("Added missing config defaults from the bundled config.yml.");
        }
        return changed;
    }

    private boolean ensureLeaderboardBoardFooters() {
        ConfigurationSection boards = getConfig().getConfigurationSection("leaderboards.boards");
        if (boards == null) {
            return false;
        }

        String legacyFooter = defaultIfBlank(getConfig().getString("leaderboards.footer"), "none");
        boolean changed = false;
        for (String alias : boards.getKeys(false)) {
            ConfigurationSection board = boards.getConfigurationSection(alias);
            if (board == null || board.contains("footer", true)) {
                continue;
            }

            board.set("footer", legacyFooter);
            changed = true;
        }
        return changed;
    }

    private boolean ensureAdvancementConfig() {
        boolean changed = false;
        if (getConfig().contains("advancements.enabled", true)) {
            getConfig().set("advancement.enabled", getConfig().getBoolean("advancements.enabled", false));
            changed = true;
        }
        if (getConfig().contains("advancements", true)) {
            getConfig().set("advancements", null);
            changed = true;
        }
        if (!getConfig().contains("advancement.enabled", true)) {
            getConfig().set("advancement.enabled", false);
            changed = true;
        }
        return changed;
    }

    private boolean migrateLegacyBoosterRewardConfig() {
        if (!getConfig().contains("booster.reward-commands", true)) {
            return false;
        }

        boolean changed = false;
        if (!getConfig().contains("booster.one-time-reward-commands", true)) {
            getConfig().set("booster.one-time-reward-commands", getConfig().getStringList("booster.reward-commands"));
            logInfo("Migrated booster.reward-commands to booster.one-time-reward-commands.");
            changed = true;
        }
        if (!getConfig().contains("booster.always-reward-commands", true)) {
            getConfig().set("booster.always-reward-commands", List.of());
            changed = true;
        }
        getConfig().set("booster.reward-commands", null);
        changed = true;
        return changed;
    }

    private boolean migrateLegacyLinkRewardConfig() {
        boolean changed = false;
        List<String> legacyCommands = getConfig().getStringList("linking.reward-commands");

        if (!getConfig().contains("linking.one-time-reward-commands", true)) {
            getConfig().set("linking.one-time-reward-commands", legacyCommands);
            if (getConfig().contains("linking.reward-commands", true)) {
                logInfo("Migrated linking.reward-commands to linking.one-time-reward-commands.");
            }
            changed = true;
        }
        if (!getConfig().contains("linking.always-reward-commands", true)) {
            getConfig().set("linking.always-reward-commands", List.of());
            changed = true;
        }
        if (!getConfig().contains("linking.unlink-commands", true)) {
            getConfig().set("linking.unlink-commands", List.of());
            changed = true;
        }
        if (getConfig().contains("linking.reward-commands", true)) {
            getConfig().set("linking.reward-commands", null);
            changed = true;
        }

        return changed;
    }

    private boolean removeLegacyMessagesConfig() {
        if (!getConfig().contains("messages", true)) {
            return false;
        }

        getConfig().set("messages", null);
        logInfo("Removed legacy config.yml messages section. Edit messages.yml instead.");
        return true;
    }

    private YamlConfiguration loadBundledConfigDefaults() {
        try (InputStream stream = getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            logWarning("Could not load bundled config defaults: " + exception.getMessage());
            return null;
        }
    }

    private boolean copyMissingDefaults(ConfigurationSection defaults, ConfigurationSection target, String path) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            String fullPath = path.isBlank() ? key : path + "." + key;
            Object defaultValue = defaults.get(key);
            if (defaultValue instanceof ConfigurationSection defaultSection) {
                if (!target.contains(fullPath, true)) {
                    target.createSection(fullPath);
                    changed = true;
                }
                if (target.isConfigurationSection(fullPath)) {
                    changed |= copyMissingDefaults(defaultSection, target, fullPath);
                }
                continue;
            }

            if (!target.contains(fullPath, true)) {
                target.set(fullPath, defaultValue);
                changed = true;
            }
        }
        return changed;
    }

    private boolean migrateLegacyLeaderboardConfigs() {
        ConfigurationSection boards = getConfig().getConfigurationSection("leaderboards.boards");
        if (boards == null) {
            return false;
        }

        boolean changed = false;
        for (String alias : new ArrayList<>(boards.getKeys(false))) {
            ConfigurationSection board = boards.getConfigurationSection(alias);
            if (board == null || !hasLegacyLeaderboardKeys(board)) {
                continue;
            }

            changed |= migrateLegacyLeaderboardBoard(alias, board);
        }

        return changed;
    }

    private boolean hasLegacyLeaderboardKeys(ConfigurationSection board) {
        return board.contains("board-id")
                || board.contains("type")
                || board.contains("entries")
                || board.contains("value-mode")
                || board.contains("line-format");
    }

    private boolean migrateLegacyLeaderboardBoard(String alias, ConfigurationSection board) {
        boolean changed = false;

        String title = defaultIfBlank(board.getString("title"), alias);
        if (!title.equals(board.getString("title"))) {
            board.set("title", title);
            changed = true;
        }

        String emptyText = defaultIfBlank(board.getString("empty-text"), "No entries found.");
        if (!emptyText.equals(board.getString("empty-text"))) {
            board.set("empty-text", emptyText);
            changed = true;
        }

        List<String> lines = board.getStringList("lines");
        if (lines.isEmpty()) {
            String boardId = defaultIfBlank(board.getString("board-id"), alias);
            String timedType = defaultIfBlank(board.getString("type"), "alltime");
            String valueMode = defaultIfBlank(board.getString("value-mode"), "value");
            String lineFormat = defaultIfBlank(board.getString("line-format"), "`#{position}` **{display_name}** - `{value}`");
            int entries = Math.max(1, board.getInt("entries", 10));

            lines = new ArrayList<>();
            for (int position = 1; position <= entries; position++) {
                lines.add(migrateLegacyLeaderboardLine(lineFormat, boardId, timedType, valueMode, position));
            }
            board.set("lines", lines);
            changed = true;
        }

        changed |= clearLegacyLeaderboardKey(board, "board-id");
        changed |= clearLegacyLeaderboardKey(board, "type");
        changed |= clearLegacyLeaderboardKey(board, "entries");
        changed |= clearLegacyLeaderboardKey(board, "value-mode");
        changed |= clearLegacyLeaderboardKey(board, "line-format");

        if (changed) {
            logInfo("Migrated legacy leaderboard board '" + alias + "' to placeholder lines.");
        }

        return changed;
    }

    private String migrateLegacyLeaderboardLine(String lineFormat, String boardId, String timedType, String valueMode, int position) {
        String namePlaceholder = "%ajlb_lb_" + boardId + "_" + position + "_" + timedType + "_name%";
        String displayNamePlaceholder = "%ajlb_lb_" + boardId + "_" + position + "_" + timedType + "_displayname%";
        String valuePlaceholder = "%ajlb_lb_" + boardId + "_" + position + "_" + timedType + "_" + valueMode + "%";

        return lineFormat
                .replace("{position}", String.valueOf(position))
                .replace("{display_name}", displayNamePlaceholder)
                .replace("{player_name}", namePlaceholder)
                .replace("{name}", namePlaceholder)
                .replace("{value}", valuePlaceholder);
    }

    private boolean clearLegacyLeaderboardKey(ConfigurationSection board, String key) {
        if (!board.contains(key)) {
            return false;
        }
        board.set(key, null);
        return true;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private void registerCommands() {
        FoDiscordCommand foDiscordCommand = new FoDiscordCommand(this);
        FoAdminCommand adminCommand = FoAdminCommand.builder(this, messages)
                .commandName("fodiscordbotadmin")
                .permission(ADMIN_PERMISSION)
                .versionCommand(false)
                .adminMessages(foDiscordCommand.adminMessages())
                .addSubcommand(foDiscordCommand.versionSubcommand())
                .addSubcommand(foDiscordCommand.reloadSubcommand())
                .addSubcommand(foDiscordCommand.editorSubcommand())
                .addSubcommand(foDiscordCommand.resetRewardsSubcommand())
                .addSubcommand(foDiscordCommand.resetLeaderboardsSubcommand())
                .build();
        if (!adminCommand.register()) {
            throw new IllegalStateException("Command 'fodiscordbotadmin' is missing from plugin.yml");
        }
        this.commandVisibilityService = CommandVisibilityService.builder(this)
                .hideWithout(ADMIN_PERMISSION, "fodiscordbotadmin", "discordbotadmin", "fodiscord", "fodiscordbot")
                .register();
        getServer().getPluginManager().registerEvents(foDiscordCommand, this);
        registerCommand("link", new LinkCommand(this));
        registerCommand("unlink", new UnlinkCommand(this));
        registerCommand("discord", new DiscordCommand(this));
    }

    private void registerCommand(String name, Object executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Command '" + name + "' is missing from plugin.yml");
        }

        switch (executor) {
            case org.bukkit.command.TabExecutor tabExecutor -> {
                command.setExecutor(tabExecutor);
                command.setTabCompleter(tabExecutor);
            }
            case org.bukkit.command.CommandExecutor commandExecutor -> command.setExecutor(commandExecutor);
            default -> throw new IllegalArgumentException("Unsupported command executor type for " + name);
        }
    }

    private boolean shouldRestartDiscord(PluginConfig previous, PluginConfig current) {
        if (previous == null || current == null) {
            return true;
        }

        String previousGuildId = previous.normalizedGuildId();
        String currentGuildId = current.normalizedGuildId();
        return !previous.botToken().equals(current.botToken())
                || previous.shouldRunDiscordNode() != current.shouldRunDiscordNode()
                || previous.serverIpCommandEnabled() != current.serverIpCommandEnabled()
                || previous.networkEnabled() != current.networkEnabled()
                || previous.advancementEnabled() != current.advancementEnabled()
                || (previousGuildId == null ? currentGuildId != null : !previousGuildId.equals(currentGuildId));
    }
}
