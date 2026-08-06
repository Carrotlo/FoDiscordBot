package me.foesio.foDiscordBot.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.model.RankRoleMapping;
import me.foesio.foDiscordBot.util.BukkitFutures;
import org.bukkit.entity.Player;

public final class RankSyncService {

    private final FoDiscordBot plugin;
    private final LinkRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Set<UUID> syncInProgress = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedWarnings = ConcurrentHashMap.newKeySet();

    public RankSyncService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public void handlePlayerJoin(Player player) {
        if (!plugin.getPluginConfig().rankSyncEnabled()) {
            return;
        }

        String guildId = plugin.getPluginConfig().normalizedGuildId();
        if (guildId == null) {
            warnOnce("rank-sync-guild-missing", "Rank sync is enabled but discord.command-guild-id is blank.");
            return;
        }
        if (!plugin.getPluginConfig().hasConfiguredBotToken()) {
            warnOnce("rank-sync-token-missing", "Rank sync is enabled but discord.token is not configured.");
            return;
        }

        List<RankRoleMapping> matches = plugin.getPluginConfig().rankSyncMappings().stream()
                .filter(mapping -> validateMapping(mapping))
                .filter(mapping -> player.hasPermission(mapping.permission()))
                .toList();
        if (matches.isEmpty()) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        if (!syncInProgress.add(playerUuid)) {
            return;
        }

        BukkitFutures.supplyAsync(plugin, () -> syncRoles(playerUuid, playerName, guildId, matches))
                .whenComplete((syncedRoles, throwable) -> {
                    syncInProgress.remove(playerUuid);
                    if (throwable != null) {
                        plugin.logWarning("Failed to sync rank roles for " + playerName + ": " + throwable.getMessage());
                    }
                });
    }

    private int syncRoles(UUID playerUuid, String playerName, String guildId, List<RankRoleMapping> matches) {
        try {
            LinkedAccount account = repository.findByPlayerUuid(playerUuid).orElse(null);
            if (account == null || !account.isLinked() || account.discordUserId() == null || account.discordUserId().isBlank()) {
                return 0;
            }

            int syncedRoles = 0;
            for (RankRoleMapping mapping : matches) {
                if (addDiscordRole(account.discordUserId(), guildId, mapping, playerName)) {
                    syncedRoles++;
                }
            }
            return syncedRoles;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private boolean addDiscordRole(String discordUserId, String guildId, RankRoleMapping mapping, String playerName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://discord.com/api/v10/guilds/" + guildId + "/members/" + discordUserId + "/roles/" + mapping.roleId()
                ))
                .header("Authorization", "Bot " + plugin.getPluginConfig().botToken())
                .header("User-Agent", "FoDiscordBot/3.9")
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) {
            return true;
        }
        if (response.statusCode() == 404) {
            warnOnce("rank-sync-member-or-role-missing-" + mapping.key(),
                    "Rank sync could not add Discord role for " + mapping.key()
                            + ". The member or role was not found in the configured guild.");
            return false;
        }
        if (response.statusCode() == 403) {
            warnOnce("rank-sync-forbidden-" + mapping.key(),
                    "Rank sync could not add Discord role for " + mapping.key()
                            + ". The bot needs Manage Roles and a higher Discord role than the target role.");
            return false;
        }

        warnOnce("rank-sync-http-" + response.statusCode() + "-" + mapping.key(),
                "Rank sync failed to add Discord role " + mapping.roleId() + " for " + playerName
                        + " with HTTP " + response.statusCode() + ".");
        return false;
    }

    private boolean validateMapping(RankRoleMapping mapping) {
        if (mapping.permission().isBlank()) {
            warnOnce("rank-sync-permission-missing-" + mapping.key(),
                    "Rank sync entry " + mapping.key() + " has a blank permission.");
            return false;
        }
        if (mapping.roleId().isBlank()) {
            warnOnce("rank-sync-role-missing-" + mapping.key(),
                    "Rank sync entry " + mapping.key() + " has a blank role-id.");
            return false;
        }
        return true;
    }

    private void warnOnce(String key, String message) {
        if (loggedWarnings.add(key)) {
            plugin.logWarning(message);
        }
    }
}
