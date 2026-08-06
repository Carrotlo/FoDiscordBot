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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.foesio.core.command.CommandPlaceholders;
import me.foesio.core.number.TickDuration;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.DiscordUserSnapshot;
import me.foesio.foDiscordBot.model.LinkCompletionResult;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.model.PendingLinkCode;
import me.foesio.foDiscordBot.model.UnlinkResult;
import me.foesio.foDiscordBot.util.BukkitFutures;
import me.foesio.foDiscordBot.util.CoreRepeatingTask;
import net.dv8tion.jda.api.entities.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class LinkService {

    private final FoDiscordBot plugin;
    private final LinkRepository repository;
    private final Map<UUID, Instant> ingameCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Instant> discordCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> rewardDispatchInProgress = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Set<String> loggedWarnings = ConcurrentHashMap.newKeySet();

    private CoreRepeatingTask cleanupTask;

    public LinkService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public void start() {
        rescheduleCleanup();
        BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.cleanupExpiredCodes();
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).exceptionally(throwable -> {
            plugin.logWarning("Failed to run initial link-code cleanup: " + throwable.getMessage());
            return null;
        });
    }

    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    public void reload() {
        rescheduleCleanup();
    }

    public CompletableFuture<LinkCodeResponse> createLinkCode(Player player) {
        Instant now = Instant.now();
        Duration remaining = getRemainingCooldown(ingameCooldowns, player.getUniqueId(), plugin.getPluginConfig().ingameCommandCooldown(), now);
        if (!remaining.isZero() && !remaining.isNegative()) {
            return CompletableFuture.completedFuture(new LinkCodeResponse(LinkCodeStatus.COOLDOWN, null, remaining));
        }

        ingameCooldowns.put(player.getUniqueId(), now);
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.updatePlayerSnapshot(player.getUniqueId(), player.getName(), now);
                Optional<LinkedAccount> existing = repository.findByPlayerUuid(player.getUniqueId());
                if (existing.isPresent() && existing.get().isLinked()) {
                    ingameCooldowns.remove(player.getUniqueId());
                    return new LinkCodeResponse(LinkCodeStatus.ALREADY_LINKED, null, Duration.ZERO);
                }

                PendingLinkCode pendingLinkCode = repository.createOrReplacePendingCode(
                        player.getUniqueId(),
                        player.getName(),
                        now,
                        plugin.getPluginConfig().codeExpiry(),
                        plugin.getPluginConfig().codeLength()
                );
                return new LinkCodeResponse(LinkCodeStatus.SUCCESS, pendingLinkCode, Duration.ZERO);
            } catch (Exception exception) {
                ingameCooldowns.remove(player.getUniqueId());
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletableFuture<UnlinkResponse> unlink(Player player) {
        String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.updatePlayerSnapshot(player.getUniqueId(), player.getName(), Instant.now());
                return repository.findByPlayerUuid(player.getUniqueId()).orElse(null);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(account -> {
            if (account == null || !account.isLinked()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> linkRewards = processUnlinkRewards(account, gamemodeId);
            CompletableFuture<Void> boosterUpdate = plugin.getBoosterService() == null
                    ? CompletableFuture.completedFuture(null)
                    : plugin.getBoosterService().markLinkBroken(account, gamemodeId);
            return linkRewards.thenCompose(ignored -> boosterUpdate);
        }).thenCompose(ignored -> BukkitFutures.supplyAsync(plugin, () -> {
            try {
                UnlinkResult unlinkResult = repository.unlinkPlayer(player.getUniqueId(), player.getName(), Instant.now());
                if (unlinkResult.status() == UnlinkResult.Status.SUCCESS) {
                    removeLinkedRole(unlinkResult.account());
                }
                return switch (unlinkResult.status()) {
                    case SUCCESS -> new UnlinkResponse(UnlinkStatus.SUCCESS);
                    case NOT_LINKED -> new UnlinkResponse(UnlinkStatus.NOT_LINKED);
                };
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }));
    }

    public CompletableFuture<DiscordLinkResponse> completeDiscordLink(String code, User user) {
        Instant now = Instant.now();
        Duration remaining = getRemainingCooldown(discordCooldowns, user.getId(), plugin.getPluginConfig().discordCommandCooldown(), now);
        if (!remaining.isZero() && !remaining.isNegative()) {
            return CompletableFuture.completedFuture(new DiscordLinkResponse(DiscordLinkStatus.COOLDOWN, null, remaining));
        }

        discordCooldowns.put(user.getId(), now);
        DiscordUserSnapshot snapshot = new DiscordUserSnapshot(
                user.getId(),
                user.getName(),
                user.getGlobalName() != null ? user.getGlobalName() : user.getName(),
                user.getAsMention()
        );

        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.completeLink(code.trim().toUpperCase(), snapshot, Instant.now(), plugin.getPluginConfig().linkOneTimeRewardCommands());
            } catch (Exception exception) {
                discordCooldowns.remove(user.getId());
                throw new RuntimeException(exception);
            }
        }).thenApply(result -> {
            DiscordLinkResponse response = mapDiscordResult(result);
            if (response.status() == DiscordLinkStatus.SUCCESS) {
                addLinkedRole(response.account());
                claimOnlineGamemodeReward(response.account());
                syncOnlineRankRoles(response.account());
            }
            return response;
        });
    }

    public CompletableFuture<DiscordUnlinkResponse> unlinkDiscordUser(User user) {
        String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findLinkedByDiscordId(user.getId()).orElse(null);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(account -> {
            if (account == null || !account.isLinked()) {
                return CompletableFuture.completedFuture(new DiscordUnlinkResponse(DiscordUnlinkStatus.NOT_LINKED, null));
            }
            CompletableFuture<Void> linkRewards = processUnlinkRewards(account, gamemodeId);
            CompletableFuture<Void> boosterUpdate = plugin.getBoosterService() == null
                    ? CompletableFuture.completedFuture(null)
                    : plugin.getBoosterService().markLinkBroken(account, gamemodeId);
            return linkRewards.thenCompose(ignored -> boosterUpdate).thenCompose(ignored -> BukkitFutures.supplyAsync(plugin, () -> {
                try {
                    UnlinkResult unlinkResult = repository.unlinkPlayer(account.playerUuid(), account.playerName(), Instant.now());
                    if (unlinkResult.status() == UnlinkResult.Status.SUCCESS) {
                        removeLinkedRole(unlinkResult.account());
                        return new DiscordUnlinkResponse(DiscordUnlinkStatus.SUCCESS, unlinkResult.account());
                    }
                    return new DiscordUnlinkResponse(DiscordUnlinkStatus.NOT_LINKED, null);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }));
        });
    }

    public CompletableFuture<LinkRewardResponse> claimGamemodeReward(Player player) {
        String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
        String lockKey = player.getUniqueId() + "|" + gamemodeId;
        if (rewardDispatchInProgress.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(new LinkRewardResponse(LinkRewardStatus.FAILED));
        }

        return BukkitFutures.supplyAsync(plugin, () -> loadLinkAction(player, gamemodeId))
                .thenCompose(action -> applyLinkAction(action, gamemodeId))
                .whenComplete((ignored, throwable) -> rewardDispatchInProgress.remove(lockKey));
    }

    public void handlePlayerJoin(Player player) {
        claimGamemodeRewardAutomatically(player, "on join");
    }

    public void recordPlayerSnapshot(Player player) {
        BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.updatePlayerSnapshot(player.getUniqueId(), player.getName(), Instant.now());
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).exceptionally(throwable -> {
            plugin.logWarning("Failed to update player snapshot for " + player.getName() + ": " + throwable.getMessage());
            return null;
        });
    }

    public LinkRepository repository() {
        return repository;
    }

    private void claimOnlineGamemodeReward(LinkedAccount account) {
        if (account == null) {
            return;
        }

        plugin.getCore().scheduler().runGlobal(() -> {
            Player player = Bukkit.getPlayer(account.playerUuid());
            if (player == null || !player.isOnline()) {
                return;
            }
            claimGamemodeRewardAutomatically(player, "after linking");
        });
    }

    private void syncOnlineRankRoles(LinkedAccount account) {
        if (account == null || plugin.getRankSyncService() == null) {
            return;
        }

        plugin.getCore().scheduler().runGlobal(() -> {
            Player player = Bukkit.getPlayer(account.playerUuid());
            if (player == null || !player.isOnline()) {
                return;
            }
            plugin.getRankSyncService().handlePlayerJoin(player);
        });
    }

    private void claimGamemodeRewardAutomatically(Player player, String context) {
        claimGamemodeReward(player).whenComplete((response, throwable) -> {
            if (throwable != null) {
                plugin.logWarning("Failed to process link rewards for " + player.getName() + " " + context + ": " + throwable.getMessage());
                return;
            }
            if (response == null) {
                return;
            }

            plugin.getCore().scheduler().runForPlayer(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (response.status() == LinkRewardStatus.SUCCESS) {
                    plugin.messages().send(player, "ingame.rewards.success",
                            FoMessageService.missingMessageFallback("ingame.rewards.success"), Map.of(
                            "gamemode", plugin.getPluginConfig().normalizedGamemodeId()
                    ));
                }
                if (response.status() == LinkRewardStatus.SUCCESS || response.status() == LinkRewardStatus.ALREADY_CLAIMED) {
                    plugin.getNetworkSyncService().syncPlayerNow(player);
                }
            });
        });
    }

    private CompletableFuture<Void> processUnlinkRewards(LinkedAccount account, String gamemodeId) {
        if (account == null || !account.isLinked()) {
            return CompletableFuture.completedFuture(null);
        }

        String lockKey = account.playerUuid() + "|" + gamemodeId;
        if (rewardDispatchInProgress.putIfAbsent(lockKey, Boolean.TRUE) != null) {
            return CompletableFuture.completedFuture(null);
        }

        return loadLinkStateFuture(account.playerUuid(), gamemodeId)
                .thenCompose(state -> applyUnlinkReward(unlinkContext(account.playerUuid(), account.playerName(), state, account), state, gamemodeId))
                .exceptionally(throwable -> {
                    plugin.logWarning("Failed to process unlink rewards for " + account.playerName() + ": " + throwable.getMessage());
                    return RewardApplyResult.FAILED;
                })
                .thenAccept(ignored -> {
                })
                .whenComplete((ignored, throwable) -> rewardDispatchInProgress.remove(lockKey));
    }

    private LinkRewardAction loadLinkAction(Player player, String gamemodeId) {
        try {
            repository.updatePlayerSnapshot(player.getUniqueId(), player.getName(), Instant.now());
            LinkedAccount account = repository.findByPlayerUuid(player.getUniqueId()).orElse(null);
            LinkRepository.LinkRewardState state = loadLinkState(player.getUniqueId(), gamemodeId);

            if (account == null || !account.isLinked()) {
                return state.currentLink()
                        ? LinkRewardAction.unlink(unlinkContext(player.getUniqueId(), player.getName(), state, null), state)
                        : LinkRewardAction.skip(LinkRewardStatus.NOT_LINKED);
            }

            if (state.currentLink() && !isActiveDiscord(state, account.discordUserId())) {
                return LinkRewardAction.replace(unlinkContext(player.getUniqueId(), player.getName(), state, account), account, state);
            }
            return LinkRewardAction.grant(account, state);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private LinkRepository.LinkRewardState loadLinkState(UUID playerUuid, String gamemodeId) throws Exception {
        return repository.findLinkRewardState(
                playerUuid,
                gamemodeId,
                plugin.getPluginConfig().linkAlwaysRewardCommands(),
                plugin.getPluginConfig().linkUnlinkCommands()
        ).orElse(new LinkRepository.LinkRewardState(
                false, null, null, null,
                List.of(), "", 0,
                List.of(), "", 0
        ));
    }

    private CompletableFuture<LinkRepository.LinkRewardState> loadLinkStateFuture(UUID playerUuid, String gamemodeId) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return loadLinkState(playerUuid, gamemodeId);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private CompletableFuture<LinkRewardResponse> applyLinkAction(LinkRewardAction action, String gamemodeId) {
        return switch (action.type()) {
            case SKIP -> CompletableFuture.completedFuture(new LinkRewardResponse(action.skipStatus()));
            case GRANT -> applyGrant(action.rewardAccount(), action.state(), gamemodeId).thenApply(this::mapGrantResult);
            case UNLINK -> applyUnlinkReward(action.unlinkContext(), action.state(), gamemodeId)
                    .thenApply(result -> result == RewardApplyResult.FAILED
                            ? new LinkRewardResponse(LinkRewardStatus.FAILED)
                            : new LinkRewardResponse(LinkRewardStatus.NOT_LINKED));
            case REPLACE -> applyUnlinkReward(action.unlinkContext(), action.state(), gamemodeId)
                    .thenCompose(result -> {
                        if (result == RewardApplyResult.FAILED) {
                            return CompletableFuture.completedFuture(new LinkRewardResponse(LinkRewardStatus.FAILED));
                        }
                        return loadLinkStateFuture(action.rewardAccount().playerUuid(), gamemodeId)
                                .thenCompose(updated -> applyGrant(action.rewardAccount(), updated, gamemodeId))
                                .thenApply(this::mapGrantResult);
                    });
        };
    }

    private LinkRewardResponse mapGrantResult(RewardApplyResult result) {
        return switch (result) {
            case GRANTED -> new LinkRewardResponse(LinkRewardStatus.SUCCESS);
            case FAILED -> new LinkRewardResponse(LinkRewardStatus.FAILED);
            case REMOVED, SKIPPED -> new LinkRewardResponse(LinkRewardStatus.ALREADY_CLAIMED);
        };
    }

    private CompletableFuture<RewardApplyResult> applyGrant(LinkedAccount account, LinkRepository.LinkRewardState state, String gamemodeId) {
        return applyOneTimeReward(account, gamemodeId).thenCompose(oneTimeResult -> {
            if (!oneTimeResult.finished()) {
                return CompletableFuture.completedFuture(RewardApplyResult.FAILED);
            }
            return applyAlwaysReward(account, state, gamemodeId)
                    .thenApply(alwaysResult -> {
                        if (!alwaysResult.finished()) {
                            return RewardApplyResult.FAILED;
                        }
                        return oneTimeResult.changed() || alwaysResult.changed()
                                ? RewardApplyResult.GRANTED
                                : RewardApplyResult.SKIPPED;
                    });
        });
    }

    private CompletableFuture<BatchApplyResult> applyOneTimeReward(LinkedAccount account, String gamemodeId) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findLinkOneTimeRewardState(
                        account.discordUserId(),
                        gamemodeId,
                        plugin.getPluginConfig().linkOneTimeRewardCommands()
                ).orElse(new LinkRepository.RewardClaimState(List.of(), "", 0, false));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(state -> {
            if (state.claimed()) {
                return CompletableFuture.completedFuture(new BatchApplyResult(true, false));
            }
            if (state.rewardCommands().isEmpty()) {
                return saveLinkOneTime(account.discordUserId(), gamemodeId, null, 0, true)
                        .thenApply(ignored -> new BatchApplyResult(true, false));
            }

            return BukkitFutures.supplySync(plugin, () -> dispatchCommands(CommandContext.fromAccount(account), state.rewardCommands(), state.nextCommandIndex(), gamemodeId, "Link first-time reward"))
                    .thenCompose(summary -> {
                        int nextIndex = state.nextCommandIndex() + summary.completedCommands();
                        boolean finished = summary.finishedAll();
                        String snapshot = finished ? null : state.encodedCommandSnapshot();
                        return saveLinkOneTime(account.discordUserId(), gamemodeId, snapshot, finished ? 0 : nextIndex, finished)
                                .thenApply(ignored -> new BatchApplyResult(finished, summary.completedCommands() > 0));
                    });
        });
    }

    private CompletableFuture<Void> saveLinkOneTime(
            String discordUserId,
            String gamemodeId,
            String encodedSnapshot,
            int nextIndex,
            boolean claimed
    ) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.updateLinkOneTimeRewardProgress(discordUserId, gamemodeId, encodedSnapshot, nextIndex, claimed, Instant.now());
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private CompletableFuture<BatchApplyResult> applyAlwaysReward(LinkedAccount account, LinkRepository.LinkRewardState state, String gamemodeId) {
        if (state.currentLink() && isActiveDiscord(state, account.discordUserId())) {
            return CompletableFuture.completedFuture(new BatchApplyResult(true, false));
        }

        if (state.alwaysCommands().isEmpty()) {
            LinkRepository.LinkRewardState updated = state.withAlwaysProgress(account, null, 0, true);
            return saveLinkState(account.playerUuid(), gamemodeId, updated)
                    .thenApply(saved -> new BatchApplyResult(true, false));
        }

        return BukkitFutures.supplySync(plugin, () -> dispatchCommands(CommandContext.fromAccount(account), state.alwaysCommands(), state.nextAlwaysCommandIndex(), gamemodeId, "Link always reward"))
                .thenCompose(summary -> {
                    int nextIndex = state.nextAlwaysCommandIndex() + summary.completedCommands();
                    boolean finished = summary.finishedAll();
                    LinkRepository.LinkRewardState updated = state.withAlwaysProgress(
                            account,
                            finished ? null : state.encodedAlwaysCommandSnapshot(),
                            finished ? 0 : nextIndex,
                            finished
                    );
                    return saveLinkState(account.playerUuid(), gamemodeId, updated)
                            .thenApply(saved -> new BatchApplyResult(finished, summary.completedCommands() > 0));
                });
    }

    private CompletableFuture<RewardApplyResult> applyUnlinkReward(CommandContext context, LinkRepository.LinkRewardState state, String gamemodeId) {
        if (state.unlinkCommands().isEmpty()) {
            return saveLinkState(context.playerUuid(), gamemodeId, state.inactive())
                    .thenApply(saved -> RewardApplyResult.REMOVED);
        }

        return BukkitFutures.supplySync(plugin, () -> dispatchCommands(context, state.unlinkCommands(), state.nextUnlinkCommandIndex(), gamemodeId, "Link unlink reward"))
                .thenCompose(summary -> {
                    int nextIndex = state.nextUnlinkCommandIndex() + summary.completedCommands();
                    boolean finished = summary.finishedAll();
                    LinkRepository.LinkRewardState updated = state.withUnlinkProgress(
                            finished ? null : state.encodedUnlinkCommandSnapshot(),
                            finished ? 0 : nextIndex,
                            !finished
                    );
                    return saveLinkState(context.playerUuid(), gamemodeId, updated)
                            .thenApply(saved -> finished ? RewardApplyResult.REMOVED : RewardApplyResult.FAILED);
                });
    }

    private CompletableFuture<LinkRepository.LinkRewardState> saveLinkState(
            UUID playerUuid,
            String gamemodeId,
            LinkRepository.LinkRewardState state
    ) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                repository.saveLinkRewardState(playerUuid, gamemodeId, state, Instant.now());
                return state;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
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

    private CommandContext unlinkContext(UUID playerUuid, String playerName, LinkRepository.LinkRewardState state, LinkedAccount fallbackAccount) {
        if (state.activeDiscordUserId() != null && !state.activeDiscordUserId().isBlank()) {
            return new CommandContext(
                    playerUuid,
                    playerName,
                    state.activeDiscordUserId(),
                    state.activeDiscordUsername(),
                    state.activeDiscordDisplayName()
            );
        }
        if (fallbackAccount != null) {
            return CommandContext.fromAccount(fallbackAccount);
        }
        return new CommandContext(playerUuid, playerName, null, null, null);
    }

    private boolean isActiveDiscord(LinkRepository.LinkRewardState state, String discordUserId) {
        return state.activeDiscordUserId() != null && state.activeDiscordUserId().equals(discordUserId);
    }

    private DiscordLinkResponse mapDiscordResult(LinkCompletionResult result) {
        return switch (result.status()) {
            case SUCCESS -> new DiscordLinkResponse(DiscordLinkStatus.SUCCESS, result.account(), Duration.ZERO);
            case INVALID_CODE -> new DiscordLinkResponse(DiscordLinkStatus.INVALID_CODE, null, Duration.ZERO);
            case EXPIRED_CODE -> new DiscordLinkResponse(DiscordLinkStatus.EXPIRED_CODE, null, Duration.ZERO);
            case DISCORD_ALREADY_LINKED -> new DiscordLinkResponse(DiscordLinkStatus.DISCORD_ALREADY_LINKED, result.account(), Duration.ZERO);
            case PLAYER_ALREADY_LINKED -> new DiscordLinkResponse(DiscordLinkStatus.PLAYER_ALREADY_LINKED, result.account(), Duration.ZERO);
        };
    }

    private void addLinkedRole(LinkedAccount account) {
        updateLinkedRole(account, LinkedRoleOperation.ADD);
    }

    private void removeLinkedRole(LinkedAccount account) {
        updateLinkedRole(account, LinkedRoleOperation.REMOVE);
    }

    private void updateLinkedRole(LinkedAccount account, LinkedRoleOperation operation) {
        String roleId = plugin.getPluginConfig().normalizedLinkedRoleId();
        if (roleId == null) {
            return;
        }

        String guildId = plugin.getPluginConfig().normalizedGuildId();
        if (guildId == null) {
            warnOnce("linked-role-guild-missing", "linking.linked-role-id is set but discord.command-guild-id is blank.");
            return;
        }
        if (!plugin.getPluginConfig().hasConfiguredBotToken()) {
            warnOnce("linked-role-token-missing", "linking.linked-role-id is set but discord.token is not configured.");
            return;
        }
        if (account == null || account.discordUserId() == null || account.discordUserId().isBlank()) {
            return;
        }

        BukkitFutures.supplyAsync(plugin, () -> {
            try {
                applyLinkedRoleOperation(account.discordUserId(), guildId, roleId, operation);
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).exceptionally(throwable -> {
            plugin.logWarning("Failed to " + operation.logAction() + " linked Discord role for "
                    + account.playerName() + ": " + throwable.getMessage());
            return null;
        });
    }

    private void applyLinkedRoleOperation(
            String discordUserId,
            String guildId,
            String roleId,
            LinkedRoleOperation operation
    ) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(
                        "https://discord.com/api/v10/guilds/" + guildId + "/members/" + discordUserId + "/roles/" + roleId
                ))
                .header("Authorization", "Bot " + plugin.getPluginConfig().botToken())
                .header("User-Agent", "FoDiscordBot/3.9")
                .timeout(Duration.ofSeconds(10));

        HttpRequest request = switch (operation) {
            case ADD -> requestBuilder.PUT(HttpRequest.BodyPublishers.noBody()).build();
            case REMOVE -> requestBuilder.DELETE().build();
        };

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) {
            return;
        }
        if (response.statusCode() == 404) {
            warnOnce("linked-role-member-or-role-missing",
                    "Could not " + operation.logAction()
                            + " linked Discord role. The member or role was not found in the configured guild.");
            return;
        }
        if (response.statusCode() == 403) {
            warnOnce("linked-role-forbidden",
                    "Could not " + operation.logAction()
                            + " linked Discord role. The bot needs Manage Roles and a higher Discord role than the target role.");
            return;
        }

        warnOnce("linked-role-http-" + response.statusCode(),
                "Could not " + operation.logAction()
                        + " linked Discord role. Discord returned HTTP " + response.statusCode() + ".");
    }

    private void warnOnce(String key, String message) {
        if (loggedWarnings.add(key)) {
            plugin.logWarning(message);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void rescheduleCleanup() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        long intervalTicks = Math.max(20L, TickDuration.ofSeconds(plugin.getPluginConfig().cleanupInterval().toSeconds()).ticks());
        cleanupTask = new CoreRepeatingTask(plugin.getCore().scheduler(), () -> {
            try {
                repository.cleanupExpiredCodes();
            } catch (Exception exception) {
                plugin.logWarning("Failed to clean expired link codes: " + exception.getMessage());
            }
        }, intervalTicks, intervalTicks, true);
        cleanupTask.start();
    }

    private <T> Duration getRemainingCooldown(Map<T, Instant> cooldowns, T key, Duration cooldown, Instant now) {
        Instant previousUse = cooldowns.get(key);
        if (previousUse == null) {
            return Duration.ZERO;
        }
        Duration elapsed = Duration.between(previousUse, now);
        if (elapsed.compareTo(cooldown) >= 0) {
            cooldowns.remove(key);
            return Duration.ZERO;
        }
        return cooldown.minus(elapsed);
    }

    public enum LinkCodeStatus {
        SUCCESS,
        COOLDOWN,
        ALREADY_LINKED
    }

    public record LinkCodeResponse(LinkCodeStatus status, PendingLinkCode code, Duration remaining) {
    }

    public enum UnlinkStatus {
        SUCCESS,
        NOT_LINKED
    }

    public record UnlinkResponse(UnlinkStatus status) {
    }

    public enum DiscordLinkStatus {
        SUCCESS,
        COOLDOWN,
        INVALID_CODE,
        EXPIRED_CODE,
        DISCORD_ALREADY_LINKED,
        PLAYER_ALREADY_LINKED
    }

    public record DiscordLinkResponse(DiscordLinkStatus status, LinkedAccount account, Duration remaining) {
    }

    public enum DiscordUnlinkStatus {
        SUCCESS,
        NOT_LINKED
    }

    public record DiscordUnlinkResponse(DiscordUnlinkStatus status, LinkedAccount account) {
    }

    public enum LinkRewardStatus {
        SUCCESS,
        NOT_LINKED,
        ALREADY_CLAIMED,
        FAILED
    }

    public record LinkRewardResponse(LinkRewardStatus status) {
    }

    private record RewardDispatchSummary(boolean finishedAll, int completedCommands) {
    }

    private record BatchApplyResult(boolean finished, boolean changed) {
    }

    private enum LinkRewardActionType {
        SKIP,
        GRANT,
        UNLINK,
        REPLACE
    }

    private record LinkRewardAction(
            LinkRewardActionType type,
            LinkRewardStatus skipStatus,
            CommandContext unlinkContext,
            LinkedAccount rewardAccount,
            LinkRepository.LinkRewardState state
    ) {
        private static LinkRewardAction skip(LinkRewardStatus status) {
            return new LinkRewardAction(LinkRewardActionType.SKIP, status, null, null, null);
        }

        private static LinkRewardAction grant(LinkedAccount account, LinkRepository.LinkRewardState state) {
            return new LinkRewardAction(LinkRewardActionType.GRANT, null, null, account, state);
        }

        private static LinkRewardAction unlink(CommandContext context, LinkRepository.LinkRewardState state) {
            return new LinkRewardAction(LinkRewardActionType.UNLINK, null, context, null, state);
        }

        private static LinkRewardAction replace(CommandContext context, LinkedAccount account, LinkRepository.LinkRewardState state) {
            return new LinkRewardAction(LinkRewardActionType.REPLACE, null, context, account, state);
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

    private enum LinkedRoleOperation {
        ADD("add"),
        REMOVE("remove");

        private final String logAction;

        LinkedRoleOperation(String logAction) {
            this.logAction = logAction;
        }

        private String logAction() {
            return logAction;
        }
    }
}
