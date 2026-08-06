package me.foesio.foDiscordBot.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clip.placeholderapi.PlaceholderAPI;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.LinkedAccount;
import me.foesio.foDiscordBot.model.ProfileCard;
import me.foesio.foDiscordBot.model.ProfileField;
import me.foesio.foDiscordBot.util.BukkitFutures;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class ProfileService {

    private static final Pattern DISCORD_MENTION_PATTERN = Pattern.compile("^<@!?(\\d+)>$");
    private static final Pattern DISCORD_ID_PATTERN = Pattern.compile("^\\d{15,22}$");
    private static final Pattern PLACEHOLDER_API_PATTERN = Pattern.compile("%[^%]+%");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private final FoDiscordBot plugin;
    private final LinkRepository repository;
    private final ConcurrentMap<UUID, CachedProfileCard> profileSyncCache = new ConcurrentHashMap<>();

    public ProfileService(FoDiscordBot plugin, LinkRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public CompletableFuture<LookupResponse> buildProfile(String query) {
        return buildProfile(query, plugin.getPluginConfig().normalizedGamemodeId());
    }

    public CompletableFuture<LookupResponse> buildProfile(String query, String gamemodeId) {
        if (plugin.getPluginConfig().networkEnabled()) {
            return buildNetworkProfile(query, gamemodeId);
        }
        return buildLocalProfile(query);
    }

    public CompletableFuture<Void> syncPlayerProfileSnapshot(Player player) {
        if (!plugin.getPluginConfig().networkEnabled() || player == null) {
            return CompletableFuture.completedFuture(null);
        }

        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findByPlayerUuid(playerUuid).orElse(null);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(account -> BukkitFutures.supplySync(plugin,
                () -> {
                    Player onlinePlayer = Bukkit.getPlayer(playerUuid);
                    OfflinePlayer target = onlinePlayer != null ? onlinePlayer : Bukkit.getOfflinePlayer(playerUuid);
                    return buildNetworkProfileCard(target, account, playerName);
                }))
                .thenCompose(card -> BukkitFutures.supplyAsync(plugin, () -> {
                    try {
                        repository.saveProfileSnapshot(plugin.getPluginConfig().normalizedGamemodeId(), card, Instant.now());
                        return null;
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }));
    }

    public CompletableFuture<Void> syncOnlineProfileSnapshots() {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return BukkitFutures.supplySync(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .map(player -> new ProfileSyncTarget(player.getUniqueId(), player.getName()))
                .toList()).thenCompose(targets -> {
            if (targets.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            return BukkitFutures.supplyAsync(plugin, () -> {
                try {
                    List<UUID> playerUuids = targets.stream()
                            .map(ProfileSyncTarget::playerUuid)
                            .toList();
                    return repository.findByPlayerUuids(playerUuids);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).thenCompose(accounts -> BukkitFutures.supplySync(plugin, () -> {
                List<ProfileCard> cards = new ArrayList<>();
                for (ProfileSyncTarget target : targets) {
                    Player onlinePlayer = Bukkit.getPlayer(target.playerUuid());
                    OfflinePlayer player = onlinePlayer != null
                            ? onlinePlayer
                            : Bukkit.getOfflinePlayer(target.playerUuid());
                    cards.add(buildNetworkProfileCard(player, accounts.get(target.playerUuid()), target.playerName()));
                }
                return List.copyOf(cards);
            })).thenCompose(cards -> BukkitFutures.supplyAsync(plugin, () -> {
                try {
                    repository.saveProfileSnapshots(plugin.getPluginConfig().normalizedGamemodeId(), cards, Instant.now());
                    return null;
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }));
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

    private CompletableFuture<LookupResponse> buildNetworkProfile(String query, String gamemodeId) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return CompletableFuture.completedFuture(new LookupResponse(LookupStatus.NOT_FOUND, null));
        }

        String normalizedGamemode = normalizeGamemode(gamemodeId);
        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                LinkedAccount account = resolveLinkedAccount(trimmed);
                if (account == null) {
                    return new LookupResponse(LookupStatus.NOT_FOUND, null);
                }

                ProfileCard snapshot = repository.findProfileSnapshot(account.playerUuid(), normalizedGamemode).orElse(null);
                if (snapshot == null) {
                    return new LookupResponse(LookupStatus.NOT_FOUND, null);
                }

                return new LookupResponse(LookupStatus.SUCCESS, snapshot);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private CompletableFuture<LookupResponse> buildLocalProfile(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return CompletableFuture.completedFuture(new LookupResponse(LookupStatus.NOT_FOUND, null));
        }

        String discordId = parseDiscordId(trimmed);
        if (discordId != null) {
            return BukkitFutures.supplyAsync(plugin, () -> {
                try {
                    return repository.findLinkedByDiscordId(discordId).orElse(null);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).thenCompose(account -> {
                if (account == null) {
                    return CompletableFuture.completedFuture(new LookupResponse(LookupStatus.NOT_FOUND, null));
                }
                return BukkitFutures.supplySync(plugin, () -> new LookupResponse(
                        LookupStatus.SUCCESS,
                        buildProfileCard(Bukkit.getOfflinePlayer(account.playerUuid()), account, account.playerName())
                ));
            });
        }

        return BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return repository.findByPlayerName(trimmed).orElse(null);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(account -> {
            if (account != null) {
                return BukkitFutures.supplySync(plugin, () -> {
                    Player onlinePlayer = Bukkit.getPlayer(account.playerUuid());
                    OfflinePlayer offlinePlayer = onlinePlayer != null ? onlinePlayer : Bukkit.getOfflinePlayer(account.playerUuid());
                    return new LookupResponse(
                            LookupStatus.SUCCESS,
                            buildProfileCard(offlinePlayer, account, trimmed)
                    );
                });
            }

            return BukkitFutures.supplySync(plugin, () -> findOnlinePlayerByName(trimmed))
                    .thenApply(onlinePlayer -> {
                        if (onlinePlayer == null) {
                            return new LookupResponse(LookupStatus.NOT_FOUND, null);
                        }
                        plugin.getLinkService().recordPlayerSnapshot(onlinePlayer);
                        return new LookupResponse(
                                LookupStatus.SUCCESS,
                                buildProfileCard(onlinePlayer, null, trimmed)
                        );
                    });
        });
    }

    private LinkedAccount resolveLinkedAccount(String query) throws Exception {
        String discordId = parseDiscordId(query);
        if (discordId != null) {
            return repository.findLinkedByDiscordId(discordId).orElse(null);
        }

        LinkedAccount byName = repository.findByPlayerName(query).orElse(null);
        if (byName != null) {
            return byName;
        }

        Player online = findOnlinePlayerByName(query);
        if (online == null) {
            return null;
        }
        repository.updatePlayerSnapshot(online.getUniqueId(), online.getName(), Instant.now());
        return repository.findByPlayerUuid(online.getUniqueId()).orElse(null);
    }

    private ProfileCard buildProfileCard(OfflinePlayer offlinePlayer, LinkedAccount account, String fallbackName) {
        String playerName = firstNonBlank(
                offlinePlayer != null ? offlinePlayer.getName() : null,
                account != null ? account.playerName() : null,
                fallbackName
        );
        String playerUuid = offlinePlayer != null
                ? offlinePlayer.getUniqueId().toString()
                : account != null ? account.playerUuid().toString() : "Unknown";
        boolean online = offlinePlayer != null && offlinePlayer.isOnline();

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("player_name", playerName);
        tokens.put("player_uuid", playerUuid);
        tokens.put("linked_discord", account != null && account.isLinked()
                ? "@" + firstNonBlank(account.discordDisplayName(), account.discordUsername(), "Unknown")
                : "Not linked");
        tokens.put("link_status", account != null && account.isLinked() ? "Linked" : "Not linked");
        tokens.put("online_status", online ? "Online" : "Offline");
        tokens.put("discord_username", account != null ? safe(account.discordUsername(), "Unknown") : "Unknown");
        tokens.put("discord_display_name", account != null ? safe(account.discordDisplayName(), "Unknown") : "Unknown");
        tokens.put("linked_at", account != null && account.linkedAt() != null ? DATE_FORMATTER.format(account.linkedAt()) : "Never");
        tokens.put("updated_at", account != null && account.updatedAt() != null ? DATE_FORMATTER.format(account.updatedAt()) : "Never");
        tokens.put("rewards_claimed", account != null && account.rewardsClaimed() ? "Yes" : "No");
        tokens.put("rewards_claimed_at",
                account != null && account.rewardsClaimedAt() != null ? DATE_FORMATTER.format(account.rewardsClaimedAt()) : "Never");

        List<ProfileField> resolvedFields = new ArrayList<>();
        for (ProfileField field : plugin.getPluginConfig().profileFields()) {
            String resolved = plugin.messages().renderTemplate(field.value(), tokens);
            resolved = applyPlaceholders(offlinePlayer, resolved);
            if (resolved.isBlank()) {
                resolved = "N/A";
            }

            String formattedName = field.name();
            String formattedValue = "`" + resolved + "`";
            if (field.sameLine()) {
                formattedName = "**" + field.name() + ":**";
            }

            resolvedFields.add(new ProfileField(formattedName, formattedValue, field.inline(), field.sameLine()));
        }

        String resolvedFooter = plugin.messages().renderTemplate(plugin.getPluginConfig().profileFooter(), tokens);

        return new ProfileCard(
                playerName,
                offlinePlayer != null ? offlinePlayer.getUniqueId() : account != null ? account.playerUuid() : null,
                buildThumbnailUrl(offlinePlayer, account, playerName),
                plugin.getPluginConfig().profileColor(),
                resolvedFooter,
                List.copyOf(resolvedFields)
        );
    }

    private ProfileCard buildNetworkProfileCard(OfflinePlayer offlinePlayer, LinkedAccount account, String fallbackName) {
        UUID playerUuid = offlinePlayer != null
                ? offlinePlayer.getUniqueId()
                : account != null ? account.playerUuid() : null;
        if (playerUuid == null) {
            return buildProfileCard(offlinePlayer, account, fallbackName);
        }

        Instant now = Instant.now();
        String fingerprint = profileFingerprint(offlinePlayer, account, fallbackName);
        CachedProfileCard cached = profileSyncCache.get(playerUuid);
        if (cached != null && cached.matches(fingerprint, now, plugin.getPluginConfig().profileSnapshotCacheTtl())) {
            return cached.card();
        }

        ProfileCard card = buildProfileCard(offlinePlayer, account, fallbackName);
        profileSyncCache.put(playerUuid, new CachedProfileCard(card, fingerprint, now));
        return card;
    }

    private String profileFingerprint(OfflinePlayer offlinePlayer, LinkedAccount account, String fallbackName) {
        StringBuilder builder = new StringBuilder();
        appendFingerprint(builder, plugin.getPluginConfig().normalizedGamemodeId());
        appendFingerprint(builder, plugin.getPluginConfig().profileColor());
        appendFingerprint(builder, plugin.getPluginConfig().profileFooter());
        for (ProfileField field : plugin.getPluginConfig().profileFields()) {
            appendFingerprint(builder, field.name());
            appendFingerprint(builder, field.value());
            appendFingerprint(builder, field.inline());
            appendFingerprint(builder, field.sameLine());
        }

        appendFingerprint(builder, fallbackName);
        appendFingerprint(builder, offlinePlayer != null ? offlinePlayer.getUniqueId() : null);
        appendFingerprint(builder, offlinePlayer != null ? offlinePlayer.getName() : null);
        appendFingerprint(builder, offlinePlayer != null && offlinePlayer.isOnline());

        appendFingerprint(builder, account != null ? account.playerUuid() : null);
        appendFingerprint(builder, account != null ? account.playerName() : null);
        appendFingerprint(builder, account != null ? account.discordUserId() : null);
        appendFingerprint(builder, account != null ? account.discordUsername() : null);
        appendFingerprint(builder, account != null ? account.discordDisplayName() : null);
        appendFingerprint(builder, account != null ? account.linkedAt() : null);
        appendFingerprint(builder, account != null ? account.updatedAt() : null);
        appendFingerprint(builder, account != null && account.rewardsClaimed());
        appendFingerprint(builder, account != null ? account.rewardsClaimedAt() : null);
        return builder.toString();
    }

    private void appendFingerprint(StringBuilder builder, Object value) {
        builder.append(value == null ? "<null>" : value).append('\n');
    }

    private String applyPlaceholders(OfflinePlayer offlinePlayer, String input) {
        if (!plugin.hasPlaceholderApi() || input == null || !PLACEHOLDER_API_PATTERN.matcher(input).find()) {
            return input;
        }
        try {
            if (offlinePlayer != null) {
                return PlaceholderAPI.setPlaceholders(offlinePlayer, input);
            }
            return input;
        } catch (Throwable throwable) {
            plugin.logWarning("Failed to apply PlaceholderAPI profile fields: " + throwable.getMessage());
            return input;
        }
    }

    private Player findOnlinePlayerByName(String name) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private String parseDiscordId(String input) {
        Matcher matcher = DISCORD_MENTION_PATTERN.matcher(input);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        if (DISCORD_ID_PATTERN.matcher(input).matches()) {
            return input;
        }
        return null;
    }

    private String buildThumbnailUrl(OfflinePlayer offlinePlayer, LinkedAccount account, String playerName) {
        return plugin.getSkinAvatarService().avatarUrl(offlinePlayer, account, playerName);
    }

    private String normalizeGamemode(String value) {
        if (value == null || value.isBlank()) {
            return plugin.getPluginConfig().normalizedGamemodeId();
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "Unknown";
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public enum LookupStatus {
        SUCCESS,
        NOT_FOUND
    }

    public record LookupResponse(LookupStatus status, ProfileCard card) {
    }

    private record ProfileSyncTarget(UUID playerUuid, String playerName) {
    }

    private record CachedProfileCard(ProfileCard card, String fingerprint, Instant builtAt) {
        private boolean matches(String fingerprint, Instant now, Duration ttl) {
            return ttl != null
                    && !ttl.isZero()
                    && !ttl.isNegative()
                    && this.fingerprint.equals(fingerprint)
                    && builtAt.plus(ttl).isAfter(now);
        }
    }
}
