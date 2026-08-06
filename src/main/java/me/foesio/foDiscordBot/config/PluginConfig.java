package me.foesio.foDiscordBot.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.foesio.foDiscordBot.model.LeaderboardDefinition;
import me.foesio.foDiscordBot.model.ProfileField;
import me.foesio.foDiscordBot.model.RankRoleMapping;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record PluginConfig(
        String botToken,
        String commandGuildId,
        String inviteUrl,
        boolean serverIpCommandEnabled,
        String serverIp,
        String chatBridgeChannelId,
        String chatBridgeWebhookName,
        String chatBridgeRelayNameFormat,
        String chatBridgeAvatarUrlTemplate,
        boolean networkEnabled,
        String gamemodeId,
        boolean primaryDiscordNode,
        Duration networkSyncInterval,
        Duration profileSnapshotCacheTtl,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        boolean mysqlUseSsl,
        int mysqlPoolSize,
        Duration mysqlConnectionTimeout,
        boolean boosterEnabled,
        String boosterRoleId,
        List<String> boosterAlwaysRewardCommands,
        List<String> boosterOneTimeRewardCommands,
        List<String> boosterRemovalCommands,
        boolean rankSyncEnabled,
        List<RankRoleMapping> rankSyncMappings,
        boolean advancementEnabled,
        int codeLength,
        Duration codeExpiry,
        Duration ingameCommandCooldown,
        Duration discordCommandCooldown,
        Duration cleanupInterval,
        boolean removeLinkMessageAfterSuccess,
        String linkedRoleId,
        List<String> linkAlwaysRewardCommands,
        List<String> linkOneTimeRewardCommands,
        List<String> linkUnlinkCommands,
        int profileColor,
        String profileFooter,
        List<ProfileField> profileFields,
        int leaderboardColor,
        Map<String, LeaderboardDefinition> leaderboards
) {

    public static PluginConfig load(FileConfiguration config) {
        ConfigurationSection linking = config.getConfigurationSection("linking");
        ConfigurationSection chatBridge = config.getConfigurationSection("chat-bridge");
        ConfigurationSection profile = config.getConfigurationSection("profile");
        ConfigurationSection leaderboardsSection = config.getConfigurationSection("leaderboards");
        ConfigurationSection boardsSection = leaderboardsSection != null
                ? leaderboardsSection.getConfigurationSection("boards")
                : null;

        ConfigurationSection network = config.getConfigurationSection("network");
        ConfigurationSection networkSync = network != null ? network.getConfigurationSection("sync") : null;
        ConfigurationSection mysql = network != null ? network.getConfigurationSection("mysql") : null;
        ConfigurationSection booster = config.getConfigurationSection("booster");
        ConfigurationSection rankSync = config.getConfigurationSection("rank-sync");
        ConfigurationSection rankSyncRanks = rankSync != null ? rankSync.getConfigurationSection("ranks") : null;

        List<ProfileField> fields = new ArrayList<>();
        List<Map<?, ?>> rawFields = profile != null ? profile.getMapList("fields") : List.of();
        for (Map<?, ?> rawField : rawFields) {
            Object rawName = rawField.containsKey("name") ? rawField.get("name") : "Field";
            Object rawValue = rawField.containsKey("value") ? rawField.get("value") : "N/A";
            Object rawInline = rawField.containsKey("inline") ? rawField.get("inline") : "false";
            Object rawSameLine = rawField.containsKey("same-line") ? rawField.get("same-line") : "false";
            String name = String.valueOf(rawName);
            String value = String.valueOf(rawValue);
            boolean inline = Boolean.parseBoolean(String.valueOf(rawInline));
            boolean sameLine = Boolean.parseBoolean(String.valueOf(rawSameLine));
            fields.add(new ProfileField(name, value, inline, sameLine));
        }
        if (fields.isEmpty()) {
            fields.add(new ProfileField(":link: Linked Discord", "{linked_discord}", false, false));
            fields.add(new ProfileField(":triangular_flag_on_post: Status", "{online_status}", false, false));
            fields.add(new ProfileField(":hammer: Kills", "%statistic_player_kills%", false, false));
        }

        String legacyLeaderboardFooter = leaderboardsSection != null ? leaderboardsSection.getString("footer", "none") : "none";
        Map<String, LeaderboardDefinition> leaderboards = new LinkedHashMap<>();
        if (boardsSection != null) {
            for (String key : boardsSection.getKeys(false)) {
                ConfigurationSection board = boardsSection.getConfigurationSection(key);
                if (board == null) {
                    continue;
                }

                String normalizedKey = key.toLowerCase(Locale.ROOT);
                leaderboards.put(normalizedKey, new LeaderboardDefinition(
                        normalizedKey,
                        board.getString("title", key),
                        List.copyOf(board.getStringList("lines")),
                        board.getString("empty-text", "No entries found."),
                        board.getString("footer", legacyLeaderboardFooter)
                ));
            }
        }

        List<RankRoleMapping> rankMappings = new ArrayList<>();
        if (rankSyncRanks != null) {
            for (String key : rankSyncRanks.getKeys(false)) {
                ConfigurationSection rank = rankSyncRanks.getConfigurationSection(key);
                if (rank == null) {
                    continue;
                }

                String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
                String permission = rank.getString("permission", "").trim();
                String roleId = rank.getString("role-id", "").trim();
                if (!normalizedKey.isBlank()) {
                    rankMappings.add(new RankRoleMapping(normalizedKey, permission, roleId));
                }
            }
        }

        long networkSyncSeconds = Math.max(5L, networkSync != null ? networkSync.getLong("interval-seconds", 30L) : 30L);
        long profileCacheSeconds = Math.max(0L, networkSync != null ? networkSync.getLong("profile-cache-seconds", 300L) : 300L);

        return new PluginConfig(
                config.getString("discord.token", "").trim(),
                config.getString("discord.command-guild-id", "").trim(),
                config.getString("discord.invite-url", "").trim(),
                config.getBoolean("server-ip.enabled", false),
                defaultIfBlank(config.getString("server-ip.ip"), "yourserverip.example"),
                config.getString("chat-bridge.channel-id", "").trim(),
                defaultIfBlank(chatBridge != null ? chatBridge.getString("webhook-name") : null, "FoDiscordBot Chat"),
                defaultIfBlank(chatBridge != null ? chatBridge.getString("relay-name-format") : null, "[{gamemode}] {player_name}"),
                defaultIfBlank(
                        chatBridge != null ? chatBridge.getString("avatar-url-template") : null,
                        "https://visage.surgeplay.com/bust/160/{player_uuid}"
                ),
                network != null && network.getBoolean("enabled", false),
                normalizeGamemode(network != null ? network.getString("gamemode-id") : null),
                network == null || network.getBoolean("primary-discord-node", true),
                Duration.ofSeconds(networkSyncSeconds),
                Duration.ofSeconds(profileCacheSeconds),
                defaultIfBlank(mysql != null ? mysql.getString("host") : null, "127.0.0.1"),
                clamp(mysql != null ? mysql.getInt("port", 3306) : 3306, 1, 65535),
                defaultIfBlank(mysql != null ? mysql.getString("database") : null, "fodiscordbot"),
                defaultIfBlank(mysql != null ? mysql.getString("username") : null, "root"),
                mysql != null ? mysql.getString("password", "") : "",
                mysql != null && mysql.getBoolean("use-ssl", false),
                clamp(mysql != null ? mysql.getInt("pool-size", 8) : 8, 2, 50),
                Duration.ofSeconds(Math.min(120L, Math.max(5L, mysql != null ? mysql.getLong("connection-timeout-seconds", 30L) : 30L))),
                booster != null && booster.getBoolean("enabled", false),
                booster != null ? booster.getString("role-id", "").trim() : "",
                List.copyOf(config.getStringList("booster.always-reward-commands")),
                List.copyOf(boosterOneTimeRewardCommands(config)),
                List.copyOf(config.getStringList("booster.removal-commands")),
                rankSync != null && rankSync.getBoolean("enabled", false),
                List.copyOf(rankMappings),
                advancementEnabled(config),
                clamp(config.getInt("linking.code-length", 6), 4, 12),
                Duration.ofSeconds(Math.max(60L, linking != null ? linking.getLong("code-expiry-seconds", 600L) : 600L)),
                Duration.ofSeconds(Math.max(1L, linking != null ? linking.getLong("ingame-command-cooldown-seconds", 30L) : 30L)),
                Duration.ofSeconds(Math.max(1L, linking != null ? linking.getLong("discord-command-cooldown-seconds", 3L) : 3L)),
                Duration.ofMinutes(Math.max(1L, linking != null ? linking.getLong("cleanup-interval-minutes", 5L) : 5L)),
                linking != null && linking.getBoolean("remove-link-message-after-success", false),
                defaultIfBlank(linking != null ? linking.getString("linked-role-id") : null, "none"),
                List.copyOf(config.getStringList("linking.always-reward-commands")),
                List.copyOf(linkOneTimeRewardCommands(config)),
                List.copyOf(config.getStringList("linking.unlink-commands")),
                parseColor(profile != null ? profile.getString("embed-color", "#03fc88") : "#03fc88"),
                profile != null ? profile.getString("footer", "FoDiscordBot") : "FoDiscordBot",
                List.copyOf(fields),
                parseColor(leaderboardsSection != null ? leaderboardsSection.getString("embed-color", "#03fc88") : "#03fc88"),
                Map.copyOf(leaderboards)
        );
    }

    public String normalizedGuildId() {
        return commandGuildId.isBlank() ? null : commandGuildId;
    }

    public String normalizedChatBridgeChannelId() {
        return chatBridgeChannelId.isBlank() ? null : chatBridgeChannelId;
    }

    public boolean chatBridgeEnabled() {
        return normalizedChatBridgeChannelId() != null;
    }

    public String normalizedGamemodeId() {
        return normalizeGamemode(gamemodeId);
    }

    public boolean hasConfiguredBotToken() {
        return botToken != null && !botToken.isBlank() && !"PUT_BOT_TOKEN_HERE".equalsIgnoreCase(botToken);
    }

    public String normalizedLinkedRoleId() {
        if (linkedRoleId == null || linkedRoleId.isBlank() || "none".equalsIgnoreCase(linkedRoleId.trim())) {
            return null;
        }
        return linkedRoleId.trim();
    }

    public boolean shouldRunDiscordNode() {
        return hasConfiguredBotToken() && (!networkEnabled || primaryDiscordNode);
    }

    public String mysqlJdbcUrl() {
        return "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlDatabase
                + "?useSSL=" + mysqlUseSsl
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseColor(String color) {
        String normalized = color == null || color.isBlank() ? "#FFFFFF" : color.trim();
        if (!normalized.startsWith("#")) {
            normalized = "#" + normalized;
        }
        return Integer.decode(normalized);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> boosterOneTimeRewardCommands(FileConfiguration config) {
        List<String> commands = config.getStringList("booster.one-time-reward-commands");
        if (!commands.isEmpty()) {
            return commands;
        }
        return config.getStringList("booster.reward-commands");
    }

    private static List<String> linkOneTimeRewardCommands(FileConfiguration config) {
        List<String> commands = config.getStringList("linking.one-time-reward-commands");
        if (!commands.isEmpty()) {
            return commands;
        }
        return config.getStringList("linking.reward-commands");
    }

    private static boolean advancementEnabled(FileConfiguration config) {
        if (config.contains("advancement.enabled", true)) {
            return config.getBoolean("advancement.enabled", false);
        }
        return config.getBoolean("advancements.enabled", false);
    }

    private static String normalizeGamemode(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
