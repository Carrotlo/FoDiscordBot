package me.foesio.foDiscordBot.service;

import java.io.File;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.AdvancementEntryView;
import me.foesio.foDiscordBot.model.AdvancementProfileView;
import me.foesio.foDiscordBot.model.AdvancementTabView;
import me.foesio.foDiscordBot.model.DiscordUserSnapshot;
import me.foesio.foDiscordBot.model.LeaderboardView;
import me.foesio.foDiscordBot.model.LinkCompletionResult;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.model.PendingLinkCode;
import me.foesio.foDiscordBot.model.ProfileCard;
import me.foesio.foDiscordBot.model.ProfileField;
import me.foesio.foDiscordBot.model.UnlinkResult;

public final class LinkRepository {

    private static final String CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ADVANCEMENT_BATCH_SIZE = 500;

    private final FoDiscordBot plugin;
    private final String sqliteJdbcUrl;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile HikariDataSource dataSource;
    private volatile String dataSourceKey;

    public LinkRepository(FoDiscordBot plugin) {
        this.plugin = plugin;
        File databaseFile = new File(plugin.getDataFolder(), "links.db");
        this.sqliteJdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
    }

    public synchronized void initialize() throws SQLException {
        refreshDataSource();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_links (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(32) NOT NULL,
                        discord_user_id VARCHAR(32) UNIQUE,
                        discord_username VARCHAR(64),
                        discord_display_name VARCHAR(64),
                        linked_at BIGINT,
                        updated_at BIGINT NOT NULL,
                        rewards_claimed INTEGER NOT NULL DEFAULT 0,
                        rewards_claimed_at BIGINT
                    )
                    """);
            createIndexIfMissing(statement, """
                    CREATE INDEX idx_player_links_name
                    ON player_links(player_name)
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pending_link_codes (
                        code VARCHAR(16) PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL UNIQUE,
                        player_name VARCHAR(32) NOT NULL,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL
                    )
                    """);
            createIndexIfMissing(statement, """
                    CREATE INDEX idx_pending_link_codes_expires_at
                    ON pending_link_codes(expires_at)
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS booster_reward_claims (
                        player_uuid VARCHAR(36) NOT NULL,
                        gamemode_id VARCHAR(64) NOT NULL,
                        reward_commands_snapshot TEXT,
                        next_reward_command_index INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        claimed_at BIGINT,
                        current_booster INTEGER NOT NULL DEFAULT 0,
                        active_discord_user_id VARCHAR(32),
                        active_discord_username VARCHAR(64),
                        active_discord_display_name VARCHAR(64),
                        active_link_broken INTEGER NOT NULL DEFAULT 0,
                        always_commands_snapshot TEXT,
                        next_always_command_index INTEGER NOT NULL DEFAULT 0,
                        removal_commands_snapshot TEXT,
                        next_removal_command_index INTEGER NOT NULL DEFAULT 0,
                        removal_claimed_at BIGINT,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, gamemode_id)
                    )
                    """);
            addColumnIfMissing(connection, "booster_reward_claims", "current_booster", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "booster_reward_claims", "active_discord_user_id", "VARCHAR(32)");
            addColumnIfMissing(connection, "booster_reward_claims", "active_discord_username", "VARCHAR(64)");
            addColumnIfMissing(connection, "booster_reward_claims", "active_discord_display_name", "VARCHAR(64)");
            addColumnIfMissing(connection, "booster_reward_claims", "active_link_broken", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "booster_reward_claims", "always_commands_snapshot", "TEXT");
            addColumnIfMissing(connection, "booster_reward_claims", "next_always_command_index", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "booster_reward_claims", "removal_commands_snapshot", "TEXT");
            addColumnIfMissing(connection, "booster_reward_claims", "next_removal_command_index", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(connection, "booster_reward_claims", "removal_claimed_at", "BIGINT");

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS booster_one_time_claims (
                        discord_user_id VARCHAR(32) NOT NULL,
                        gamemode_id VARCHAR(64) NOT NULL,
                        reward_commands_snapshot TEXT,
                        next_reward_command_index INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        claimed_at BIGINT,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (discord_user_id, gamemode_id)
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS network_reward_claims (
                        player_uuid VARCHAR(36) NOT NULL,
                        gamemode_id VARCHAR(64) NOT NULL,
                        reward_commands_snapshot TEXT,
                        next_reward_command_index INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        claimed_at BIGINT,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, gamemode_id)
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_reward_states (
                        player_uuid VARCHAR(36) NOT NULL,
                        gamemode_id VARCHAR(64) NOT NULL,
                        current_link INTEGER NOT NULL DEFAULT 0,
                        active_discord_user_id VARCHAR(32),
                        active_discord_username VARCHAR(64),
                        active_discord_display_name VARCHAR(64),
                        always_commands_snapshot TEXT,
                        next_always_command_index INTEGER NOT NULL DEFAULT 0,
                        unlink_commands_snapshot TEXT,
                        next_unlink_command_index INTEGER NOT NULL DEFAULT 0,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, gamemode_id)
                    )
                    """);

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_one_time_claims (
                        discord_user_id VARCHAR(32) NOT NULL,
                        gamemode_id VARCHAR(64) NOT NULL,
                        reward_commands_snapshot TEXT,
                        next_reward_command_index INTEGER NOT NULL DEFAULT 0,
                        claimed INTEGER NOT NULL DEFAULT 0,
                        claimed_at BIGINT,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (discord_user_id, gamemode_id)
                    )
                    """);

            if (plugin.getPluginConfig().networkEnabled()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS network_gamemodes (
                            gamemode_id VARCHAR(64) PRIMARY KEY,
                            updated_at BIGINT NOT NULL
                        )
                        """);

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS network_gamemode_boards (
                            gamemode_id VARCHAR(64) NOT NULL,
                            board_alias VARCHAR(64) NOT NULL,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (gamemode_id, board_alias)
                        )
                        """);

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS network_leaderboard_snapshots (
                            gamemode_id VARCHAR(64) NOT NULL,
                            board_alias VARCHAR(64) NOT NULL,
                            title VARCHAR(128) NOT NULL,
                            color INTEGER NOT NULL,
                            lines_blob TEXT NOT NULL,
                            footer TEXT,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (gamemode_id, board_alias)
                        )
                        """);
                addColumnIfMissing(connection, "network_leaderboard_snapshots", "footer", "TEXT");

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS network_profile_snapshots (
                            player_uuid VARCHAR(36) NOT NULL,
                            gamemode_id VARCHAR(64) NOT NULL,
                            player_name VARCHAR(32) NOT NULL,
                            thumbnail_url TEXT,
                            color INTEGER NOT NULL,
                            footer TEXT,
                            updated_at BIGINT NOT NULL,
                            PRIMARY KEY (player_uuid, gamemode_id)
                        )
                        """);
                createIndexIfMissing(statement, """
                        CREATE INDEX idx_network_profile_name
                        ON network_profile_snapshots(player_name)
                        """);

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS network_profile_snapshot_fields (
                            player_uuid VARCHAR(36) NOT NULL,
                            gamemode_id VARCHAR(64) NOT NULL,
                            position INTEGER NOT NULL,
                            field_name TEXT NOT NULL,
                            field_value TEXT NOT NULL,
                            inline_field INTEGER NOT NULL,
                            same_line INTEGER NOT NULL,
                            PRIMARY KEY (player_uuid, gamemode_id, position)
                        )
                        """);

                if (plugin.getPluginConfig().advancementEnabled() || plugin.getPluginConfig().networkEnabled()) {
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS network_advancement_gamemodes (
                                gamemode_id VARCHAR(64) PRIMARY KEY,
                                plugin_version VARCHAR(32),
                                updated_at BIGINT NOT NULL
                            )
                            """);

                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS network_advancement_players (
                                gamemode_id VARCHAR(64) NOT NULL,
                                player_uuid VARCHAR(36) NOT NULL,
                                player_name VARCHAR(32) NOT NULL,
                                player_name_lower VARCHAR(32),
                                plugin_version VARCHAR(32),
                                points INTEGER NOT NULL DEFAULT 0,
                                completed INTEGER NOT NULL DEFAULT 0,
                                total INTEGER NOT NULL DEFAULT 0,
                                updated_at BIGINT NOT NULL,
                                PRIMARY KEY (gamemode_id, player_uuid)
                            )
                            """);
                    addColumnIfMissing(connection, "network_advancement_players", "player_name_lower", "VARCHAR(32)");
                    backfillAdvancementPlayerNameLower(connection);
                    createIndexIfMissing(statement, """
                            CREATE INDEX idx_network_advancement_player_name
                            ON network_advancement_players(gamemode_id, player_name)
                            """);
                    createIndexIfMissing(statement, """
                            CREATE INDEX idx_network_advancement_player_name_lower
                            ON network_advancement_players(gamemode_id, player_name_lower)
                            """);

                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS network_advancement_entries (
                                gamemode_id VARCHAR(64) NOT NULL,
                                player_uuid VARCHAR(36) NOT NULL,
                                tab_id VARCHAR(64) NOT NULL,
                                tab_title TEXT NOT NULL,
                                tab_description TEXT,
                                tab_icon VARCHAR(64),
                                tab_background TEXT,
                                tab_completed INTEGER NOT NULL DEFAULT 0,
                                tab_total INTEGER NOT NULL DEFAULT 0,
                                tab_position INTEGER NOT NULL DEFAULT 0,
                                advancement_id VARCHAR(64) NOT NULL,
                                full_id VARCHAR(128) NOT NULL,
                                title TEXT NOT NULL,
                                description TEXT,
                                icon VARCHAR(64),
                                frame VARCHAR(32),
                                current_progress INTEGER NOT NULL DEFAULT 0,
                                required_progress INTEGER NOT NULL DEFAULT 1,
                                completed INTEGER NOT NULL DEFAULT 0,
                                visible INTEGER NOT NULL DEFAULT 0,
                                hidden INTEGER NOT NULL DEFAULT 0,
                                points INTEGER NOT NULL DEFAULT 0,
                                position INTEGER NOT NULL DEFAULT 0,
                                updated_at BIGINT NOT NULL,
                                PRIMARY KEY (gamemode_id, player_uuid, full_id)
                            )
                            """);
                    createIndexIfMissing(statement, """
                            CREATE INDEX idx_network_advancement_entries_player_position
                            ON network_advancement_entries(gamemode_id, player_uuid, tab_position, position)
                            """);
                }

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS network_chat_relay_queue (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            gamemode_id VARCHAR(64) NOT NULL,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(32) NOT NULL,
                            avatar_url TEXT,
                            message TEXT NOT NULL,
                            created_at BIGINT NOT NULL,
                            PRIMARY KEY (id)
                        )
                        """);
                addColumnIfMissing(connection, "network_chat_relay_queue", "avatar_url", "TEXT");
                createIndexIfMissing(statement, """
                        CREATE INDEX idx_network_chat_relay_created_at
                        ON network_chat_relay_queue(created_at)
                        """);
            }

            migrateLegacyRewardClaimState(connection);
        }
    }

    private void createIndexIfMissing(Statement statement, String createIndexSql) throws SQLException {
        try {
            statement.executeUpdate(createIndexSql);
        } catch (SQLException exception) {
            if (isIndexAlreadyExists(exception)) {
                return;
            }
            throw exception;
        }
    }

    private void addColumnIfMissing(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        } catch (SQLException exception) {
            if (isColumnAlreadyExists(exception)) {
                return;
            }
            throw exception;
        }
    }

    private void backfillAdvancementPlayerNameLower(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE network_advancement_players
                SET player_name_lower = LOWER(player_name)
                WHERE player_name_lower IS NULL OR player_name_lower = ''
                """)) {
            statement.executeUpdate();
        }
    }

    private void migrateLegacyRewardClaimState(Connection connection) throws SQLException {
        migrateLegacyLinkRewardClaims(connection);
        migrateLegacyBoosterRewardClaims(connection);
    }

    private void migrateLegacyLinkRewardClaims(Connection connection) throws SQLException {
        String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
        List<LegacyPlayerRewardClaim> playerClaims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, rewards_claimed_at, updated_at
                FROM player_links
                WHERE rewards_claimed = 1
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long claimedAt = nullableEpoch(resultSet, "rewards_claimed_at");
                if (claimedAt <= 0L) {
                    claimedAt = Math.max(0L, resultSet.getLong("updated_at"));
                }
                playerClaims.add(new LegacyPlayerRewardClaim(
                        resultSet.getString("player_uuid"),
                        claimedAt
                ));
            }
        }

        for (LegacyPlayerRewardClaim claim : playerClaims) {
            if (rewardClaimExists(connection, "network_reward_claims", "player_uuid", claim.playerUuid(), gamemodeId)) {
                continue;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO network_reward_claims (
                        player_uuid, gamemode_id, reward_commands_snapshot,
                        next_reward_command_index, claimed, claimed_at, updated_at
                    ) VALUES (?, ?, NULL, 0, 1, ?, ?)
                    """)) {
                statement.setString(1, claim.playerUuid());
                statement.setString(2, gamemodeId);
                statement.setLong(3, claim.claimedAtEpoch());
                statement.setLong(4, claim.claimedAtEpoch());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
            }
        }

        List<LegacyLinkRewardClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT n.player_uuid, n.gamemode_id, n.reward_commands_snapshot,
                       n.next_reward_command_index, n.claimed, n.claimed_at,
                       n.updated_at, p.discord_user_id
                FROM network_reward_claims n
                LEFT JOIN player_links p ON p.player_uuid = n.player_uuid
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                claims.add(new LegacyLinkRewardClaim(
                        resultSet.getString("player_uuid"),
                        resultSet.getString("gamemode_id"),
                        resultSet.getString("reward_commands_snapshot"),
                        Math.max(0, resultSet.getInt("next_reward_command_index")),
                        resultSet.getInt("claimed") == 1,
                        nullableEpoch(resultSet, "claimed_at"),
                        Math.max(0L, resultSet.getLong("updated_at")),
                        resultSet.getString("discord_user_id")
                ));
            }
        }

        for (LegacyLinkRewardClaim claim : claims) {
            if (!claim.hasLinkedDiscord() || (!claim.claimed() && claim.nextCommandIndex() <= 0 && !hasText(claim.encodedSnapshot()))
                    || rewardClaimExists(connection, "link_one_time_claims", "discord_user_id", claim.discordUserId(), claim.gamemodeId())) {
                continue;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO link_one_time_claims (
                        discord_user_id, gamemode_id, reward_commands_snapshot,
                        next_reward_command_index, claimed, claimed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, claim.discordUserId());
                statement.setString(2, normalizeGamemode(claim.gamemodeId()));
                setNullableString(statement, 3, claim.claimed() ? null : claim.encodedSnapshot());
                statement.setInt(4, claim.claimed() ? 0 : claim.nextCommandIndex());
                statement.setInt(5, claim.claimed() ? 1 : 0);
                if (claim.claimed()) {
                    statement.setLong(6, claim.claimedAtOrUpdatedAt());
                } else {
                    statement.setNull(6, Types.BIGINT);
                }
                statement.setLong(7, claim.updatedAtEpoch());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO link_reward_states (
                    player_uuid, gamemode_id, current_link,
                    active_discord_user_id, active_discord_username,
                    active_discord_display_name, always_commands_snapshot,
                    next_always_command_index, unlink_commands_snapshot,
                    next_unlink_command_index, updated_at
                )
                SELECT player_uuid, ?, 1, discord_user_id, discord_username,
                       discord_display_name, NULL, 0, NULL, 0, updated_at
                FROM player_links
                WHERE discord_user_id IS NOT NULL AND discord_user_id <> ''
                  AND NOT EXISTS (
                      SELECT 1
                      FROM link_reward_states s
                      WHERE s.player_uuid = player_links.player_uuid
                        AND s.gamemode_id = ?
                  )
                """)) {
            statement.setString(1, gamemodeId);
            statement.setString(2, gamemodeId);
            statement.executeUpdate();
        }
    }

    private void migrateLegacyBoosterRewardClaims(Connection connection) throws SQLException {
        List<LegacyBoosterRewardClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT b.player_uuid, b.gamemode_id, b.reward_commands_snapshot,
                       b.next_reward_command_index, b.claimed, b.claimed_at,
                       b.current_booster, b.updated_at,
                       b.active_discord_user_id,
                       p.discord_user_id, p.discord_username, p.discord_display_name
                FROM booster_reward_claims b
                LEFT JOIN player_links p ON p.player_uuid = b.player_uuid
                """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                claims.add(new LegacyBoosterRewardClaim(
                        resultSet.getString("player_uuid"),
                        resultSet.getString("gamemode_id"),
                        resultSet.getString("reward_commands_snapshot"),
                        Math.max(0, resultSet.getInt("next_reward_command_index")),
                        resultSet.getInt("claimed") == 1,
                        nullableEpoch(resultSet, "claimed_at"),
                        resultSet.getInt("current_booster") == 1,
                        Math.max(0L, resultSet.getLong("updated_at")),
                        resultSet.getString("active_discord_user_id"),
                        resultSet.getString("discord_user_id"),
                        resultSet.getString("discord_username"),
                        resultSet.getString("discord_display_name")
                ));
            }
        }

        for (LegacyBoosterRewardClaim claim : claims) {
            if (claim.hasLinkedDiscord() && (claim.claimed() || claim.nextCommandIndex() > 0 || hasText(claim.encodedSnapshot()))
                    && !rewardClaimExists(connection, "booster_one_time_claims", "discord_user_id", claim.discordUserId(), claim.gamemodeId())) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO booster_one_time_claims (
                            discord_user_id, gamemode_id, reward_commands_snapshot,
                            next_reward_command_index, claimed, claimed_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, claim.discordUserId());
                    statement.setString(2, normalizeGamemode(claim.gamemodeId()));
                    setNullableString(statement, 3, claim.claimed() ? null : claim.encodedSnapshot());
                    statement.setInt(4, claim.claimed() ? 0 : claim.nextCommandIndex());
                    statement.setInt(5, claim.claimed() ? 1 : 0);
                    if (claim.claimed()) {
                        statement.setLong(6, claim.claimedAtOrUpdatedAt());
                    } else {
                        statement.setNull(6, Types.BIGINT);
                    }
                    statement.setLong(7, claim.updatedAtEpoch());
                    statement.executeUpdate();
                } catch (SQLException exception) {
                    if (!isConstraintViolation(exception)) {
                        throw exception;
                    }
                }
            }

            if (claim.currentBooster() && !hasText(claim.activeDiscordUserId()) && claim.hasLinkedDiscord()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE booster_reward_claims
                        SET active_discord_user_id = ?,
                            active_discord_username = ?,
                            active_discord_display_name = ?,
                            active_link_broken = 0,
                            updated_at = ?
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, claim.discordUserId());
                    setNullableString(statement, 2, claim.discordUsername());
                    setNullableString(statement, 3, claim.discordDisplayName());
                    statement.setLong(4, claim.updatedAtEpoch());
                    statement.setString(5, claim.playerUuid());
                    statement.setString(6, normalizeGamemode(claim.gamemodeId()));
                    statement.executeUpdate();
                }
            }
        }
    }

    private boolean rewardClaimExists(
            Connection connection,
            String tableName,
            String idColumn,
            String idValue,
            String gamemodeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM %s
                WHERE %s = ? AND gamemode_id = ?
                LIMIT 1
                """.formatted(tableName, idColumn))) {
            statement.setString(1, idValue);
            statement.setString(2, normalizeGamemode(gamemodeId));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private long nullableEpoch(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? 0L : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    public Optional<LinkedAccount> findByPlayerUuid(UUID playerUuid) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_uuid, player_name, discord_user_id, discord_username,
                            discord_display_name, linked_at, updated_at,
                            rewards_claimed, rewards_claimed_at
                     FROM player_links
                     WHERE player_uuid = ?
                     """)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        }
    }

    public Map<UUID, LinkedAccount> findByPlayerUuids(Collection<UUID> playerUuids) throws SQLException {
        if (playerUuids == null || playerUuids.isEmpty()) {
            return Map.of();
        }

        Map<UUID, LinkedAccount> accounts = new LinkedHashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_uuid, player_name, discord_user_id, discord_username,
                            discord_display_name, linked_at, updated_at,
                            rewards_claimed, rewards_claimed_at
                     FROM player_links
                     WHERE player_uuid = ?
                     """)) {
            for (UUID playerUuid : playerUuids) {
                if (playerUuid == null) {
                    continue;
                }
                statement.setString(1, playerUuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        LinkedAccount account = readAccount(resultSet);
                        accounts.put(account.playerUuid(), account);
                    }
                }
            }
        }
        return Map.copyOf(accounts);
    }

    public Optional<LinkedAccount> findByPlayerName(String playerName) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_uuid, player_name, discord_user_id, discord_username,
                            discord_display_name, linked_at, updated_at,
                            rewards_claimed, rewards_claimed_at
                     FROM player_links
                     WHERE LOWER(player_name) = LOWER(?)
                     ORDER BY updated_at DESC
                     LIMIT 1
                     """)) {
            statement.setString(1, playerName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<LinkedAccount> findLinkedByDiscordId(String discordUserId) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_uuid, player_name, discord_user_id, discord_username,
                            discord_display_name, linked_at, updated_at,
                            rewards_claimed, rewards_claimed_at
                     FROM player_links
                     WHERE discord_user_id = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, discordUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        }
    }

    public PendingLinkCode createOrReplacePendingCode(UUID playerUuid, String playerName, Instant now, Duration expiry, int codeLength)
            throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                deletePendingCodeForPlayer(connection, playerUuid);
                upsertPlayerSnapshot(connection, playerUuid, playerName, now);

                Instant expiresAt = now.plus(expiry);
                for (int attempt = 0; attempt < 20; attempt++) {
                    String code = generateCode(codeLength);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO pending_link_codes(code, player_uuid, player_name, created_at, expires_at)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        statement.setString(1, code);
                        statement.setString(2, playerUuid.toString());
                        statement.setString(3, playerName);
                        statement.setLong(4, now.getEpochSecond());
                        statement.setLong(5, expiresAt.getEpochSecond());
                        statement.executeUpdate();
                        connection.commit();
                        return new PendingLinkCode(code, playerUuid, playerName, now, expiresAt);
                    } catch (SQLException exception) {
                        if (!isConstraintViolation(exception)) {
                            throw exception;
                        }
                    }
                }

                connection.rollback();
                throw new SQLException("Failed to generate a unique link code after 20 attempts.");
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public LinkCompletionResult completeLink(String code, DiscordUserSnapshot userSnapshot, Instant now, List<String> ignoredRewardCommands)
            throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                PendingLinkCode pendingLinkCode = getPendingCode(connection, code).orElse(null);
                if (pendingLinkCode == null) {
                    connection.rollback();
                    return new LinkCompletionResult(LinkCompletionResult.Status.INVALID_CODE, null, false);
                }

                if (!pendingLinkCode.expiresAt().isAfter(now)) {
                    deletePendingCode(connection, code);
                    connection.commit();
                    return new LinkCompletionResult(LinkCompletionResult.Status.EXPIRED_CODE, null, false);
                }

                LinkedAccount currentPlayerState = getLinkedAccount(connection, pendingLinkCode.playerUuid()).orElse(null);
                if (currentPlayerState != null && currentPlayerState.isLinked()) {
                    connection.rollback();
                    return new LinkCompletionResult(LinkCompletionResult.Status.PLAYER_ALREADY_LINKED, currentPlayerState, false);
                }

                LinkedAccount discordState = getLinkedAccountByDiscord(connection, userSnapshot.userId()).orElse(null);
                if (discordState != null && !discordState.playerUuid().equals(pendingLinkCode.playerUuid())) {
                    connection.rollback();
                    return new LinkCompletionResult(LinkCompletionResult.Status.DISCORD_ALREADY_LINKED, discordState, false);
                }

                Instant linkedAt = currentPlayerState != null && currentPlayerState.linkedAt() != null
                        ? currentPlayerState.linkedAt()
                        : now;

                if (currentPlayerState == null) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO player_links (
                                player_uuid, player_name, discord_user_id, discord_username,
                                discord_display_name, linked_at, updated_at,
                                rewards_claimed, rewards_claimed_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL)
                            """)) {
                        statement.setString(1, pendingLinkCode.playerUuid().toString());
                        statement.setString(2, pendingLinkCode.playerName());
                        statement.setString(3, userSnapshot.userId());
                        statement.setString(4, userSnapshot.username());
                        statement.setString(5, userSnapshot.displayName());
                        statement.setLong(6, linkedAt.getEpochSecond());
                        statement.setLong(7, now.getEpochSecond());
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE player_links
                            SET player_name = ?,
                                discord_user_id = ?,
                                discord_username = ?,
                                discord_display_name = ?,
                                linked_at = ?,
                                updated_at = ?,
                                rewards_claimed = ?,
                                rewards_claimed_at = ?
                            WHERE player_uuid = ?
                            """)) {
                        statement.setString(1, pendingLinkCode.playerName());
                        statement.setString(2, userSnapshot.userId());
                        statement.setString(3, userSnapshot.username());
                        statement.setString(4, userSnapshot.displayName());
                        statement.setLong(5, linkedAt.getEpochSecond());
                        statement.setLong(6, now.getEpochSecond());
                        statement.setInt(7, currentPlayerState.rewardsClaimed() ? 1 : 0);
                        if (currentPlayerState.rewardsClaimedAt() != null) {
                            statement.setLong(8, currentPlayerState.rewardsClaimedAt().getEpochSecond());
                        } else {
                            statement.setNull(8, Types.BIGINT);
                        }
                        statement.setString(9, pendingLinkCode.playerUuid().toString());
                        statement.executeUpdate();
                    }
                }

                deletePendingCode(connection, code);
                connection.commit();

                LinkedAccount linkedAccount = new LinkedAccount(
                        pendingLinkCode.playerUuid(),
                        pendingLinkCode.playerName(),
                        userSnapshot.userId(),
                        userSnapshot.username(),
                        userSnapshot.displayName(),
                        linkedAt,
                        now,
                        false,
                        null
                );
                return new LinkCompletionResult(LinkCompletionResult.Status.SUCCESS, linkedAccount, false);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public UnlinkResult unlinkPlayer(UUID playerUuid, String playerName, Instant now) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                LinkedAccount currentState = getLinkedAccount(connection, playerUuid).orElse(null);
                if (currentState == null || !currentState.isLinked()) {
                    upsertPlayerSnapshot(connection, playerUuid, playerName, now);
                    deletePendingCodeForPlayer(connection, playerUuid);
                    connection.commit();
                    return new UnlinkResult(UnlinkResult.Status.NOT_LINKED, currentState);
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE player_links
                        SET player_name = ?,
                            discord_user_id = NULL,
                            discord_username = NULL,
                            discord_display_name = NULL,
                            updated_at = ?
                        WHERE player_uuid = ?
                        """)) {
                    statement.setString(1, playerName);
                    statement.setLong(2, now.getEpochSecond());
                    statement.setString(3, playerUuid.toString());
                    statement.executeUpdate();
                }

                deletePendingCodeForPlayer(connection, playerUuid);
                connection.commit();
                return new UnlinkResult(UnlinkResult.Status.SUCCESS, currentState);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void updatePlayerSnapshot(UUID playerUuid, String playerName, Instant now) throws SQLException {
        try (Connection connection = openConnection()) {
            upsertPlayerSnapshot(connection, playerUuid, playerName, now);
        }
    }

    public Optional<BoosterRewardState> findBoosterRewardState(
            UUID playerUuid,
            String gamemodeId,
            List<String> fallbackAlwaysCommands,
            List<String> fallbackRemovalCommands
    )
            throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT current_booster,
                            active_discord_user_id, active_discord_username, active_discord_display_name,
                            active_link_broken,
                            always_commands_snapshot, next_always_command_index,
                            removal_commands_snapshot, next_removal_command_index
                     FROM booster_reward_claims
                     WHERE player_uuid = ? AND gamemode_id = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, normalizedGamemode);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> alwaysFallback = sanitizeRewardCommands(fallbackAlwaysCommands);
                List<String> removalFallback = sanitizeRewardCommands(fallbackRemovalCommands);
                if (!resultSet.next()) {
                    return Optional.of(new BoosterRewardState(
                            false,
                            null,
                            null,
                            null,
                            false,
                            alwaysFallback,
                            encodeRewardCommands(alwaysFallback),
                            0,
                            removalFallback,
                            encodeRewardCommands(removalFallback),
                            0
                    ));
                }

                String encodedAlwaysSnapshot = resultSet.getString("always_commands_snapshot");
                List<String> alwaysCommands = decodeRewardCommands(encodedAlwaysSnapshot);
                if (alwaysCommands.isEmpty()) {
                    alwaysCommands = alwaysFallback;
                    encodedAlwaysSnapshot = encodeRewardCommands(alwaysCommands);
                }
                int nextAlwaysIndex = Math.max(0, resultSet.getInt("next_always_command_index"));
                if (nextAlwaysIndex > alwaysCommands.size()) {
                    nextAlwaysIndex = alwaysCommands.size();
                }

                String encodedRemovalSnapshot = resultSet.getString("removal_commands_snapshot");
                List<String> removalCommands = decodeRewardCommands(encodedRemovalSnapshot);
                if (removalCommands.isEmpty()) {
                    removalCommands = removalFallback;
                    encodedRemovalSnapshot = encodeRewardCommands(removalCommands);
                }
                int nextRemovalIndex = Math.max(0, resultSet.getInt("next_removal_command_index"));
                if (nextRemovalIndex > removalCommands.size()) {
                    nextRemovalIndex = removalCommands.size();
                }

                return Optional.of(new BoosterRewardState(
                        resultSet.getInt("current_booster") == 1,
                        resultSet.getString("active_discord_user_id"),
                        resultSet.getString("active_discord_username"),
                        resultSet.getString("active_discord_display_name"),
                        resultSet.getInt("active_link_broken") == 1,
                        alwaysCommands,
                        encodedAlwaysSnapshot,
                        nextAlwaysIndex,
                        removalCommands,
                        encodedRemovalSnapshot,
                        nextRemovalIndex
                ));
            }
        }
    }

    public void saveBoosterRewardState(
            UUID playerUuid,
            String gamemodeId,
            BoosterRewardState state,
            Instant now
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE booster_reward_claims
                    SET current_booster = ?,
                        active_discord_user_id = ?,
                        active_discord_username = ?,
                        active_discord_display_name = ?,
                        active_link_broken = ?,
                        always_commands_snapshot = ?,
                        next_always_command_index = ?,
                        removal_commands_snapshot = ?,
                        next_removal_command_index = ?,
                        removal_claimed_at = ?,
                        updated_at = ?
                    WHERE player_uuid = ? AND gamemode_id = ?
                    """)) {
                statement.setInt(1, state.currentBooster() ? 1 : 0);
                setNullableString(statement, 2, state.activeDiscordUserId());
                setNullableString(statement, 3, state.activeDiscordUsername());
                setNullableString(statement, 4, state.activeDiscordDisplayName());
                statement.setInt(5, state.activeLinkBroken() ? 1 : 0);
                setNullableString(statement, 6, state.encodedAlwaysCommandSnapshot());
                statement.setInt(7, Math.max(0, state.nextAlwaysCommandIndex()));
                setNullableString(statement, 8, state.encodedRemovalCommandSnapshot());
                statement.setInt(9, Math.max(0, state.nextRemovalCommandIndex()));
                statement.setNull(10, Types.BIGINT);
                statement.setLong(11, now.getEpochSecond());
                statement.setString(12, playerUuid.toString());
                statement.setString(13, normalizedGamemode);
                updated = statement.executeUpdate();
            }

            if (updated > 0) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO booster_reward_claims (
                        player_uuid, gamemode_id, current_booster,
                        active_discord_user_id, active_discord_username,
                        active_discord_display_name, active_link_broken,
                        always_commands_snapshot,
                        next_always_command_index, removal_commands_snapshot,
                        next_removal_command_index, removal_claimed_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, normalizedGamemode);
                statement.setInt(3, state.currentBooster() ? 1 : 0);
                setNullableString(statement, 4, state.activeDiscordUserId());
                setNullableString(statement, 5, state.activeDiscordUsername());
                setNullableString(statement, 6, state.activeDiscordDisplayName());
                statement.setInt(7, state.activeLinkBroken() ? 1 : 0);
                setNullableString(statement, 8, state.encodedAlwaysCommandSnapshot());
                statement.setInt(9, Math.max(0, state.nextAlwaysCommandIndex()));
                setNullableString(statement, 10, state.encodedRemovalCommandSnapshot());
                statement.setInt(11, Math.max(0, state.nextRemovalCommandIndex()));
                statement.setNull(12, Types.BIGINT);
                statement.setLong(13, now.getEpochSecond());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
                saveBoosterRewardState(playerUuid, normalizedGamemode, state, now);
            }
        }
    }

    public Optional<RewardClaimState> findBoosterOneTimeRewardState(
            String discordUserId,
            String gamemodeId,
            List<String> fallbackRewardCommands
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT reward_commands_snapshot, next_reward_command_index, claimed
                     FROM booster_one_time_claims
                     WHERE discord_user_id = ? AND gamemode_id = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, discordUserId);
            statement.setString(2, normalizedGamemode);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> fallback = sanitizeRewardCommands(fallbackRewardCommands);
                if (!resultSet.next()) {
                    String encoded = encodeRewardCommands(fallback);
                    return Optional.of(new RewardClaimState(fallback, encoded, 0, false));
                }

                String encodedSnapshot = resultSet.getString("reward_commands_snapshot");
                List<String> commands = decodeRewardCommands(encodedSnapshot);
                if (commands.isEmpty()) {
                    commands = fallback;
                    encodedSnapshot = encodeRewardCommands(commands);
                }

                int nextIndex = Math.max(0, resultSet.getInt("next_reward_command_index"));
                if (nextIndex > commands.size()) {
                    nextIndex = commands.size();
                }

                boolean claimed = resultSet.getInt("claimed") == 1;
                return Optional.of(new RewardClaimState(commands, encodedSnapshot, nextIndex, claimed));
            }
        }
    }

    public void updateBoosterOneTimeRewardProgress(
            String discordUserId,
            String gamemodeId,
            String encodedSnapshot,
            int nextCommandIndex,
            boolean claimed,
            Instant now
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE booster_one_time_claims
                    SET reward_commands_snapshot = ?,
                        next_reward_command_index = ?,
                        claimed = ?,
                        claimed_at = ?,
                        updated_at = ?
                    WHERE discord_user_id = ? AND gamemode_id = ?
                    """)) {
                setNullableString(statement, 1, encodedSnapshot);
                statement.setInt(2, Math.max(0, nextCommandIndex));
                statement.setInt(3, claimed ? 1 : 0);
                if (claimed) {
                    statement.setLong(4, now.getEpochSecond());
                } else {
                    statement.setNull(4, Types.BIGINT);
                }
                statement.setLong(5, now.getEpochSecond());
                statement.setString(6, discordUserId);
                statement.setString(7, normalizedGamemode);
                updated = statement.executeUpdate();
            }

            if (updated > 0) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO booster_one_time_claims (
                        discord_user_id, gamemode_id, reward_commands_snapshot,
                        next_reward_command_index, claimed, claimed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, discordUserId);
                statement.setString(2, normalizedGamemode);
                setNullableString(statement, 3, encodedSnapshot);
                statement.setInt(4, Math.max(0, nextCommandIndex));
                statement.setInt(5, claimed ? 1 : 0);
                if (claimed) {
                    statement.setLong(6, now.getEpochSecond());
                } else {
                    statement.setNull(6, Types.BIGINT);
                }
                statement.setLong(7, now.getEpochSecond());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
                updateBoosterOneTimeRewardProgress(discordUserId, normalizedGamemode, encodedSnapshot, nextCommandIndex, claimed, now);
            }
        }
    }

    public Optional<LinkRewardState> findLinkRewardState(
            UUID playerUuid,
            String gamemodeId,
            List<String> fallbackAlwaysCommands,
            List<String> fallbackUnlinkCommands
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT current_link,
                            active_discord_user_id, active_discord_username, active_discord_display_name,
                            always_commands_snapshot, next_always_command_index,
                            unlink_commands_snapshot, next_unlink_command_index
                     FROM link_reward_states
                     WHERE player_uuid = ? AND gamemode_id = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, normalizedGamemode);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> alwaysFallback = sanitizeRewardCommands(fallbackAlwaysCommands);
                List<String> unlinkFallback = sanitizeRewardCommands(fallbackUnlinkCommands);
                if (!resultSet.next()) {
                    return Optional.of(new LinkRewardState(
                            false,
                            null,
                            null,
                            null,
                            alwaysFallback,
                            encodeRewardCommands(alwaysFallback),
                            0,
                            unlinkFallback,
                            encodeRewardCommands(unlinkFallback),
                            0
                    ));
                }

                String encodedAlwaysSnapshot = resultSet.getString("always_commands_snapshot");
                List<String> alwaysCommands = decodeRewardCommands(encodedAlwaysSnapshot);
                if (alwaysCommands.isEmpty()) {
                    alwaysCommands = alwaysFallback;
                    encodedAlwaysSnapshot = encodeRewardCommands(alwaysCommands);
                }
                int nextAlwaysIndex = Math.max(0, resultSet.getInt("next_always_command_index"));
                if (nextAlwaysIndex > alwaysCommands.size()) {
                    nextAlwaysIndex = alwaysCommands.size();
                }

                String encodedUnlinkSnapshot = resultSet.getString("unlink_commands_snapshot");
                List<String> unlinkCommands = decodeRewardCommands(encodedUnlinkSnapshot);
                if (unlinkCommands.isEmpty()) {
                    unlinkCommands = unlinkFallback;
                    encodedUnlinkSnapshot = encodeRewardCommands(unlinkCommands);
                }
                int nextUnlinkIndex = Math.max(0, resultSet.getInt("next_unlink_command_index"));
                if (nextUnlinkIndex > unlinkCommands.size()) {
                    nextUnlinkIndex = unlinkCommands.size();
                }

                return Optional.of(new LinkRewardState(
                        resultSet.getInt("current_link") == 1,
                        resultSet.getString("active_discord_user_id"),
                        resultSet.getString("active_discord_username"),
                        resultSet.getString("active_discord_display_name"),
                        alwaysCommands,
                        encodedAlwaysSnapshot,
                        nextAlwaysIndex,
                        unlinkCommands,
                        encodedUnlinkSnapshot,
                        nextUnlinkIndex
                ));
            }
        }
    }

    public void saveLinkRewardState(
            UUID playerUuid,
            String gamemodeId,
            LinkRewardState state,
            Instant now
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE link_reward_states
                    SET current_link = ?,
                        active_discord_user_id = ?,
                        active_discord_username = ?,
                        active_discord_display_name = ?,
                        always_commands_snapshot = ?,
                        next_always_command_index = ?,
                        unlink_commands_snapshot = ?,
                        next_unlink_command_index = ?,
                        updated_at = ?
                    WHERE player_uuid = ? AND gamemode_id = ?
                    """)) {
                statement.setInt(1, state.currentLink() ? 1 : 0);
                setNullableString(statement, 2, state.activeDiscordUserId());
                setNullableString(statement, 3, state.activeDiscordUsername());
                setNullableString(statement, 4, state.activeDiscordDisplayName());
                setNullableString(statement, 5, state.encodedAlwaysCommandSnapshot());
                statement.setInt(6, Math.max(0, state.nextAlwaysCommandIndex()));
                setNullableString(statement, 7, state.encodedUnlinkCommandSnapshot());
                statement.setInt(8, Math.max(0, state.nextUnlinkCommandIndex()));
                statement.setLong(9, now.getEpochSecond());
                statement.setString(10, playerUuid.toString());
                statement.setString(11, normalizedGamemode);
                updated = statement.executeUpdate();
            }

            if (updated > 0) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO link_reward_states (
                        player_uuid, gamemode_id, current_link,
                        active_discord_user_id, active_discord_username,
                        active_discord_display_name, always_commands_snapshot,
                        next_always_command_index, unlink_commands_snapshot,
                        next_unlink_command_index, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, normalizedGamemode);
                statement.setInt(3, state.currentLink() ? 1 : 0);
                setNullableString(statement, 4, state.activeDiscordUserId());
                setNullableString(statement, 5, state.activeDiscordUsername());
                setNullableString(statement, 6, state.activeDiscordDisplayName());
                setNullableString(statement, 7, state.encodedAlwaysCommandSnapshot());
                statement.setInt(8, Math.max(0, state.nextAlwaysCommandIndex()));
                setNullableString(statement, 9, state.encodedUnlinkCommandSnapshot());
                statement.setInt(10, Math.max(0, state.nextUnlinkCommandIndex()));
                statement.setLong(11, now.getEpochSecond());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
                saveLinkRewardState(playerUuid, normalizedGamemode, state, now);
            }
        }
    }

    public Optional<RewardClaimState> findLinkOneTimeRewardState(
            String discordUserId,
            String gamemodeId,
            List<String> fallbackRewardCommands
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT reward_commands_snapshot, next_reward_command_index, claimed
                     FROM link_one_time_claims
                     WHERE discord_user_id = ? AND gamemode_id = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, discordUserId);
            statement.setString(2, normalizedGamemode);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> fallback = sanitizeRewardCommands(fallbackRewardCommands);
                if (!resultSet.next()) {
                    String encoded = encodeRewardCommands(fallback);
                    return Optional.of(new RewardClaimState(fallback, encoded, 0, false));
                }

                String encodedSnapshot = resultSet.getString("reward_commands_snapshot");
                List<String> commands = decodeRewardCommands(encodedSnapshot);
                if (commands.isEmpty()) {
                    commands = fallback;
                    encodedSnapshot = encodeRewardCommands(commands);
                }

                int nextIndex = Math.max(0, resultSet.getInt("next_reward_command_index"));
                if (nextIndex > commands.size()) {
                    nextIndex = commands.size();
                }

                boolean claimed = resultSet.getInt("claimed") == 1;
                return Optional.of(new RewardClaimState(commands, encodedSnapshot, nextIndex, claimed));
            }
        }
    }

    public void updateLinkOneTimeRewardProgress(
            String discordUserId,
            String gamemodeId,
            String encodedSnapshot,
            int nextCommandIndex,
            boolean claimed,
            Instant now
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE link_one_time_claims
                    SET reward_commands_snapshot = ?,
                        next_reward_command_index = ?,
                        claimed = ?,
                        claimed_at = ?,
                        updated_at = ?
                    WHERE discord_user_id = ? AND gamemode_id = ?
                    """)) {
                setNullableString(statement, 1, encodedSnapshot);
                statement.setInt(2, Math.max(0, nextCommandIndex));
                statement.setInt(3, claimed ? 1 : 0);
                if (claimed) {
                    statement.setLong(4, now.getEpochSecond());
                } else {
                    statement.setNull(4, Types.BIGINT);
                }
                statement.setLong(5, now.getEpochSecond());
                statement.setString(6, discordUserId);
                statement.setString(7, normalizedGamemode);
                updated = statement.executeUpdate();
            }

            if (updated > 0) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO link_one_time_claims (
                        discord_user_id, gamemode_id, reward_commands_snapshot,
                        next_reward_command_index, claimed, claimed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, discordUserId);
                statement.setString(2, normalizedGamemode);
                setNullableString(statement, 3, encodedSnapshot);
                statement.setInt(4, Math.max(0, nextCommandIndex));
                statement.setInt(5, claimed ? 1 : 0);
                if (claimed) {
                    statement.setLong(6, now.getEpochSecond());
                } else {
                    statement.setNull(6, Types.BIGINT);
                }
                statement.setLong(7, now.getEpochSecond());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
                updateLinkOneTimeRewardProgress(discordUserId, normalizedGamemode, encodedSnapshot, nextCommandIndex, claimed, now);
            }
        }
    }

    public Optional<GamemodeRewardState> findGamemodeRewardState(UUID playerUuid, String gamemodeId, List<String> fallbackRewardCommands)
            throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT reward_commands_snapshot, next_reward_command_index, claimed
                     FROM network_reward_claims
                     WHERE player_uuid = ? AND gamemode_id = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, normalizedGamemode);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> fallback = sanitizeRewardCommands(fallbackRewardCommands);
                if (!resultSet.next()) {
                    String encoded = encodeRewardCommands(fallback);
                    return Optional.of(new GamemodeRewardState(fallback, encoded, 0, false));
                }

                String encodedSnapshot = resultSet.getString("reward_commands_snapshot");
                List<String> commands = decodeRewardCommands(encodedSnapshot);
                if (commands.isEmpty()) {
                    commands = fallback;
                    encodedSnapshot = encodeRewardCommands(commands);
                }

                int nextIndex = Math.max(0, resultSet.getInt("next_reward_command_index"));
                if (nextIndex > commands.size()) {
                    nextIndex = commands.size();
                }

                boolean claimed = resultSet.getInt("claimed") == 1;
                return Optional.of(new GamemodeRewardState(commands, encodedSnapshot, nextIndex, claimed));
            }
        }
    }

    public void updateGamemodeRewardProgress(
            UUID playerUuid,
            String gamemodeId,
            String encodedSnapshot,
            int nextCommandIndex,
            boolean claimed,
            Instant now
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE network_reward_claims
                    SET reward_commands_snapshot = ?,
                        next_reward_command_index = ?,
                        claimed = ?,
                        claimed_at = ?,
                        updated_at = ?
                    WHERE player_uuid = ? AND gamemode_id = ?
                    """)) {
                setNullableString(statement, 1, encodedSnapshot);
                statement.setInt(2, Math.max(0, nextCommandIndex));
                statement.setInt(3, claimed ? 1 : 0);
                if (claimed) {
                    statement.setLong(4, now.getEpochSecond());
                } else {
                    statement.setNull(4, Types.BIGINT);
                }
                statement.setLong(5, now.getEpochSecond());
                statement.setString(6, playerUuid.toString());
                statement.setString(7, normalizedGamemode);
                updated = statement.executeUpdate();
            }

            if (updated > 0) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO network_reward_claims (
                        player_uuid, gamemode_id, reward_commands_snapshot,
                        next_reward_command_index, claimed, claimed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, normalizedGamemode);
                setNullableString(statement, 3, encodedSnapshot);
                statement.setInt(4, Math.max(0, nextCommandIndex));
                statement.setInt(5, claimed ? 1 : 0);
                if (claimed) {
                    statement.setLong(6, now.getEpochSecond());
                } else {
                    statement.setNull(6, Types.BIGINT);
                }
                statement.setLong(7, now.getEpochSecond());
                statement.executeUpdate();
            } catch (SQLException exception) {
                if (!isConstraintViolation(exception)) {
                    throw exception;
                }
                updateGamemodeRewardProgress(playerUuid, normalizedGamemode, encodedSnapshot, nextCommandIndex, claimed, now);
            }
        }
    }

    public int resetGamemodeRewardClaims(UUID playerUuid, String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Set<String> discordUserIds = new HashSet<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT discord_user_id
                        FROM player_links
                        WHERE player_uuid = ?
                        LIMIT 1
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            addIfPresent(discordUserIds, resultSet.getString("discord_user_id"));
                        }
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT active_discord_user_id
                        FROM link_reward_states
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, normalizedGamemode);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            addIfPresent(discordUserIds, resultSet.getString("active_discord_user_id"));
                        }
                    }
                }

                int changed = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM link_reward_states
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM network_reward_claims
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE player_links
                        SET rewards_claimed = 0,
                            rewards_claimed_at = NULL
                        WHERE player_uuid = ? AND rewards_claimed = 1
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM link_one_time_claims
                        WHERE discord_user_id = ? AND gamemode_id = ?
                        """)) {
                    for (String discordUserId : discordUserIds) {
                        statement.setString(1, discordUserId);
                        statement.setString(2, normalizedGamemode);
                        changed += statement.executeUpdate();
                    }
                }

                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public int resetGamemodeRewardClaimsByDiscordId(String discordUserId, String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM link_reward_states
                        WHERE active_discord_user_id = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, discordUserId);
                    statement.setString(2, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM link_one_time_claims
                        WHERE discord_user_id = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, discordUserId);
                    statement.setString(2, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public int resetAllGamemodeRewardClaims(String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM link_reward_states
                        WHERE gamemode_id = ?
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM link_one_time_claims
                        WHERE gamemode_id = ?
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM network_reward_claims
                        WHERE gamemode_id = ?
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE player_links
                        SET rewards_claimed = 0,
                            rewards_claimed_at = NULL
                        WHERE rewards_claimed = 1
                        """)) {
                    changed += statement.executeUpdate();
                }

                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public int resetBoosterRewardClaims(UUID playerUuid, String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Set<String> discordUserIds = new HashSet<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT discord_user_id
                        FROM player_links
                        WHERE player_uuid = ?
                        LIMIT 1
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            addIfPresent(discordUserIds, resultSet.getString("discord_user_id"));
                        }
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT active_discord_user_id
                        FROM booster_reward_claims
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, normalizedGamemode);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            addIfPresent(discordUserIds, resultSet.getString("active_discord_user_id"));
                        }
                    }
                }

                int changed = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM booster_reward_claims
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM booster_one_time_claims
                        WHERE discord_user_id = ? AND gamemode_id = ?
                        """)) {
                    for (String discordUserId : discordUserIds) {
                        statement.setString(1, discordUserId);
                        statement.setString(2, normalizedGamemode);
                        changed += statement.executeUpdate();
                    }
                }

                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public int resetAllBoosterRewardClaims(String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM booster_reward_claims
                        WHERE gamemode_id = ?
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM booster_one_time_claims
                        WHERE gamemode_id = ?
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    changed += statement.executeUpdate();
                }

                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private Optional<RewardClaimState> findRewardClaimState(
            String tableName,
            UUID playerUuid,
            String gamemodeId,
            List<String> fallbackRewardCommands
    ) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT reward_commands_snapshot, next_reward_command_index, claimed
                     FROM %s
                     WHERE player_uuid = ? AND gamemode_id = ?
                     LIMIT 1
                     """.formatted(tableName))) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, normalizedGamemode);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> fallback = sanitizeRewardCommands(fallbackRewardCommands);
                if (!resultSet.next()) {
                    String encoded = encodeRewardCommands(fallback);
                    return Optional.of(new RewardClaimState(fallback, encoded, 0, false));
                }

                String encodedSnapshot = resultSet.getString("reward_commands_snapshot");
                List<String> commands = decodeRewardCommands(encodedSnapshot);
                if (commands.isEmpty()) {
                    commands = fallback;
                    encodedSnapshot = encodeRewardCommands(commands);
                }

                int nextIndex = Math.max(0, resultSet.getInt("next_reward_command_index"));
                if (nextIndex > commands.size()) {
                    nextIndex = commands.size();
                }

                boolean claimed = resultSet.getInt("claimed") == 1;
                return Optional.of(new RewardClaimState(commands, encodedSnapshot, nextIndex, claimed));
            }
        }
    }

    public void saveProfileSnapshot(String gamemodeId, ProfileCard card, Instant now) throws SQLException {
        if (card == null || card.playerUuid() == null) {
            return;
        }

        saveProfileSnapshots(gamemodeId, List.of(card), now);
    }

    public void saveProfileSnapshots(String gamemodeId, List<ProfileCard> cards, Instant now) throws SQLException {
        List<ProfileCard> snapshots = cards == null
                ? List.of()
                : cards.stream()
                .filter(card -> card != null && card.playerUuid() != null)
                .toList();
        if (snapshots.isEmpty()) {
            return;
        }

        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertGamemode(connection, normalizedGamemode, now);

                try (PreparedStatement updateStatement = connection.prepareStatement("""
                        UPDATE network_profile_snapshots
                        SET player_name = ?, thumbnail_url = ?, color = ?, footer = ?, updated_at = ?
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """);
                     PreparedStatement insertStatement = connection.prepareStatement("""
                            INSERT INTO network_profile_snapshots (
                                player_uuid, gamemode_id, player_name,
                                thumbnail_url, color, footer, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """);
                     PreparedStatement deleteFieldsStatement = connection.prepareStatement("""
                        DELETE FROM network_profile_snapshot_fields
                        WHERE player_uuid = ? AND gamemode_id = ?
                        """);
                     PreparedStatement insertFieldStatement = connection.prepareStatement("""
                        INSERT INTO network_profile_snapshot_fields (
                            player_uuid, gamemode_id, position,
                            field_name, field_value, inline_field, same_line
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (ProfileCard card : snapshots) {
                        updateStatement.setString(1, card.playerName());
                        updateStatement.setString(2, card.thumbnailUrl());
                        updateStatement.setInt(3, card.color());
                        updateStatement.setString(4, card.footer());
                        updateStatement.setLong(5, now.getEpochSecond());
                        updateStatement.setString(6, card.playerUuid().toString());
                        updateStatement.setString(7, normalizedGamemode);
                        int updated = updateStatement.executeUpdate();

                        if (updated == 0) {
                            insertStatement.setString(1, card.playerUuid().toString());
                            insertStatement.setString(2, normalizedGamemode);
                            insertStatement.setString(3, card.playerName());
                            insertStatement.setString(4, card.thumbnailUrl());
                            insertStatement.setInt(5, card.color());
                            insertStatement.setString(6, card.footer());
                            insertStatement.setLong(7, now.getEpochSecond());
                            insertStatement.executeUpdate();
                        }

                        deleteFieldsStatement.setString(1, card.playerUuid().toString());
                        deleteFieldsStatement.setString(2, normalizedGamemode);
                        deleteFieldsStatement.executeUpdate();

                        int position = 0;
                        for (ProfileField field : card.fields()) {
                            insertFieldStatement.setString(1, card.playerUuid().toString());
                            insertFieldStatement.setString(2, normalizedGamemode);
                            insertFieldStatement.setInt(3, position++);
                            insertFieldStatement.setString(4, field.name());
                            insertFieldStatement.setString(5, field.value());
                            insertFieldStatement.setInt(6, field.inline() ? 1 : 0);
                            insertFieldStatement.setInt(7, field.sameLine() ? 1 : 0);
                            insertFieldStatement.addBatch();
                        }
                    }
                    insertFieldStatement.executeBatch();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<ProfileCard> findProfileSnapshot(UUID playerUuid, String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            ProfileCard snapshot = null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT player_name, thumbnail_url, color, footer
                    FROM network_profile_snapshots
                    WHERE player_uuid = ? AND gamemode_id = ?
                    LIMIT 1
                    """)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, normalizedGamemode);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        snapshot = new ProfileCard(
                                resultSet.getString("player_name"),
                                playerUuid,
                                resultSet.getString("thumbnail_url"),
                                resultSet.getInt("color"),
                                resultSet.getString("footer"),
                                List.of()
                        );
                    }
                }
            }

            if (snapshot == null) {
                return Optional.empty();
            }

            List<ProfileField> fields = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT field_name, field_value, inline_field, same_line
                    FROM network_profile_snapshot_fields
                    WHERE player_uuid = ? AND gamemode_id = ?
                    ORDER BY position ASC
                    """)) {
                statement.setString(1, playerUuid.toString());
                statement.setString(2, normalizedGamemode);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        fields.add(new ProfileField(
                                resultSet.getString("field_name"),
                                resultSet.getString("field_value"),
                                resultSet.getInt("inline_field") == 1,
                                resultSet.getInt("same_line") == 1
                        ));
                    }
                }
            }

            return Optional.of(new ProfileCard(
                    snapshot.playerName(),
                    snapshot.playerUuid(),
                    snapshot.thumbnailUrl(),
                    snapshot.color(),
                    snapshot.footer(),
                    List.copyOf(fields)
            ));
        }
    }

    public void saveLeaderboardSnapshot(String gamemodeId, String boardAlias, LeaderboardView view, Instant now) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String normalizedAlias = normalizeBoardAlias(boardAlias);
        String encodedLines = String.join("\n", view.lines());

        try (Connection connection = openConnection()) {
            upsertGamemode(connection, normalizedGamemode, now);
            upsertGamemodeBoard(connection, normalizedGamemode, normalizedAlias, now);

            int updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE network_leaderboard_snapshots
                    SET title = ?, color = ?, lines_blob = ?, footer = ?, updated_at = ?
                    WHERE gamemode_id = ? AND board_alias = ?
                    """)) {
                statement.setString(1, view.title());
                statement.setInt(2, view.color());
                statement.setString(3, encodedLines);
                statement.setString(4, view.normalizedFooter());
                statement.setLong(5, now.getEpochSecond());
                statement.setString(6, normalizedGamemode);
                statement.setString(7, normalizedAlias);
                updated = statement.executeUpdate();
            }

            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO network_leaderboard_snapshots (
                            gamemode_id, board_alias, title, color, lines_blob, footer, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    statement.setString(2, normalizedAlias);
                    statement.setString(3, view.title());
                    statement.setInt(4, view.color());
                    statement.setString(5, encodedLines);
                    statement.setString(6, view.normalizedFooter());
                    statement.setLong(7, now.getEpochSecond());
                    statement.executeUpdate();
                }
            }
        }
    }

    public void replaceBoardCatalog(String gamemodeId, List<String> boardAliases, Instant now) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertGamemode(connection, normalizedGamemode, now);

                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM network_gamemode_boards
                        WHERE gamemode_id = ?
                        """)) {
                    statement.setString(1, normalizedGamemode);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO network_gamemode_boards (gamemode_id, board_alias, updated_at)
                        VALUES (?, ?, ?)
                        """)) {
                    Set<String> inserted = new HashSet<>();
                    for (String alias : boardAliases) {
                        String normalizedAlias = normalizeBoardAlias(alias);
                        if (normalizedAlias.isBlank() || !inserted.add(normalizedAlias)) {
                            continue;
                        }
                        statement.setString(1, normalizedGamemode);
                        statement.setString(2, normalizedAlias);
                        statement.setLong(3, now.getEpochSecond());
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public Optional<LeaderboardView> findLeaderboardSnapshot(String gamemodeId, String boardAlias) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String normalizedAlias = normalizeBoardAlias(boardAlias);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT title, color, lines_blob, footer
                     FROM network_leaderboard_snapshots
                     WHERE gamemode_id = ? AND board_alias = ?
                     LIMIT 1
                     """)) {
            statement.setString(1, normalizedGamemode);
            statement.setString(2, normalizedAlias);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }

                String linesBlob = resultSet.getString("lines_blob");
                List<String> lines = new ArrayList<>();
                if (linesBlob != null && !linesBlob.isBlank()) {
                    for (String line : linesBlob.split("\\R", -1)) {
                        if (line != null && !line.isBlank()) {
                            lines.add(line);
                        }
                    }
                }
                if (lines.isEmpty()) {
                    lines.add("No data available.");
                }

                return Optional.of(new LeaderboardView(
                        resultSet.getString("title"),
                        resultSet.getInt("color"),
                        List.copyOf(lines),
                        resultSet.getString("footer")
                ));
            }
        }
    }

    public int resetLeaderboardSnapshots(String gamemodeId, String boardAlias) throws SQLException {
        boolean allGamemodes = isAllScope(gamemodeId);
        boolean allBoards = isAllScope(boardAlias);
        String normalizedGamemode = allGamemodes ? "" : normalizeGamemode(gamemodeId);
        String normalizedAlias = allBoards ? "" : normalizeBoardAlias(boardAlias);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int changed = deleteLeaderboardRows(
                        connection,
                        "network_leaderboard_snapshots",
                        allGamemodes,
                        normalizedGamemode,
                        allBoards,
                        normalizedAlias
                );
                changed += deleteLeaderboardRows(
                        connection,
                        "network_gamemode_boards",
                        allGamemodes,
                        normalizedGamemode,
                        allBoards,
                        normalizedAlias
                );
                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void upsertAdvancementGamemode(String gamemodeId, String pluginVersion, Instant now) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection()) {
            upsertGamemode(connection, normalizedGamemode, now);
            upsertAdvancementGamemode(connection, normalizedGamemode, pluginVersion, now);
        }
    }

    public void saveAdvancementSnapshot(AdvancementProfileView view, Instant now) throws SQLException {
        if (view == null) {
            return;
        }
        saveAdvancementSnapshots(List.of(view), now);
    }

    public void saveAdvancementSnapshots(List<AdvancementProfileView> views, Instant now) throws SQLException {
        List<AdvancementProfileView> snapshots = views == null
                ? List.of()
                : views.stream().filter(this::validAdvancementView).toList();
        if (snapshots.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                Set<String> upsertedGamemodes = new HashSet<>();
                int pendingEntries = 0;
                try (PreparedStatement deleteStatement = connection.prepareStatement("""
                        DELETE FROM network_advancement_entries
                        WHERE gamemode_id = ? AND player_uuid = ?
                        """);
                     PreparedStatement insertStatement = connection.prepareStatement("""
                        INSERT INTO network_advancement_entries (
                            gamemode_id, player_uuid, tab_id, tab_title, tab_description, tab_icon,
                            tab_background, tab_completed, tab_total, tab_position, advancement_id,
                            full_id, title, description, icon, frame, current_progress, required_progress,
                            completed, visible, hidden, points, position, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    for (AdvancementProfileView view : snapshots) {
                        String normalizedGamemode = normalizeGamemode(view.gamemodeId());
                        if (upsertedGamemodes.add(normalizedGamemode)) {
                            upsertGamemode(connection, normalizedGamemode, now);
                            upsertAdvancementGamemode(connection, normalizedGamemode, view.pluginVersion(), now);
                        }

                        upsertAdvancementPlayer(connection, normalizedGamemode, view, now);

                        deleteStatement.setString(1, normalizedGamemode);
                        deleteStatement.setString(2, view.playerUuid().toString());
                        deleteStatement.executeUpdate();

                        pendingEntries = addAdvancementEntries(insertStatement, normalizedGamemode, view, now, pendingEntries);
                    }
                    insertStatement.executeBatch();
                }

                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private int addAdvancementEntries(
            PreparedStatement statement,
            String normalizedGamemode,
            AdvancementProfileView view,
            Instant now,
            int pendingEntries
    ) throws SQLException {
        long nowEpoch = now.getEpochSecond();
        List<AdvancementTabView> tabs = view.tabs() == null ? List.of() : view.tabs();
        Set<String> usedFullIds = new HashSet<>();
        for (int tabIndex = 0; tabIndex < tabs.size(); tabIndex++) {
            AdvancementTabView tab = tabs.get(tabIndex);
            if (tab == null) {
                continue;
            }

            String tabId = requiredDbValue(tab.id(), "tab-" + tabIndex, 64);
            String tabTitle = requiredDbValue(tab.title(), tabId, 256);
            List<AdvancementEntryView> advancements = tab.advancements() == null ? List.of() : tab.advancements();
            for (int advancementIndex = 0; advancementIndex < advancements.size(); advancementIndex++) {
                AdvancementEntryView advancement = advancements.get(advancementIndex);
                if (advancement == null) {
                    continue;
                }

                String advancementId = requiredDbValue(advancement.id(), "advancement-" + advancementIndex, 64);
                String fullId = uniqueAdvancementFullId(
                        usedFullIds,
                        requiredDbValue(advancement.fullId(), tabId + "/" + advancementId, 128),
                        tabId,
                        advancementIndex
                );
                statement.setString(1, normalizedGamemode);
                statement.setString(2, view.playerUuid().toString());
                statement.setString(3, tabId);
                statement.setString(4, tabTitle);
                statement.setString(5, joinLines(tab.description()));
                statement.setString(6, nullableDbValue(tab.icon(), 64));
                statement.setString(7, tab.background());
                statement.setInt(8, tab.completed());
                statement.setInt(9, tab.total());
                statement.setInt(10, tabIndex);
                statement.setString(11, advancementId);
                statement.setString(12, fullId);
                statement.setString(13, requiredDbValue(advancement.title(), advancementId, 256));
                statement.setString(14, joinLines(advancement.description()));
                statement.setString(15, nullableDbValue(advancement.icon(), 64));
                statement.setString(16, nullableDbValue(advancement.frame(), 32));
                statement.setInt(17, advancement.current());
                statement.setInt(18, advancement.required());
                statement.setInt(19, advancement.completed() ? 1 : 0);
                statement.setInt(20, advancement.visible() ? 1 : 0);
                statement.setInt(21, advancement.hidden() ? 1 : 0);
                statement.setInt(22, advancement.points());
                statement.setInt(23, advancementIndex);
                statement.setLong(24, nowEpoch);
                statement.addBatch();
                pendingEntries++;
                if (pendingEntries >= ADVANCEMENT_BATCH_SIZE) {
                    statement.executeBatch();
                    pendingEntries = 0;
                }
            }
        }
        return pendingEntries;
    }

    public Optional<AdvancementProfileView> findAdvancementSnapshot(String gamemodeId, String query) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }

        try (Connection connection = openConnection()) {
            AdvancementPlayerRow player = findAdvancementPlayer(connection, normalizedGamemode, trimmed).orElse(null);
            if (player == null) {
                return Optional.empty();
            }

            Map<String, MutableAdvancementTab> tabs = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT tab_id, tab_title, tab_description, tab_icon, tab_background,
                           tab_completed, tab_total, advancement_id, full_id, title,
                           description, icon, frame, current_progress, required_progress,
                           completed, visible, hidden, points
                    FROM network_advancement_entries
                    WHERE gamemode_id = ? AND player_uuid = ?
                    ORDER BY tab_position ASC, position ASC
                    """)) {
                statement.setString(1, normalizedGamemode);
                statement.setString(2, player.playerUuid().toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String tabId = resultSet.getString("tab_id");
                        MutableAdvancementTab tab = tabs.get(tabId);
                        if (tab == null) {
                            tab = new MutableAdvancementTab(
                                    tabId,
                                    resultSet.getString("tab_title"),
                                    splitLines(resultSet.getString("tab_description")),
                                    resultSet.getString("tab_icon"),
                                    resultSet.getString("tab_background"),
                                    resultSet.getInt("tab_completed"),
                                    resultSet.getInt("tab_total"),
                                    new ArrayList<>()
                            );
                            tabs.put(tabId, tab);
                        }
                        tab.advancements().add(new AdvancementEntryView(
                                resultSet.getString("advancement_id"),
                                resultSet.getString("full_id"),
                                resultSet.getString("title"),
                                splitLines(resultSet.getString("description")),
                                resultSet.getString("icon"),
                                resultSet.getString("frame"),
                                resultSet.getInt("current_progress"),
                                resultSet.getInt("required_progress"),
                                resultSet.getInt("completed") == 1,
                                resultSet.getInt("visible") == 1,
                                resultSet.getInt("hidden") == 1,
                                resultSet.getInt("points")
                        ));
                    }
                }
            }

            return Optional.of(new AdvancementProfileView(
                    normalizedGamemode,
                    player.playerUuid(),
                    player.playerName(),
                    player.pluginVersion(),
                    player.points(),
                    player.completed(),
                    player.total(),
                    tabs.values().stream().map(MutableAdvancementTab::toView).toList()
            ));
        }
    }

    public List<String> listAdvancementGamemodeIds() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT gamemode_id
                     FROM network_advancement_gamemodes
                     ORDER BY gamemode_id ASC
                     """)) {
            List<String> gamemodes = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String value = resultSet.getString("gamemode_id");
                    if (value != null && !value.isBlank()) {
                        gamemodes.add(value);
                    }
                }
            }
            return List.copyOf(gamemodes);
        }
    }

    public List<String> listAdvancementPlayerNames(String gamemodeId, String focusedLower, int limit) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String focused = focusedLower == null ? "" : focusedLower.toLowerCase(Locale.ROOT);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT player_name
                     FROM network_advancement_players
                     WHERE gamemode_id = ? AND player_name_lower LIKE ?
                     ORDER BY player_name ASC
                     LIMIT ?
                     """)) {
            statement.setString(1, normalizedGamemode);
            statement.setString(2, focused + "%");
            statement.setInt(3, Math.max(1, Math.min(25, limit)));
            List<String> players = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String value = resultSet.getString("player_name");
                    if (value != null && !value.isBlank()) {
                        players.add(value);
                    }
                }
            }
            return List.copyOf(players);
        }
    }

    public List<String> listGamemodeIds() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT gamemode_id
                     FROM network_gamemodes
                     ORDER BY gamemode_id ASC
                     """)) {
            List<String> gamemodes = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String value = resultSet.getString("gamemode_id");
                    if (value != null && !value.isBlank()) {
                        gamemodes.add(value);
                    }
                }
            }
            return List.copyOf(gamemodes);
        }
    }

    public List<String> listBoardAliasesForGamemode(String gamemodeId) throws SQLException {
        String normalizedGamemode = normalizeGamemode(gamemodeId);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT board_alias
                     FROM network_gamemode_boards
                     WHERE gamemode_id = ?
                     ORDER BY board_alias ASC
                     """)) {
            statement.setString(1, normalizedGamemode);
            List<String> aliases = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String value = resultSet.getString("board_alias");
                    if (value != null && !value.isBlank()) {
                        aliases.add(value);
                    }
                }
            }
            return List.copyOf(aliases);
        }
    }

    public void cleanupExpiredCodes() throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM pending_link_codes
                     WHERE expires_at <= ?
                     """)) {
            statement.setLong(1, Instant.now().getEpochSecond());
            statement.executeUpdate();
        }
    }

    public void enqueueChatRelayMessage(
            UUID playerUuid,
            String playerName,
            String avatarUrl,
            String gamemodeId,
            String message,
            Instant now
    ) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO network_chat_relay_queue (
                         gamemode_id, player_uuid, player_name, avatar_url, message, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, normalizeGamemode(gamemodeId));
            statement.setString(2, playerUuid != null ? playerUuid.toString() : "");
            statement.setString(3, playerName == null ? "" : playerName);
            statement.setString(4, avatarUrl == null ? "" : avatarUrl);
            statement.setString(5, message == null ? "" : message);
            statement.setLong(6, now.getEpochSecond());
            statement.executeUpdate();
        }
    }

    public List<QueuedChatRelayMessage> findPendingChatRelayMessages(int limit) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, gamemode_id, player_uuid, player_name, avatar_url, message, created_at
                     FROM network_chat_relay_queue
                     ORDER BY id ASC
                     LIMIT ?
                     """)) {
            statement.setInt(1, Math.max(1, limit));
            List<QueuedChatRelayMessage> messages = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String playerUuid = resultSet.getString("player_uuid");
                    messages.add(new QueuedChatRelayMessage(
                            resultSet.getLong("id"),
                            resultSet.getString("gamemode_id"),
                            playerUuid == null || playerUuid.isBlank() ? null : UUID.fromString(playerUuid),
                            resultSet.getString("player_name"),
                            resultSet.getString("avatar_url"),
                            resultSet.getString("message"),
                            Instant.ofEpochSecond(resultSet.getLong("created_at"))
                    ));
                }
            }
            return List.copyOf(messages);
        }
    }

    public void deleteQueuedChatRelayMessage(long id) throws SQLException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM network_chat_relay_queue
                     WHERE id = ?
                     """)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    public synchronized void close() {
        HikariDataSource active = dataSource;
        dataSource = null;
        dataSourceKey = null;
        if (active != null) {
            active.close();
        }
    }

    private Connection openConnection() throws SQLException {
        HikariDataSource active = activeDataSource();
        Connection connection = active.getConnection();
        if (!plugin.getPluginConfig().networkEnabled()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 3000");
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
        return connection;
    }

    private HikariDataSource activeDataSource() throws SQLException {
        HikariDataSource active = dataSource;
        if (active != null && !active.isClosed() && connectionKey().equals(dataSourceKey)) {
            return active;
        }
        synchronized (this) {
            refreshDataSource();
            return dataSource;
        }
    }

    private void refreshDataSource() throws SQLException {
        String key = connectionKey();
        HikariDataSource active = dataSource;
        if (active != null && !active.isClosed() && key.equals(dataSourceKey)) {
            return;
        }

        HikariDataSource replacement;
        try {
            replacement = new HikariDataSource(createHikariConfig());
        } catch (RuntimeException exception) {
            throw new SQLException("Failed to initialize database connection pool.", exception);
        }

        dataSource = replacement;
        dataSourceKey = key;
        if (active != null) {
            active.close();
        }
    }

    private HikariConfig createHikariConfig() {
        boolean networkMode = plugin.getPluginConfig().networkEnabled();
        HikariConfig config = new HikariConfig();
        config.setPoolName("FoDiscordBot-" + (networkMode ? "mysql" : "sqlite"));
        config.setJdbcUrl(networkMode ? plugin.getPluginConfig().mysqlJdbcUrl() : sqliteJdbcUrl);
        int maximumPoolSize = networkMode ? plugin.getPluginConfig().mysqlPoolSize() : 2;
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(networkMode ? Math.min(2, maximumPoolSize) : 1);
        config.setConnectionTimeout(networkMode ? plugin.getPluginConfig().mysqlConnectionTimeout().toMillis() : 10_000L);
        config.setValidationTimeout(5_000L);
        if (networkMode) {
            config.setUsername(plugin.getPluginConfig().mysqlUsername());
            config.setPassword(plugin.getPluginConfig().mysqlPassword());
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        }
        return config;
    }

    private String connectionKey() {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return "sqlite|" + sqliteJdbcUrl;
        }
        return String.join("|",
                "mysql",
                plugin.getPluginConfig().mysqlJdbcUrl(),
                plugin.getPluginConfig().mysqlUsername(),
                plugin.getPluginConfig().mysqlPassword(),
                String.valueOf(plugin.getPluginConfig().mysqlPoolSize()),
                String.valueOf(plugin.getPluginConfig().mysqlConnectionTimeout().toMillis())
        );
    }

    private Optional<LinkedAccount> getLinkedAccount(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, player_name, discord_user_id, discord_username,
                       discord_display_name, linked_at, updated_at,
                       rewards_claimed, rewards_claimed_at
                FROM player_links
                WHERE player_uuid = ?
                """)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<LinkedAccount> getLinkedAccountByDiscord(Connection connection, String discordUserId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, player_name, discord_user_id, discord_username,
                       discord_display_name, linked_at, updated_at,
                       rewards_claimed, rewards_claimed_at
                FROM player_links
                WHERE discord_user_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, discordUserId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readAccount(resultSet)) : Optional.empty();
            }
        }
    }

    private Optional<PendingLinkCode> getPendingCode(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT code, player_uuid, player_name, created_at, expires_at
                FROM pending_link_codes
                WHERE code = ?
                LIMIT 1
                """)) {
            statement.setString(1, code);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PendingLinkCode(
                        resultSet.getString("code"),
                        UUID.fromString(resultSet.getString("player_uuid")),
                        resultSet.getString("player_name"),
                        Instant.ofEpochSecond(resultSet.getLong("created_at")),
                        Instant.ofEpochSecond(resultSet.getLong("expires_at"))
                ));
            }
        }
    }

    private void deletePendingCode(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM pending_link_codes
                WHERE code = ?
                """)) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }

    private void deletePendingCodeForPlayer(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM pending_link_codes
                WHERE player_uuid = ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.executeUpdate();
        }
    }

    private void upsertPlayerSnapshot(Connection connection, UUID playerUuid, String playerName, Instant now) throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE player_links
                SET player_name = ?, updated_at = ?
                WHERE player_uuid = ?
                """)) {
            statement.setString(1, playerName);
            statement.setLong(2, now.getEpochSecond());
            statement.setString(3, playerUuid.toString());
            updated = statement.executeUpdate();
        }

        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO player_links(player_uuid, player_name, updated_at, rewards_claimed)
                VALUES (?, ?, ?, 0)
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setLong(3, now.getEpochSecond());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            upsertPlayerSnapshot(connection, playerUuid, playerName, now);
        }
    }

    private LinkedAccount readAccount(ResultSet resultSet) throws SQLException {
        long linkedAtEpoch = resultSet.getLong("linked_at");
        Instant linkedAt = resultSet.wasNull() ? null : Instant.ofEpochSecond(linkedAtEpoch);

        long rewardsClaimedAtEpoch = resultSet.getLong("rewards_claimed_at");
        Instant rewardsClaimedAt = resultSet.wasNull() ? null : Instant.ofEpochSecond(rewardsClaimedAtEpoch);

        return new LinkedAccount(
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("discord_user_id"),
                resultSet.getString("discord_username"),
                resultSet.getString("discord_display_name"),
                linkedAt,
                Instant.ofEpochSecond(resultSet.getLong("updated_at")),
                resultSet.getInt("rewards_claimed") == 1,
                rewardsClaimedAt
        );
    }

    private void upsertGamemode(Connection connection, String gamemodeId, Instant now) throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE network_gamemodes
                SET updated_at = ?
                WHERE gamemode_id = ?
                """)) {
            statement.setLong(1, now.getEpochSecond());
            statement.setString(2, gamemodeId);
            updated = statement.executeUpdate();
        }
        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO network_gamemodes(gamemode_id, updated_at)
                VALUES (?, ?)
                """)) {
            statement.setString(1, gamemodeId);
            statement.setLong(2, now.getEpochSecond());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            upsertGamemode(connection, gamemodeId, now);
        }
    }

    private void upsertGamemodeBoard(Connection connection, String gamemodeId, String boardAlias, Instant now) throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE network_gamemode_boards
                SET updated_at = ?
                WHERE gamemode_id = ? AND board_alias = ?
                """)) {
            statement.setLong(1, now.getEpochSecond());
            statement.setString(2, gamemodeId);
            statement.setString(3, boardAlias);
            updated = statement.executeUpdate();
        }
        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO network_gamemode_boards(gamemode_id, board_alias, updated_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, gamemodeId);
            statement.setString(2, boardAlias);
            statement.setLong(3, now.getEpochSecond());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            upsertGamemodeBoard(connection, gamemodeId, boardAlias, now);
        }
    }

    private int deleteLeaderboardRows(
            Connection connection,
            String tableName,
            boolean allGamemodes,
            String gamemodeId,
            boolean allBoards,
            String boardAlias
    ) throws SQLException {
        if (allGamemodes && allBoards) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM %s
                    """.formatted(tableName))) {
                return statement.executeUpdate();
            }
        }

        if (allGamemodes) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM %s
                    WHERE board_alias = ?
                    """.formatted(tableName))) {
                statement.setString(1, boardAlias);
                return statement.executeUpdate();
            }
        }

        if (allBoards) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM %s
                    WHERE gamemode_id = ?
                    """.formatted(tableName))) {
                statement.setString(1, gamemodeId);
                return statement.executeUpdate();
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM %s
                WHERE gamemode_id = ? AND board_alias = ?
                """.formatted(tableName))) {
            statement.setString(1, gamemodeId);
            statement.setString(2, boardAlias);
            return statement.executeUpdate();
        }
    }

    private void upsertAdvancementGamemode(Connection connection, String gamemodeId, String pluginVersion, Instant now) throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE network_advancement_gamemodes
                SET plugin_version = ?, updated_at = ?
                WHERE gamemode_id = ?
                """)) {
            setNullableString(statement, 1, nullableDbValue(pluginVersion, 32));
            statement.setLong(2, now.getEpochSecond());
            statement.setString(3, gamemodeId);
            updated = statement.executeUpdate();
        }
        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO network_advancement_gamemodes(gamemode_id, plugin_version, updated_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, gamemodeId);
            setNullableString(statement, 2, nullableDbValue(pluginVersion, 32));
            statement.setLong(3, now.getEpochSecond());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            upsertAdvancementGamemode(connection, gamemodeId, pluginVersion, now);
        }
    }

    private void upsertAdvancementPlayer(
            Connection connection,
            String gamemodeId,
            AdvancementProfileView view,
            Instant now
    ) throws SQLException {
        String playerName = requiredDbValue(view.playerName(), view.playerUuid().toString(), 32);
        String pluginVersion = nullableDbValue(view.pluginVersion(), 32);
        int updated;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE network_advancement_players
                SET player_name = ?, player_name_lower = ?, plugin_version = ?, points = ?, completed = ?, total = ?, updated_at = ?
                WHERE gamemode_id = ? AND player_uuid = ?
                """)) {
            statement.setString(1, playerName);
            statement.setString(2, normalizePlayerName(playerName));
            setNullableString(statement, 3, pluginVersion);
            statement.setInt(4, view.points());
            statement.setInt(5, view.completed());
            statement.setInt(6, view.total());
            statement.setLong(7, now.getEpochSecond());
            statement.setString(8, gamemodeId);
            statement.setString(9, view.playerUuid().toString());
            updated = statement.executeUpdate();
        }
        if (updated > 0) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO network_advancement_players (
                    gamemode_id, player_uuid, player_name, player_name_lower, plugin_version,
                    points, completed, total, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, gamemodeId);
            statement.setString(2, view.playerUuid().toString());
            statement.setString(3, playerName);
            statement.setString(4, normalizePlayerName(playerName));
            setNullableString(statement, 5, pluginVersion);
            statement.setInt(6, view.points());
            statement.setInt(7, view.completed());
            statement.setInt(8, view.total());
            statement.setLong(9, now.getEpochSecond());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            upsertAdvancementPlayer(connection, gamemodeId, view, now);
        }
    }

    private Optional<AdvancementPlayerRow> findAdvancementPlayer(
            Connection connection,
            String gamemodeId,
            String query
    ) throws SQLException {
        UUID uuid = parseUuid(query);
        if (uuid != null) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT gamemode_id, player_uuid, player_name, plugin_version,
                           points, completed, total, updated_at
                    FROM network_advancement_players
                    WHERE gamemode_id = ? AND player_uuid = ?
                    LIMIT 1
                    """)) {
                statement.setString(1, gamemodeId);
                statement.setString(2, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(readAdvancementPlayer(resultSet))
                            : Optional.empty();
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT gamemode_id, player_uuid, player_name, plugin_version,
                       points, completed, total, updated_at
                FROM network_advancement_players
                WHERE gamemode_id = ? AND player_name_lower = ?
                ORDER BY updated_at DESC
                LIMIT 1
                """)) {
            statement.setString(1, gamemodeId);
            statement.setString(2, normalizePlayerName(query));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(readAdvancementPlayer(resultSet))
                        : Optional.empty();
            }
        }
    }

    private AdvancementPlayerRow readAdvancementPlayer(ResultSet resultSet) throws SQLException {
        return new AdvancementPlayerRow(
                resultSet.getString("gamemode_id"),
                UUID.fromString(resultSet.getString("player_uuid")),
                resultSet.getString("player_name"),
                resultSet.getString("plugin_version"),
                resultSet.getInt("points"),
                resultSet.getInt("completed"),
                resultSet.getInt("total"),
                Instant.ofEpochSecond(resultSet.getLong("updated_at"))
        );
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean validAdvancementView(AdvancementProfileView view) {
        return view != null && view.playerUuid() != null;
    }

    private String uniqueAdvancementFullId(Set<String> usedFullIds, String candidate, String tabId, int advancementIndex) {
        String normalized = requiredDbValue(candidate, tabId + "/" + advancementIndex, 128);
        if (usedFullIds.add(normalized)) {
            return normalized;
        }

        int collision = 1;
        while (true) {
            String suffix = "-" + advancementIndex + "-" + collision;
            String unique = truncateDbValue(normalized, Math.max(1, 128 - suffix.length())) + suffix;
            if (usedFullIds.add(unique)) {
                return unique;
            }
            collision++;
        }
    }

    private String requiredDbValue(String value, String fallback, int maxLength) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized == null || normalized.isBlank()) {
            normalized = "unknown";
        }
        return truncateDbValue(normalized, maxLength);
    }

    private String nullableDbValue(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return truncateDbValue(value.trim(), maxLength);
    }

    private String truncateDbValue(String value, int maxLength) {
        if (value == null || maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean isConstraintViolation(SQLException exception) {
        String sqlState = exception.getSQLState();
        return sqlState != null && sqlState.startsWith("23");
    }

    private boolean isIndexAlreadyExists(SQLException exception) {
        if (exception.getErrorCode() == 1061) {
            // MySQL: Duplicate key name.
            return true;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String lowered = message.toLowerCase(Locale.ROOT);
        return lowered.contains("already exists") || lowered.contains("duplicate key name");
    }

    private boolean isColumnAlreadyExists(SQLException exception) {
        if (exception.getErrorCode() == 1060) {
            // MySQL: Duplicate column name.
            return true;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String lowered = message.toLowerCase(Locale.ROOT);
        return lowered.contains("duplicate column name") || lowered.contains("duplicate column");
    }

    private List<String> sanitizeRewardCommands(List<String> rewardCommands) {
        List<String> commands = new ArrayList<>();
        if (rewardCommands == null) {
            return commands;
        }
        for (String rewardCommand : rewardCommands) {
            if (rewardCommand != null && !rewardCommand.isBlank()) {
                commands.add(rewardCommand);
            }
        }
        return commands;
    }

    private String encodeRewardCommands(List<String> rewardCommands) {
        return String.join("\n", sanitizeRewardCommands(rewardCommands));
    }

    private List<String> decodeRewardCommands(String encodedSnapshot) {
        if (encodedSnapshot == null || encodedSnapshot.isBlank()) {
            return new ArrayList<>();
        }

        List<String> commands = new ArrayList<>();
        for (String line : encodedSnapshot.split("\\R", -1)) {
            if (line != null && !line.isBlank()) {
                commands.add(line);
            }
        }
        return commands;
    }

    private String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }

        List<String> sanitized = new ArrayList<>();
        for (String line : lines) {
            if (line != null) {
                sanitized.add(line);
            }
        }
        return String.join("\n", sanitized);
    }

    private List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        for (String line : value.split("\\R", -1)) {
            if (line != null && !line.isBlank()) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value != null && !value.isBlank()) {
            statement.setString(index, value);
            return;
        }
        statement.setNull(index, Types.VARCHAR);
    }

    private String generateCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(CODE_CHARACTERS.charAt(secureRandom.nextInt(CODE_CHARACTERS.length())));
        }
        return builder.toString();
    }

    private String normalizeGamemode(String value) {
        if (value == null || value.isBlank()) {
            return plugin.getPluginConfig().normalizedGamemodeId();
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeBoardAlias(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isAllScope(String value) {
        return value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim());
    }

    private String normalizePlayerName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record GamemodeRewardState(
            List<String> rewardCommands,
            String encodedCommandSnapshot,
            int nextCommandIndex,
            boolean claimed
    ) {
    }

    public record RewardClaimState(
            List<String> rewardCommands,
            String encodedCommandSnapshot,
            int nextCommandIndex,
            boolean claimed
    ) {
    }

    public record LinkRewardState(
            boolean currentLink,
            String activeDiscordUserId,
            String activeDiscordUsername,
            String activeDiscordDisplayName,
            List<String> alwaysCommands,
            String encodedAlwaysCommandSnapshot,
            int nextAlwaysCommandIndex,
            List<String> unlinkCommands,
            String encodedUnlinkCommandSnapshot,
            int nextUnlinkCommandIndex
    ) {
        public LinkRewardState withAlwaysProgress(LinkedAccount account, String encodedSnapshot, int nextCommandIndex, boolean current) {
            return new LinkRewardState(
                    current,
                    current ? account.discordUserId() : activeDiscordUserId,
                    current ? account.discordUsername() : activeDiscordUsername,
                    current ? account.discordDisplayName() : activeDiscordDisplayName,
                    alwaysCommands,
                    encodedSnapshot,
                    nextCommandIndex,
                    unlinkCommands,
                    encodedUnlinkCommandSnapshot,
                    nextUnlinkCommandIndex
            );
        }

        public LinkRewardState withUnlinkProgress(String encodedSnapshot, int nextCommandIndex, boolean current) {
            return new LinkRewardState(
                    current,
                    activeDiscordUserId,
                    activeDiscordUsername,
                    activeDiscordDisplayName,
                    alwaysCommands,
                    current ? encodedAlwaysCommandSnapshot : null,
                    current ? nextAlwaysCommandIndex : 0,
                    unlinkCommands,
                    encodedSnapshot,
                    nextCommandIndex
            );
        }

        public LinkRewardState inactive() {
            return new LinkRewardState(
                    false,
                    null,
                    null,
                    null,
                    alwaysCommands,
                    null,
                    0,
                    unlinkCommands,
                    null,
                    0
            );
        }
    }

    public record BoosterRewardState(
            boolean currentBooster,
            String activeDiscordUserId,
            String activeDiscordUsername,
            String activeDiscordDisplayName,
            boolean activeLinkBroken,
            List<String> alwaysCommands,
            String encodedAlwaysCommandSnapshot,
            int nextAlwaysCommandIndex,
            List<String> removalCommands,
            String encodedRemovalCommandSnapshot,
            int nextRemovalCommandIndex
    ) {
        public BoosterRewardState withAlwaysProgress(LinkedAccount account, String encodedSnapshot, int nextCommandIndex, boolean current) {
            return new BoosterRewardState(
                    current,
                    current ? account.discordUserId() : activeDiscordUserId,
                    current ? account.discordUsername() : activeDiscordUsername,
                    current ? account.discordDisplayName() : activeDiscordDisplayName,
                    false,
                    alwaysCommands,
                    encodedSnapshot,
                    nextCommandIndex,
                    removalCommands,
                    encodedRemovalCommandSnapshot,
                    nextRemovalCommandIndex
            );
        }

        public BoosterRewardState withRemovalProgress(String encodedSnapshot, int nextCommandIndex, boolean current) {
            return new BoosterRewardState(
                    current,
                    activeDiscordUserId,
                    activeDiscordUsername,
                    activeDiscordDisplayName,
                    current && activeLinkBroken,
                    alwaysCommands,
                    current ? encodedAlwaysCommandSnapshot : null,
                    current ? nextAlwaysCommandIndex : 0,
                    removalCommands,
                    encodedSnapshot,
                    nextCommandIndex
            );
        }

        public BoosterRewardState inactive() {
            return new BoosterRewardState(
                    false,
                    null,
                    null,
                    null,
                    false,
                    alwaysCommands,
                    null,
                    0,
                    removalCommands,
                    null,
                    0
            );
        }

        public BoosterRewardState withBrokenLink() {
            return new BoosterRewardState(
                    currentBooster,
                    activeDiscordUserId,
                    activeDiscordUsername,
                    activeDiscordDisplayName,
                    currentBooster,
                    alwaysCommands,
                    encodedAlwaysCommandSnapshot,
                    nextAlwaysCommandIndex,
                    removalCommands,
                    encodedRemovalCommandSnapshot,
                    nextRemovalCommandIndex
            );
        }
    }

    private record LegacyPlayerRewardClaim(String playerUuid, long claimedAtEpoch) {
    }

    private record LegacyLinkRewardClaim(
            String playerUuid,
            String gamemodeId,
            String encodedSnapshot,
            int nextCommandIndex,
            boolean claimed,
            long claimedAtEpoch,
            long updatedAtEpoch,
            String discordUserId
    ) {
        private boolean hasLinkedDiscord() {
            return discordUserId != null && !discordUserId.isBlank();
        }

        private long claimedAtOrUpdatedAt() {
            return claimedAtEpoch > 0L ? claimedAtEpoch : updatedAtEpoch;
        }
    }

    private record LegacyBoosterRewardClaim(
            String playerUuid,
            String gamemodeId,
            String encodedSnapshot,
            int nextCommandIndex,
            boolean claimed,
            long claimedAtEpoch,
            boolean currentBooster,
            long updatedAtEpoch,
            String activeDiscordUserId,
            String discordUserId,
            String discordUsername,
            String discordDisplayName
    ) {
        private boolean hasLinkedDiscord() {
            return discordUserId != null && !discordUserId.isBlank();
        }

        private long claimedAtOrUpdatedAt() {
            return claimedAtEpoch > 0L ? claimedAtEpoch : updatedAtEpoch;
        }
    }

    private record AdvancementPlayerRow(
            String gamemodeId,
            UUID playerUuid,
            String playerName,
            String pluginVersion,
            int points,
            int completed,
            int total,
            Instant updatedAt
    ) {
    }

    private record MutableAdvancementTab(
            String id,
            String title,
            List<String> description,
            String icon,
            String background,
            int completed,
            int total,
            List<AdvancementEntryView> advancements
    ) {
        private AdvancementTabView toView() {
            return new AdvancementTabView(
                    id,
                    title,
                    description,
                    icon,
                    background,
                    completed,
                    total,
                    List.copyOf(advancements)
            );
        }
    }

    public record QueuedChatRelayMessage(
            long id,
            String gamemodeId,
            UUID playerUuid,
            String playerName,
            String avatarUrl,
            String message,
            Instant createdAt
    ) {
    }
}
