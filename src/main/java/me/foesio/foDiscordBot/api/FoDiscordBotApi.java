package me.foesio.foDiscordBot.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Supported account queries for other Bukkit plugins. Discover through ServicesManager. */
public interface FoDiscordBotApi {

    /** Looks up committed link state on an async database worker. An unlinked player returns empty. */
    CompletableFuture<Optional<LinkedIdentity>> findLinkByPlayer(UUID playerUuid);

    /** Looks up committed link state on an async database worker. An unknown Discord ID returns empty. */
    CompletableFuture<Optional<LinkedIdentity>> findLinkByDiscordId(String discordUserId);

    /** Reads local reward state, not the live Discord role. A missing row yields an inactive snapshot. */
    CompletableFuture<BoosterRewardSnapshot> findBoosterReward(UUID playerUuid, String gamemodeId);

    /** Updates slash commands without reconnecting the bot. Safe to call from a Bukkit or async thread. */
    CompletableFuture<CommandRefreshResult> refreshSlashCommands();

    /** Consumes a normal one-time code using the same checks and cooldown as Discord linking. */
    CompletableFuture<LinkRequestResult> linkWithCode(String code, String discordUserId,
                                                      String discordUsername, String discordDisplayName);

    /** Unlinks a committed account through normal reward, booster, role and persistence handling. */
    CompletableFuture<UnlinkRequestResult> unlinkByPlayer(UUID playerUuid);

    /** Unlinks a committed account through normal reward, booster, role and persistence handling. */
    CompletableFuture<UnlinkRequestResult> unlinkByDiscordId(String discordUserId);
}
