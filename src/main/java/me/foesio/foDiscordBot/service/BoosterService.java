package me.foesio.foDiscordBot.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.foesio.core.message.FoMessageService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import me.foesio.core.command.CommandPlaceholders;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.util.BukkitFutures;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class BoosterService {

    private final FoDiscordBot plugin;
    private final LinkRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Set<String> dispatchInProgress = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedWarnings = ConcurrentHashMap.newKeySet();

    public BoosterService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public void handlePlayerJoin(Player player) {
        if (!plugin.getPluginConfig().boosterEnabled()) {
            return;
        }

        BoosterRuntimeConfig runtimeConfig = runtimeConfig();
        if (runtimeConfig == null) {
            return;
        }

        String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
        String lockKey = player.getUniqueId() + "|" + gamemodeId;
        if (!dispatchInProgress.add(lockKey)) {
            return;
        }

        BukkitFutures.supplyAsync(plugin, () -> loadJoinAction(player, gamemodeId, runtimeConfig))
                .thenCompose(action -> applyJoinAction(action, gamemodeId))
                .whenComplete((result, throwable) -> {
                    dispatchInProgress.remove(lockKey);
                    if (throwable != null) {
                        plugin.logWarning("Failed to process booster rewards for " + player.getName() + ": " + throwable.getMessage());
                        return;
                    }
                    if (result == RewardApplyResult.GRANTED && player.isOnline()) {
                        sendMessage(player, "booster.success", gamemodeId);
                    } else if (result == RewardApplyResult.REMOVED && player.isOnline()) {
                        sendMessage(player, "booster.removed", gamemodeId);
                    }
                });
    }

    public CompletableFuture<Void> markLinkBroken(LinkedAccount account, String gamemodeId) {
        if (!plugin.getPluginConfig().boosterEnabled() || account == null) {
            return CompletableFuture.completedFuture(null);
        }

        return loadStateFuture(account.playerUuid(), gamemodeId).thenCompose(state -> {
            if (!state.currentBooster()) {
                return CompletableFuture.completedFuture(null);
            }
            return saveState(account.playerUuid(), gamemodeId, state.withBrokenLink()).thenApply(ignored -> null);
        });
    }

    private BoosterRuntimeConfig runtimeConfig() {
        String roleId = plugin.getPluginConfig().boosterRoleId();
        String guildId = plugin.getPluginConfig().normalizedGuildId();
        if (roleId.isBlank()) {
            warnOnce("booster-role-missing", "Booster rewards are enabled but booster.role-id is blank.");
            return null;
        }
        if (guildId == null) {
            warnOnce("booster-guild-missing", "Booster rewards are enabled but discord.command-guild-id is blank.");
            return null;
        }
        if (!plugin.getPluginConfig().hasConfiguredBotToken()) {
            warnOnce("booster-token-missing", "Booster rewards are enabled but discord.token is not configured.");
            return null;
        }
        return new BoosterRuntimeConfig(guildId, roleId);
    }

    private JoinAction loadJoinAction(Player player, String gamemodeId, BoosterRuntimeConfig runtimeConfig) {
        try {
            repository.updatePlayerSnapshot(player.getUniqueId(), player.getName(), Instant.now());
            LinkedAccount account = repository.findByPlayerUuid(player.getUniqueId()).orElse(null);
            LinkRepository.BoosterRewardState state = loadState(player.getUniqueId(), gamemodeId);

            if (account == null || !account.isLinked() || account.discordUserId() == null || account.discordUserId().isBlank()) {
                return state.currentBooster()
                        ? JoinAction.remove(removalContext(player, state), state)
                        : JoinAction.skip();
            }

            boolean hasRole = discordMemberHasRole(account.discordUserId(), runtimeConfig.guildId(), runtimeConfig.roleId());
            if (state.currentBooster() && state.activeLinkBroken()) {
                return hasRole
                        ? JoinAction.replace(removalContext(player, state), account, state)
                        : JoinAction.remove(removalContext(player, state), state);
            }
            if (!hasRole) {
                return state.currentBooster()
                        ? JoinAction.remove(removalContext(player, state), state)
                        : JoinAction.skip();
            }

            if (state.currentBooster() && !isActiveDiscord(state, account.discordUserId())) {
                return JoinAction.replace(removalContext(player, state), account, state);
            }
            return JoinAction.grant(account, state);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private LinkRepository.BoosterRewardState loadState(UUID playerUuid, String gamemodeId) throws Exception {
        return repository.findBoosterRewardState(
                playerUuid,
                gamemodeId,
                plugin.getPluginConfig().boosterAlwaysRewardCommands(),
                plugin.getPluginConfig().boosterRemovalCommands()
        ).orElse(new LinkRepository.BoosterRewardState(
                false, null, null, null, false,
                List.of(), "", 0,
                List.of(), "", 0
        ));
    }

    private CompletableFuture<RewardApplyResult> applyJoinAction(JoinAction action, String gamemodeId) {
        return switch (action.type()) {
            case SKIP -> CompletableFuture.completedFuture(RewardApplyResult.SKIPPED);
            case GRANT -> applyGrant(action.rewardAccount(), action.state(), gamemodeId);
            case REMOVE -> applyRemoval(action.removalContext(), action.state(), gamemodeId);
            case REPLACE -> applyRemoval(action.removalContext(), action.state(), gamemodeId)
                    .thenCompose(result -> {
                        if (result != RewardApplyResult.REMOVED) {
                            return CompletableFuture.completedFuture(result);
                        }
                        return loadStateFuture(action.rewardAccount().playerUuid(), gamemodeId)
                                .thenCompose(updated -> applyGrant(action.rewardAccount(), updated, gamemodeId));
                    });
        };
    }

    private CompletableFuture<LinkRepository.BoosterRewardState> loadStateFuture(UUID playerUuid, String gamemodeId) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return loadState(playerUuid, gamemodeId);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private CompletableFuture<RewardApplyResult> applyGrant(LinkedAccount account, LinkRepository.BoosterRewardState state, String gamemodeId) {
        return applyOneTimeReward(account, gamemodeId).thenCompose(oneTimeResult -> {
            if (!oneTimeResult.finished()) {
                return CompletableFuture.completedFuture(RewardApplyResult.FAILED);
            }
            return applyAlwaysReward(account, state, gamemodeId)
                    .thenApply(alwaysResult -> oneTimeResult.changed() || alwaysResult.changed()
                            ? RewardApplyResult.GRANTED
                            : RewardApplyResult.SKIPPED);
        });
    }

    private CompletableFuture<BatchApplyResult> applyOneTimeReward(LinkedAccount account, String gamemodeId) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findBoosterOneTimeRewardState(
                        account.discordUserId(),
                        gamemodeId,
                        plugin.getPluginConfig().boosterOneTimeRewardCommands()
                ).orElse(new LinkRepository.RewardClaimState(List.of(), "", 0, false));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(state -> {
            if (state.claimed()) {
                return CompletableFuture.completedFuture(new BatchApplyResult(true, false));
            }
            if (state.rewardCommands().isEmpty()) {
                return saveOneTime(account.discordUserId(), gamemodeId, null, 0, true)
                        .thenApply(ignored -> new BatchApplyResult(true, false));
            }

            return BukkitFutures.supplySync(plugin, () -> dispatchCommands(CommandContext.fromAccount(account), state.rewardCommands(), state.nextCommandIndex(), gamemodeId, "Booster one-time reward"))
                    .thenCompose(summary -> {
                        int nextIndex = state.nextCommandIndex() + summary.completedCommands();
                        boolean finished = summary.finishedAll();
                        String snapshot = finished ? null : state.encodedCommandSnapshot();
                        return saveOneTime(account.discordUserId(), gamemodeId, snapshot, finished ? 0 : nextIndex, finished)
                                .thenApply(ignored -> new BatchApplyResult(finished, summary.completedCommands() > 0));
                    });
        });
    }

    private CompletableFuture<Void> saveOneTime(
            String discordUserId,
            String gamemodeId,
            String encodedSnapshot,
            int nextIndex,
            boolean claimed
    ) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.updateBoosterOneTimeRewardProgress(discordUserId, gamemodeId, encodedSnapshot, nextIndex, claimed, Instant.now());
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private CompletableFuture<BatchApplyResult> applyAlwaysReward(LinkedAccount account, LinkRepository.BoosterRewardState state, String gamemodeId) {
        if (state.currentBooster() && isActiveDiscord(state, account.discordUserId())) {
            return CompletableFuture.completedFuture(new BatchApplyResult(true, false));
        }

        if (state.alwaysCommands().isEmpty()) {
            LinkRepository.BoosterRewardState updated = state.withAlwaysProgress(account, null, 0, true);
            return saveState(account.playerUuid(), gamemodeId, updated)
                    .thenApply(saved -> new BatchApplyResult(true, false));
        }

        return BukkitFutures.supplySync(plugin, () -> dispatchCommands(CommandContext.fromAccount(account), state.alwaysCommands(), state.nextAlwaysCommandIndex(), gamemodeId, "Booster always reward"))
                .thenCompose(summary -> {
                    int nextIndex = state.nextAlwaysCommandIndex() + summary.completedCommands();
                    boolean finished = summary.finishedAll();
                    LinkRepository.BoosterRewardState updated = state.withAlwaysProgress(
                            account,
                            finished ? null : state.encodedAlwaysCommandSnapshot(),
                            finished ? 0 : nextIndex,
                            finished
                    );
                    return saveState(account.playerUuid(), gamemodeId, updated)
                            .thenApply(saved -> new BatchApplyResult(finished, summary.completedCommands() > 0));
                });
    }

    private CompletableFuture<RewardApplyResult> applyRemoval(CommandContext context, LinkRepository.BoosterRewardState state, String gamemodeId) {
        if (state.removalCommands().isEmpty()) {
            LinkRepository.BoosterRewardState updated = state.inactive();
            return saveState(context.playerUuid(), gamemodeId, updated).thenApply(saved -> RewardApplyResult.REMOVED);
        }

        return BukkitFutures.supplySync(plugin, () -> dispatchCommands(context, state.removalCommands(), state.nextRemovalCommandIndex(), gamemodeId, "Booster removal"))
                .thenCompose(summary -> {
                    int nextIndex = state.nextRemovalCommandIndex() + summary.completedCommands();
                    boolean finished = summary.finishedAll();
                    LinkRepository.BoosterRewardState updated = state.withRemovalProgress(
                            finished ? null : state.encodedRemovalCommandSnapshot(),
                            finished ? 0 : nextIndex,
                            !finished
                    );
                    return saveState(context.playerUuid(), gamemodeId, updated)
                            .thenApply(saved -> finished ? RewardApplyResult.REMOVED : RewardApplyResult.FAILED);
                });
    }

    private CompletableFuture<LinkRepository.BoosterRewardState> saveState(
            UUID playerUuid,
            String gamemodeId,
            LinkRepository.BoosterRewardState state
    ) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.saveBoosterRewardState(playerUuid, gamemodeId, state, Instant.now());
                return state;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private boolean discordMemberHasRole(String discordUserId, String guildId, String roleId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://discord.com/api/v10/guilds/" + guildId + "/members/" + discordUserId
                ))
                .header("Authorization", "Bot " + plugin.getPluginConfig().botToken())
                .header("User-Agent", "FoDiscordBot/3.9")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return false;
        }
        if (response.statusCode() == 200) {
            return response.body() != null && response.body().contains("\"" + roleId + "\"");
        }

        warnOnce("booster-http-" + response.statusCode(),
                "Discord booster role check failed with HTTP " + response.statusCode() + ".");
        return false;
    }

    private RewardDispatchSummary dispatchCommands(
            CommandContext context,
            List<String> commands,
            int startIndex,
            String gamemodeId,
            String label
    ) {
        int completedCommands = 0;
        for (int index = startIndex; index < commands.size(); index++) {
            String parsed = CommandPlaceholders.apply(commands.get(index), Map.of(
                    "player_name", context.playerName(),
                    "player_uuid", context.playerUuid().toString(),
                    "discord_id", safe(context.discordUserId()),
                    "discord_name", safe(context.discordUsername()),
                    "discord_display_name", safe(context.discordDisplayName()),
                    "gamemode", gamemodeId
            ));
            try {
                boolean handled = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                if (!handled) {
                    plugin.logWarning(label + " command returned false for " + context.playerName() + ": " + parsed);
                    return new RewardDispatchSummary(false, completedCommands);
                }
            } catch (Throwable throwable) {
                plugin.logWarning(label + " command failed for " + context.playerName() + ": " + parsed + " (" + throwable.getMessage() + ")");
                return new RewardDispatchSummary(false, completedCommands);
            }
            completedCommands++;
        }
        return new RewardDispatchSummary(true, completedCommands);
    }

    private CommandContext removalContext(Player player, LinkRepository.BoosterRewardState state) {
        return new CommandContext(
                player.getUniqueId(),
                player.getName(),
                state.activeDiscordUserId(),
                state.activeDiscordUsername(),
                state.activeDiscordDisplayName()
        );
    }

    private boolean isActiveDiscord(LinkRepository.BoosterRewardState state, String discordUserId) {
        return state.activeDiscordUserId() != null && state.activeDiscordUserId().equals(discordUserId);
    }

    private void sendMessage(Player player, String path, String gamemodeId) {
        plugin.getCore().scheduler().runForPlayer(player, () -> plugin.messages().send(
                player,
                "ingame." + path,
                FoMessageService.missingMessageFallback("ingame." + path),
                Map.of("gamemode", gamemodeId)
        ));
    }

    private void warnOnce(String key, String message) {
        if (loggedWarnings.add(key)) {
            plugin.logWarning(message);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record BoosterRuntimeConfig(String guildId, String roleId) {
    }

    private record RewardDispatchSummary(boolean finishedAll, int completedCommands) {
    }

    private record BatchApplyResult(boolean finished, boolean changed) {
    }

    private enum JoinActionType {
        SKIP,
        GRANT,
        REMOVE,
        REPLACE
    }

    private record JoinAction(
            JoinActionType type,
            CommandContext removalContext,
            LinkedAccount rewardAccount,
            LinkRepository.BoosterRewardState state
    ) {
        private static JoinAction skip() {
            return new JoinAction(JoinActionType.SKIP, null, null, null);
        }

        private static JoinAction grant(LinkedAccount account, LinkRepository.BoosterRewardState state) {
            return new JoinAction(JoinActionType.GRANT, null, account, state);
        }

        private static JoinAction remove(CommandContext context, LinkRepository.BoosterRewardState state) {
            return new JoinAction(JoinActionType.REMOVE, context, null, state);
        }

        private static JoinAction replace(CommandContext context, LinkedAccount account, LinkRepository.BoosterRewardState state) {
            return new JoinAction(JoinActionType.REPLACE, context, account, state);
        }
    }

    private record CommandContext(
            UUID playerUuid,
            String playerName,
            String discordUserId,
            String discordUsername,
            String discordDisplayName
    ) {
        private static CommandContext fromAccount(LinkedAccount account) {
            return new CommandContext(
                    account.playerUuid(),
                    account.playerName(),
                    account.discordUserId(),
                    account.discordUsername(),
                    account.discordDisplayName()
            );
        }
    }

    private enum RewardApplyResult {
        GRANTED,
        REMOVED,
        FAILED,
        SKIPPED
    }
}
