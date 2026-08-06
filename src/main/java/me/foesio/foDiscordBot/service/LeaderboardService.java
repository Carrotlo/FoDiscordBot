package me.foesio.foDiscordBot.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import me.clip.placeholderapi.PlaceholderAPI;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.LeaderboardDefinition;
import me.foesio.foDiscordBot.model.LeaderboardView;
import me.foesio.foDiscordBot.util.BukkitFutures;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class LeaderboardService {

    private static final Pattern UNRESOLVED_PLACEHOLDER_PATTERN = Pattern.compile("%[^%\\s]+%");

    private final FoDiscordBot plugin;
    private final LinkRepository repository;
    private final Set<String> syncWarnings = ConcurrentHashMap.newKeySet();

    public LeaderboardService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public CompletableFuture<LookupResponse> buildLeaderboard(String alias) {
        return buildLeaderboard(plugin.getPluginConfig().normalizedGamemodeId(), alias);
    }

    public CompletableFuture<LookupResponse> buildLeaderboard(String gamemodeId, String alias) {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return BukkitFutures.supplySync(plugin, () -> resolveLocal(alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT)));
        }

        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String normalizedAlias = normalizeAlias(alias);
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                List<String> gamemodes = repository.listGamemodeIds();
                if (!gamemodes.contains(normalizedGamemode)) {
                    return new LookupResponse(LookupStatus.UNKNOWN_GAMEMODE, null);
                }

                List<String> knownBoards = repository.listBoardAliasesForGamemode(normalizedGamemode);
                if (!knownBoards.contains(normalizedAlias)) {
                    return new LookupResponse(LookupStatus.UNKNOWN_BOARD, null);
                }

                LeaderboardView snapshot = repository.findLeaderboardSnapshot(normalizedGamemode, normalizedAlias).orElse(null);
                if (snapshot == null) {
                    return new LookupResponse(LookupStatus.NO_DATA, null);
                }
                return new LookupResponse(LookupStatus.SUCCESS, snapshot);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletableFuture<List<String>> listAvailableGamemodes() {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return CompletableFuture.completedFuture(List.of(plugin.getPluginConfig().normalizedGamemodeId()));
        }

        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                List<String> gamemodes = new ArrayList<>(repository.listGamemodeIds());
                String local = plugin.getPluginConfig().normalizedGamemodeId();
                if (!gamemodes.contains(local)) {
                    gamemodes.add(local);
                }
                gamemodes.sort(String::compareTo);
                return List.copyOf(gamemodes);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletableFuture<List<String>> listBoardsForGamemode(String gamemodeId) {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return CompletableFuture.completedFuture(configuredBoardAliases());
        }

        String normalizedGamemode = normalizeGamemode(gamemodeId);
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.listBoardAliasesForGamemode(normalizedGamemode);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletableFuture<Void> syncLocalLeaderboardsToNetwork() {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return BukkitFutures.supplySync(plugin, this::buildAllLocalBoards)
                .thenCompose(views -> BukkitFutures.supplyAsync(plugin, () -> {
                    try {
                        String gamemode = plugin.getPluginConfig().normalizedGamemodeId();
                        List<String> configuredAliases = configuredBoardAliases();
                        repository.replaceBoardCatalog(gamemode, configuredAliases, Instant.now());
                        for (Map.Entry<String, LeaderboardView> entry : views.entrySet()) {
                            repository.saveLeaderboardSnapshot(gamemode, entry.getKey(), entry.getValue(), Instant.now());
                        }
                        return null;
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }));
    }

    public List<String> configuredBoardAliases() {
        return plugin.getPluginConfig().leaderboards().keySet().stream().sorted().toList();
    }

    private Map<String, LeaderboardView> buildAllLocalBoards() {
        Map<String, LeaderboardView> output = new LinkedHashMap<>();
        for (String alias : configuredBoardAliases()) {
            LookupResponse response = resolveLocal(alias);
            if (response.status() == LookupStatus.SUCCESS && response.view() != null) {
                output.put(alias, response.view());
                syncWarnings.remove(alias);
                continue;
            }

            warnSyncIssue(alias, response.status());
        }
        return output;
    }

    private LookupResponse resolveLocal(String alias) {
        if (alias == null || alias.isBlank()) {
            return new LookupResponse(LookupStatus.UNKNOWN_BOARD, null);
        }

        LeaderboardDefinition definition = plugin.getPluginConfig().leaderboards().get(alias);
        if (definition == null) {
            return new LookupResponse(LookupStatus.UNKNOWN_BOARD, null);
        }

        OfflinePlayer context = chooseContextPlayer();
        List<String> lines = new ArrayList<>();
        for (String configuredLine : definition.lines()) {
            String resolvedLine = resolvePlaceholder(context, configuredLine);
            if (isMissing(resolvedLine)) {
                continue;
            }
            lines.add(resolvedLine);
        }

        if (lines.isEmpty()) {
            lines.add(definition.emptyText());
        }

        return new LookupResponse(LookupStatus.SUCCESS, new LeaderboardView(
                definition.title(),
                plugin.getPluginConfig().leaderboardColor(),
                List.copyOf(lines),
                definition.normalizedFooter()
        ));
    }

    private OfflinePlayer chooseContextPlayer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();
        return offlinePlayers.length > 0 ? offlinePlayers[0] : null;
    }

    private String resolvePlaceholder(OfflinePlayer context, String placeholder) {
        if (!plugin.hasPlaceholderApi()) {
            return placeholder;
        }
        try {
            return PlaceholderAPI.setPlaceholders(context, placeholder);
        } catch (Throwable throwable) {
            plugin.logWarning("Failed to resolve leaderboard placeholder " + placeholder + ": " + throwable.getMessage());
            return placeholder;
        }
    }

    private boolean isMissing(String value) {
        return value == null || value.isBlank() || UNRESOLVED_PLACEHOLDER_PATTERN.matcher(value).find();
    }

    private String normalizeGamemode(String value) {
        if (value == null || value.isBlank()) {
            return plugin.getPluginConfig().normalizedGamemodeId();
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeAlias(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void warnSyncIssue(String alias, LookupStatus status) {
        String key = alias + ":" + status.name();
        if (!syncWarnings.add(key)) {
            return;
        }

        String gamemode = plugin.getPluginConfig().normalizedGamemodeId();
        switch (status) {
            case UNAVAILABLE -> plugin.logWarning(
                    "Could not publish leaderboard '" + alias + "' for gamemode '" + gamemode + "': placeholder resolution is unavailable."
            );
            case UNKNOWN_BOARD -> plugin.logWarning(
                    "Could not publish leaderboard '" + alias + "' for gamemode '" + gamemode + "': board alias is missing from config."
            );
            default -> plugin.logWarning(
                    "Could not publish leaderboard '" + alias + "' for gamemode '" + gamemode + "': status=" + status.name()
            );
        }
    }

    public enum LookupStatus {
        SUCCESS,
        UNAVAILABLE,
        UNKNOWN_GAMEMODE,
        NO_DATA,
        UNKNOWN_BOARD
    }

    public record LookupResponse(LookupStatus status, LeaderboardView view) {
    }
}
