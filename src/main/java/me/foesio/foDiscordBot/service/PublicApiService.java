package me.foesio.foDiscordBot.service;

import java.util.Optional;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.api.BoosterRewardSnapshot;
import me.foesio.foDiscordBot.api.CommandRefreshResult;
import me.foesio.foDiscordBot.api.FoDiscordBotApi;
import me.foesio.foDiscordBot.api.LinkedIdentity;
import me.foesio.foDiscordBot.api.LinkRequestResult;
import me.foesio.foDiscordBot.api.UnlinkRequestResult;
import me.foesio.foDiscordBot.api.event.AccountChangeCause;
import me.foesio.foDiscordBot.model.DiscordUserSnapshot;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.util.BukkitFutures;

public final class PublicApiService implements FoDiscordBotApi {

    private final FoDiscordBot plugin;
    private final LinkRepository repository;

    public PublicApiService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    @Override
    public CompletableFuture<Optional<LinkedIdentity>> findLinkByPlayer(UUID playerUuid) {
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("FoDiscordBot is disabled"));
        }
        if (playerUuid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid is required"));
        }
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findByPlayerUuid(playerUuid).filter(LinkedAccount::isLinked).map(PublicApiService::identity);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not read account link", exception);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<LinkedIdentity>> findLinkByDiscordId(String discordUserId) {
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("FoDiscordBot is disabled"));
        }
        if (discordUserId == null || discordUserId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("discordUserId is required"));
        }
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findLinkedByDiscordId(discordUserId).filter(LinkedAccount::isLinked).map(PublicApiService::identity);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not read account link", exception);
            }
        });
    }

    @Override
    public CompletableFuture<BoosterRewardSnapshot> findBoosterReward(UUID playerUuid, String gamemodeId) {
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("FoDiscordBot is disabled"));
        }
        if (playerUuid == null || gamemodeId == null || gamemodeId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid and gamemodeId are required"));
        }
        String normalizedGamemode = gamemodeId.trim().toLowerCase(Locale.ROOT);
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findBoosterRewardState(playerUuid, normalizedGamemode,
                                plugin.getPluginConfig().boosterAlwaysRewardCommands(),
                                plugin.getPluginConfig().boosterRemovalCommands())
                        .map(state -> new BoosterRewardSnapshot(playerUuid, normalizedGamemode, state.currentBooster(),
                                state.activeLinkBroken(), state.activeDiscordUserId()))
                        .orElseGet(() -> new BoosterRewardSnapshot(playerUuid, normalizedGamemode, false, false, null));
            } catch (Exception exception) {
                throw new IllegalStateException("Could not read booster reward state", exception);
            }
        });
    }

    @Override
    public CompletableFuture<CommandRefreshResult> refreshSlashCommands() {
        if (!plugin.isEnabled() || plugin.getDiscordBotManager() == null) {
            return CompletableFuture.completedFuture(CommandRefreshResult.UNAVAILABLE);
        }
        return plugin.getDiscordBotManager().refreshSlashCommands();
    }

    @Override
    public CompletableFuture<LinkRequestResult> linkWithCode(String code, String discordUserId,
                                                              String discordUsername, String discordDisplayName) {
        if (!plugin.isEnabled() || plugin.getLinkService() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("FoDiscordBot is unavailable"));
        }
        if (code == null || code.isBlank() || discordUserId == null || !discordUserId.matches("[0-9]{16,20}")
                || discordUsername == null || discordUsername.isBlank()
                || discordDisplayName == null || discordDisplayName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A code and valid Discord identity are required"));
        }
        DiscordUserSnapshot snapshot = new DiscordUserSnapshot(discordUserId, discordUsername,
                discordDisplayName, "<@" + discordUserId + ">");
        return plugin.getLinkService().completeLink(code, snapshot, AccountChangeCause.API)
                .thenApply(response -> new LinkRequestResult(
                        switch (response.status()) {
                            case SUCCESS -> LinkRequestResult.Status.LINKED;
                            case COOLDOWN -> LinkRequestResult.Status.COOLDOWN;
                            case INVALID_CODE -> LinkRequestResult.Status.INVALID_CODE;
                            case EXPIRED_CODE -> LinkRequestResult.Status.EXPIRED_CODE;
                            case DISCORD_ALREADY_LINKED -> LinkRequestResult.Status.DISCORD_ALREADY_LINKED;
                            case PLAYER_ALREADY_LINKED -> LinkRequestResult.Status.PLAYER_ALREADY_LINKED;
                        },
                        response.status() == LinkService.DiscordLinkStatus.SUCCESS ? identity(response.account()) : null,
                        response.remaining()));
    }

    @Override
    public CompletableFuture<UnlinkRequestResult> unlinkByPlayer(UUID playerUuid) {
        if (!plugin.isEnabled() || plugin.getLinkService() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("FoDiscordBot is unavailable"));
        }
        if (playerUuid == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("playerUuid is required"));
        }
        return plugin.getLinkService().unlinkExternallyByPlayer(playerUuid).thenApply(PublicApiService::unlinkResult);
    }

    @Override
    public CompletableFuture<UnlinkRequestResult> unlinkByDiscordId(String discordUserId) {
        if (!plugin.isEnabled() || plugin.getLinkService() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("FoDiscordBot is unavailable"));
        }
        if (discordUserId == null || discordUserId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("discordUserId is required"));
        }
        return plugin.getLinkService().unlinkExternallyByDiscordId(discordUserId).thenApply(PublicApiService::unlinkResult);
    }

    private static UnlinkRequestResult unlinkResult(me.foesio.foDiscordBot.model.UnlinkResult result) {
        return result.status() == me.foesio.foDiscordBot.model.UnlinkResult.Status.SUCCESS
                ? new UnlinkRequestResult(UnlinkRequestResult.Status.UNLINKED, identity(result.account()))
                : new UnlinkRequestResult(UnlinkRequestResult.Status.NOT_LINKED, null);
    }

    public static LinkedIdentity identity(LinkedAccount account) {
        return new LinkedIdentity(account.playerUuid(), account.playerName(), account.discordUserId(),
                account.discordUsername(), account.discordDisplayName(), account.linkedAt());
    }
}
