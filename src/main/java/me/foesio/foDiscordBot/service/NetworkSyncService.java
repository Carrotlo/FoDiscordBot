package me.foesio.foDiscordBot.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import me.foesio.core.number.TickDuration;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.util.CoreRepeatingTask;
import org.bukkit.entity.Player;

public final class NetworkSyncService {

    private final FoDiscordBot plugin;
    private final ProfileService profileService;
    private final LeaderboardService leaderboardService;
    private final AdvancementService advancementService;
    private final AtomicBoolean cycleRunning = new AtomicBoolean(false);

    private CoreRepeatingTask periodicTask;

    public NetworkSyncService(
            FoDiscordBot plugin,
            ProfileService profileService,
            LeaderboardService leaderboardService,
            AdvancementService advancementService
    ) {
        this.plugin = plugin;
        this.profileService = profileService;
        this.leaderboardService = leaderboardService;
        this.advancementService = advancementService;
    }

    public void start() {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return;
        }

        reschedule();
        runCycle();
    }

    public void reload() {
        shutdown();
        start();
    }

    public void shutdown() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
    }

    public void syncPlayerNow(Player player) {
        if (!plugin.getPluginConfig().networkEnabled() || player == null) {
            return;
        }

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        profileService.syncPlayerProfileSnapshot(player)
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        plugin.logWarning("Failed to sync profile snapshot for " + playerName + ": " + throwable.getMessage());
                    }
                    return null;
                })
                .thenCompose(ignored -> plugin.getPluginConfig().advancementEnabled()
                        ? advancementService.syncPlayerNow(playerUuid, playerName)
                        : CompletableFuture.completedFuture(null))
                .exceptionally(throwable -> {
                    plugin.logWarning("Failed to sync advancement snapshot for " + playerName + ": " + throwable.getMessage());
                    return null;
                });
    }

    private void reschedule() {
        if (periodicTask != null) {
            periodicTask.cancel();
        }

        long intervalTicks = Math.max(20L, TickDuration.ofSeconds(plugin.getPluginConfig().networkSyncInterval().toSeconds()).ticks());
        periodicTask = new CoreRepeatingTask(
                plugin.getCore().scheduler(),
                this::runCycle,
                intervalTicks,
                intervalTicks,
                true
        );
        periodicTask.start();
    }

    private void runCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return;
        }

        runSyncStep("leaderboard snapshots", leaderboardService::syncLocalLeaderboardsToNetwork)
                .thenCompose(ignored -> runSyncStep("profile snapshots", profileService::syncOnlineProfileSnapshots))
                .thenCompose(ignored -> plugin.getPluginConfig().advancementEnabled()
                        ? runSyncStep("advancement snapshots", advancementService::syncOnlinePlayersToNetwork)
                        : CompletableFuture.completedFuture(null))
                .whenComplete((ignored, throwable) -> {
                    cycleRunning.set(false);
                    if (throwable != null) {
                        plugin.logWarning("Network sync cycle failed: " + throwable.getMessage());
                    }
                });
    }

    private CompletableFuture<Void> runSyncStep(String name, Supplier<CompletableFuture<Void>> step) {
        try {
            return step.get().exceptionally(throwable -> {
                plugin.logWarning("Failed to sync " + name + ": " + throwable.getMessage());
                return null;
            });
        } catch (Exception exception) {
            plugin.logWarning("Failed to sync " + name + ": " + exception.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }
}
