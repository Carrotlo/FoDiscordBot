package me.foesio.foDiscordBot.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.AdvancementProfileView;
import me.foesio.foDiscordBot.util.BukkitFutures;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class AdvancementService {

    private static final Duration LOCAL_PLAYER_NAME_CACHE_TTL = Duration.ofSeconds(30);

    private final FoDiscordBot plugin;
    private final LinkRepository repository;
    private final FoAdvancementsHook hook;
    private volatile LocalPlayerNameCache localPlayerNameCache = new LocalPlayerNameCache(Instant.EPOCH, List.of());

    public AdvancementService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.hook = new FoAdvancementsHook(plugin);
    }

    public boolean localAvailable() {
        return plugin.getPluginConfig().advancementEnabled() && hook.available();
    }

    public boolean discordAvailable() {
        return plugin.getPluginConfig().advancementEnabled() || plugin.getPluginConfig().networkEnabled();
    }

    public CompletableFuture<LookupResponse> buildAdvancements(String gamemodeId, String query) {
        if (!discordAvailable()) {
            return CompletableFuture.completedFuture(new LookupResponse(LookupStatus.UNAVAILABLE, null));
        }

        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String localGamemode = plugin.getPluginConfig().normalizedGamemodeId();

        if (!plugin.getPluginConfig().networkEnabled()) {
            if (!normalizedGamemode.equals(localGamemode)) {
                return CompletableFuture.completedFuture(new LookupResponse(LookupStatus.UNKNOWN_GAMEMODE, null));
            }
            return buildLocalAdvancements(query, localGamemode);
        }

        if (normalizedGamemode.equals(localGamemode) && localAvailable()) {
            return buildLocalAdvancements(query, localGamemode).thenCompose(response -> {
                if (response.status() == LookupStatus.SUCCESS) {
                    return CompletableFuture.completedFuture(response);
                }
                return buildNetworkAdvancements(normalizedGamemode, query);
            });
        }

        return buildNetworkAdvancements(normalizedGamemode, query);
    }

    public CompletableFuture<List<String>> listAdvancementGamemodes() {
        if (!discordAvailable()) {
            return CompletableFuture.completedFuture(List.of());
        }

        if (!plugin.getPluginConfig().networkEnabled()) {
            return CompletableFuture.completedFuture(localAvailable()
                    ? List.of(plugin.getPluginConfig().normalizedGamemodeId())
                    : List.of());
        }

        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                List<String> gamemodes = new ArrayList<>(repository.listAdvancementGamemodeIds());
                String local = plugin.getPluginConfig().normalizedGamemodeId();
                if (localAvailable() && !gamemodes.contains(local)) {
                    gamemodes.add(local);
                }
                gamemodes.sort(String::compareToIgnoreCase);
                return List.copyOf(gamemodes);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletableFuture<List<String>> listAdvancementPlayers(String gamemodeId, String focused) {
        if (!discordAvailable()) {
            return CompletableFuture.completedFuture(List.of());
        }

        String normalizedGamemode = normalizeGamemode(gamemodeId);
        String focusedLower = focused == null ? "" : focused.toLowerCase(Locale.ROOT);
        if (!plugin.getPluginConfig().networkEnabled()) {
            if (!localAvailable()) {
                return CompletableFuture.completedFuture(List.of());
            }
            return BukkitFutures.supplySync(plugin, () -> localPlayerNames(focusedLower));
        }

        String localGamemode = plugin.getPluginConfig().normalizedGamemodeId();
        if (normalizedGamemode.equals(localGamemode) && localAvailable()) {
            CompletableFuture<List<String>> storedPlayers = BukkitFutures.supplyAsync(plugin, () -> {
                try {
                    return repository.listAdvancementPlayerNames(normalizedGamemode, focusedLower, 25);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            CompletableFuture<List<String>> localPlayers = BukkitFutures.supplySync(plugin, () -> localPlayerNames(focusedLower));
            return storedPlayers.thenCombine(localPlayers, this::mergePlayerNames);
        }

        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.listAdvancementPlayerNames(normalizedGamemode, focusedLower, 25);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    public CompletableFuture<Void> syncPlayerNow(Player player) {
        if (player == null) {
            return CompletableFuture.completedFuture(null);
        }

        return syncPlayerNow(player.getUniqueId(), player.getName());
    }

    public CompletableFuture<Void> syncPlayerNow(UUID playerUuid, String playerName) {
        if (!plugin.getPluginConfig().advancementEnabled()
                || !plugin.getPluginConfig().networkEnabled()
                || playerUuid == null
                || !hook.available()) {
            return CompletableFuture.completedFuture(null);
        }

        return BukkitFutures.supplySync(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(playerUuid);
            if (onlinePlayer == null) {
                return null;
            }
            return hook.snapshot(
                    plugin.getPluginConfig().normalizedGamemodeId(),
                    onlinePlayer.getUniqueId(),
                    onlinePlayer.getName() == null ? playerName : onlinePlayer.getName()
            );
        }).thenCompose(snapshot -> snapshot == null
                ? CompletableFuture.completedFuture(null)
                : saveSnapshots(List.of(snapshot)));
    }

    public CompletableFuture<Void> syncOnlinePlayersToNetwork() {
        if (!plugin.getPluginConfig().advancementEnabled()
                || !plugin.getPluginConfig().networkEnabled()
                || !hook.available()) {
            return CompletableFuture.completedFuture(null);
        }

        return BukkitFutures.supplySync(plugin, () -> {
            List<AdvancementProfileView> snapshots = new ArrayList<>();
            String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
            for (Player player : Bukkit.getOnlinePlayers()) {
                AdvancementProfileView snapshot;
                try {
                    snapshot = hook.snapshot(gamemodeId, player.getUniqueId(), player.getName());
                } catch (RuntimeException exception) {
                    plugin.logWarning("Failed to read FoAdvancements snapshot for "
                            + player.getName() + ": " + exception.getMessage());
                    continue;
                }
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
            return List.copyOf(snapshots);
        }).thenCompose(this::saveSnapshots);
    }

    private CompletableFuture<Void> saveSnapshots(List<AdvancementProfileView> snapshots) {
        if (!plugin.getPluginConfig().advancementEnabled() || !plugin.getPluginConfig().networkEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                String gamemodeId = plugin.getPluginConfig().normalizedGamemodeId();
                Instant now = Instant.now();
                String pluginVersion = snapshots.isEmpty() ? "" : snapshots.getFirst().pluginVersion();
                if (snapshots.isEmpty()) {
                    repository.upsertAdvancementGamemode(gamemodeId, pluginVersion, now);
                } else {
                    repository.saveAdvancementSnapshots(snapshots, now);
                }
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private CompletableFuture<LookupResponse> buildLocalAdvancements(String query, String gamemodeId) {
        if (!hook.available()) {
            return CompletableFuture.completedFuture(new LookupResponse(LookupStatus.UNAVAILABLE, null));
        }

        return BukkitFutures.supplySync(plugin, () -> {
            ResolvedPlayer target = resolveLocalPlayer(query);
            if (target == null) {
                return new LookupResponse(LookupStatus.NOT_FOUND, null);
            }
            AdvancementProfileView snapshot = hook.snapshot(gamemodeId, target.uuid(), target.name());
            return snapshot == null
                    ? new LookupResponse(LookupStatus.UNAVAILABLE, null)
                    : new LookupResponse(LookupStatus.SUCCESS, snapshot);
        });
    }

    private CompletableFuture<LookupResponse> buildNetworkAdvancements(String gamemodeId, String query) {
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                List<String> advancementGamemodes = repository.listAdvancementGamemodeIds();
                if (!advancementGamemodes.contains(gamemodeId)) {
                    List<String> allGamemodes = repository.listGamemodeIds();
                    return new LookupResponse(
                            allGamemodes.contains(gamemodeId) ? LookupStatus.UNAVAILABLE : LookupStatus.UNKNOWN_GAMEMODE,
                            null
                    );
                }

                Optional<AdvancementProfileView> snapshot = repository.findAdvancementSnapshot(gamemodeId, query);
                return snapshot
                        .map(view -> new LookupResponse(LookupStatus.SUCCESS, view))
                        .orElseGet(() -> new LookupResponse(LookupStatus.NOT_FOUND, null));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private List<String> localPlayerNames(String focusedLower) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            addMatchingName(names, player.getName(), focusedLower);
        }
        for (String playerName : cachedOfflinePlayerNames()) {
            addMatchingName(names, playerName, focusedLower);
        }
        names.sort(String::compareToIgnoreCase);
        return names.stream().distinct().limit(25).toList();
    }

    private List<String> cachedOfflinePlayerNames() {
        Instant now = Instant.now();
        LocalPlayerNameCache cache = localPlayerNameCache;
        if (now.isBefore(cache.expiresAt())) {
            return cache.names();
        }

        synchronized (this) {
            cache = localPlayerNameCache;
            now = Instant.now();
            if (now.isBefore(cache.expiresAt())) {
                return cache.names();
            }

            List<String> names = new ArrayList<>();
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (player.getName() != null && !player.getName().isBlank()) {
                    names.add(player.getName());
                }
            }
            names.sort(String::compareToIgnoreCase);
            LocalPlayerNameCache refreshed = new LocalPlayerNameCache(
                    now.plus(LOCAL_PLAYER_NAME_CACHE_TTL),
                    List.copyOf(names)
            );
            localPlayerNameCache = refreshed;
            return refreshed.names();
        }
    }

    private List<String> mergePlayerNames(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        merged.addAll(first);
        merged.addAll(second);
        merged.sort(String::compareToIgnoreCase);
        return merged.stream()
                .distinct()
                .limit(25)
                .toList();
    }

    private void addMatchingName(List<String> names, String name, String focusedLower) {
        if (name == null || name.isBlank()) {
            return;
        }
        if (focusedLower.isBlank() || name.toLowerCase(Locale.ROOT).startsWith(focusedLower)) {
            names.add(name);
        }
    }

    private ResolvedPlayer resolveLocalPlayer(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return null;
        }

        Player online = Bukkit.getPlayerExact(trimmed);
        if (online != null) {
            return new ResolvedPlayer(online.getUniqueId(), online.getName());
        }

        UUID uuid = parseUuid(trimmed);
        if (uuid != null) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String name = offlinePlayer.getName() == null ? uuid.toString() : offlinePlayer.getName();
            return new ResolvedPlayer(uuid, name);
        }

        return List.of(Bukkit.getOfflinePlayers()).stream()
                .filter(player -> player.getName() != null && player.getName().equalsIgnoreCase(trimmed))
                .sorted(Comparator.comparing(player -> player.getName().toLowerCase(Locale.ROOT)))
                .findFirst()
                .map(player -> new ResolvedPlayer(player.getUniqueId(), player.getName()))
                .orElse(null);
    }

    private UUID parseUuid(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String normalizeGamemode(String value) {
        if (value == null || value.isBlank()) {
            return plugin.getPluginConfig().normalizedGamemodeId();
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public enum LookupStatus {
        SUCCESS,
        UNKNOWN_GAMEMODE,
        UNAVAILABLE,
        NOT_FOUND
    }

    public record LookupResponse(LookupStatus status, AdvancementProfileView profile) {
    }

    private record ResolvedPlayer(UUID uuid, String name) {
    }

    private record LocalPlayerNameCache(Instant expiresAt, List<String> names) {
    }
}
