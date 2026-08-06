package me.foesio.foDiscordBot.service;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.LinkedAccount;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SkinAvatarService {

    private static final String DEFAULT_AVATAR_TEMPLATE = "https://visage.surgeplay.com/bust/160/{player_uuid}";
    private static final String OFFLINE_NAME_AVATAR_TEMPLATE = "https://visage.surgeplay.com/bust/160/{player_name}";
    private static final Pattern SKIN_TEXTURE_URL_PATTERN = Pattern.compile(
            "\"SKIN\"\\s*:\\s*\\{.*?\"url\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final FoDiscordBot plugin;
    private final Map<UUID, SkinTexture> uuidTextures = new ConcurrentHashMap<>();
    private final Map<String, SkinTexture> nameTextures = new ConcurrentHashMap<>();
    private volatile boolean warnedSkinsRestorerFailure;

    public SkinAvatarService(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    public void cachePlayer(Player player) {
        if (player == null) {
            return;
        }
        resolveTexture(player.getUniqueId(), player.getName(), player).ifPresent(texture ->
                cache(player.getUniqueId(), player.getName(), texture));
    }

    public String avatarUrl(UUID playerUuid, String playerName) {
        SkinTexture cached = cachedTexture(playerUuid, playerName).orElse(null);
        if (cached != null) {
            return formatAvatarUrl(playerUuid, playerName, cached);
        }

        if (Bukkit.isPrimaryThread()) {
            Player onlinePlayer = findOnlinePlayer(playerUuid, playerName);
            if (onlinePlayer != null) {
                return avatarUrl(onlinePlayer, null, playerName);
            }
        }

        return formatAvatarUrl(playerUuid, playerName, null);
    }

    public String avatarUrl(OfflinePlayer offlinePlayer, LinkedAccount account, String fallbackName) {
        UUID playerUuid = account != null
                ? account.playerUuid()
                : offlinePlayer != null ? offlinePlayer.getUniqueId() : null;
        String playerName = firstNonBlank(
                offlinePlayer != null ? offlinePlayer.getName() : null,
                account != null ? account.playerName() : null,
                fallbackName
        );

        if (Bukkit.isPrimaryThread()) {
            Player onlinePlayer = findOnlinePlayer(playerUuid, playerName);
            if (onlinePlayer != null) {
                return avatarUrl(onlinePlayer, account, playerName);
            }
        }

        SkinTexture cached = cachedTexture(playerUuid, playerName).orElse(null);
        if (cached != null) {
            return formatAvatarUrl(playerUuid, playerName, cached);
        }

        if (Bukkit.isPrimaryThread()) {
            SkinTexture skinsRestorerTexture = resolveSkinsRestorerTexture(playerUuid, playerName).orElse(null);
            if (skinsRestorerTexture != null) {
                cache(playerUuid, playerName, skinsRestorerTexture);
                return formatAvatarUrl(playerUuid, playerName, skinsRestorerTexture);
            }
        }

        return formatAvatarUrl(playerUuid, playerName, null);
    }

    private String avatarUrl(Player player, LinkedAccount account, String fallbackName) {
        UUID playerUuid = player.getUniqueId();
        String playerName = firstNonBlank(player.getName(), account != null ? account.playerName() : null, fallbackName);
        SkinTexture texture = resolveTexture(playerUuid, playerName, player).orElse(null);
        if (texture != null) {
            cache(playerUuid, playerName, texture);
        }
        return formatAvatarUrl(playerUuid, playerName, texture);
    }

    private Optional<SkinTexture> resolveTexture(UUID playerUuid, String playerName, Player onlinePlayer) {
        Optional<SkinTexture> profileTexture = resolvePaperProfileTexture(onlinePlayer);
        if (profileTexture.isPresent()) {
            return profileTexture;
        }
        return resolveSkinsRestorerTexture(playerUuid, playerName);
    }

    private Optional<SkinTexture> resolvePaperProfileTexture(Player player) {
        if (player == null) {
            return Optional.empty();
        }

        try {
            java.net.URL skinUrl = player.getPlayerProfile().getTextures().getSkin();
            Optional<SkinTexture> texture = skinTextureFromUrl(skinUrl != null ? skinUrl.toString() : "");
            if (texture.isPresent()) {
                return texture;
            }

            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if (!"textures".equalsIgnoreCase(property.getName())) {
                    continue;
                }

                Optional<SkinTexture> propertyTexture = skinTextureFromPropertyValue(property.getValue());
                if (propertyTexture.isPresent()) {
                    return propertyTexture;
                }
            }
        } catch (Throwable ignored) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private Optional<SkinTexture> resolveSkinsRestorerTexture(UUID playerUuid, String playerName) {
        if (playerUuid == null || playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("SkinsRestorer")) {
            return Optional.empty();
        }

        try {
            Plugin skinsRestorerPlugin = Bukkit.getPluginManager().getPlugin("SkinsRestorer");
            if (skinsRestorerPlugin == null) {
                return Optional.empty();
            }

            ClassLoader skinsRestorerClassLoader = skinsRestorerPlugin.getClass().getClassLoader();
            Class<?> providerClass = Class.forName(
                    "net.skinsrestorer.api.SkinsRestorerProvider",
                    true,
                    skinsRestorerClassLoader
            );
            Class<?> apiClass = Class.forName(
                    "net.skinsrestorer.api.SkinsRestorer",
                    true,
                    skinsRestorerClassLoader
            );
            Class<?> playerStorageClass = Class.forName(
                    "net.skinsrestorer.api.storage.PlayerStorage",
                    true,
                    skinsRestorerClassLoader
            );
            Object skinsRestorer = providerClass.getMethod("get").invoke(null);
            Object playerStorage = apiClass.getMethod("getPlayerStorage").invoke(skinsRestorer);

            Object skinProperty = invokeOptional(playerStorageClass, playerStorage, "getSkinOfPlayer", new Class<?>[]{UUID.class}, playerUuid)
                    .orElseGet(() -> invokeOptional(
                            playerStorageClass,
                            playerStorage,
                            "getSkinForPlayer",
                            new Class<?>[]{UUID.class, String.class, boolean.class},
                            playerUuid,
                            playerName,
                            Bukkit.getOnlineMode()
                    ).orElse(null));

            if (skinProperty == null) {
                return Optional.empty();
            }

            String propertyValue = invokeString(skinProperty, "getValue");
            return skinTextureFromPropertyValue(propertyValue);
        } catch (ClassNotFoundException ignored) {
            return Optional.empty();
        } catch (Throwable throwable) {
            warnSkinsRestorerFailure(rootMessage(throwable));
            return Optional.empty();
        }
    }

    private Optional<Object> invokeOptional(
            Class<?> methodOwner,
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args
    ) {
        try {
            Method method = methodOwner.getMethod(methodName, parameterTypes);
            Object value = method.invoke(target, args);
            if (value instanceof Optional<?> optional) {
                return optional.map(Object.class::cast);
            }
            return Optional.ofNullable(value);
        } catch (NoSuchMethodException exception) {
            return Optional.empty();
        } catch (IllegalAccessException exception) {
            return Optional.empty();
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(exception.getCause());
        }
    }

    private String invokeString(Object target, String methodName) throws Exception {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value instanceof String string ? string : "";
    }

    private Optional<SkinTexture> skinTextureFromPropertyValue(String propertyValue) {
        if (propertyValue == null || propertyValue.isBlank()) {
            return Optional.empty();
        }

        try {
            String json = new String(Base64.getDecoder().decode(propertyValue), StandardCharsets.UTF_8);
            Matcher matcher = SKIN_TEXTURE_URL_PATTERN.matcher(json);
            if (!matcher.find()) {
                return Optional.empty();
            }

            return skinTextureFromUrl(matcher.group(1).replace("\\/", "/"));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<SkinTexture> skinTextureFromUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Optional.empty();
        }

        String url = rawUrl.trim().replace("http://textures.minecraft.net/", "https://textures.minecraft.net/");
        int markerIndex = url.indexOf("/texture/");
        if (markerIndex < 0) {
            return Optional.empty();
        }

        String hash = url.substring(markerIndex + "/texture/".length()).trim();
        int queryIndex = hash.indexOf('?');
        if (queryIndex >= 0) {
            hash = hash.substring(0, queryIndex);
        }
        if (hash.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new SkinTexture(url, hash));
    }

    private Optional<SkinTexture> cachedTexture(UUID playerUuid, String playerName) {
        if (playerUuid != null) {
            SkinTexture texture = uuidTextures.get(playerUuid);
            if (texture != null) {
                return Optional.of(texture);
            }
        }

        String nameKey = normalizeName(playerName);
        if (!nameKey.isBlank()) {
            SkinTexture texture = nameTextures.get(nameKey);
            if (texture != null) {
                return Optional.of(texture);
            }
        }

        return Optional.empty();
    }

    private void cache(UUID playerUuid, String playerName, SkinTexture texture) {
        if (texture == null) {
            return;
        }
        if (playerUuid != null) {
            uuidTextures.put(playerUuid, texture);
        }
        String nameKey = normalizeName(playerName);
        if (!nameKey.isBlank()) {
            nameTextures.put(nameKey, texture);
        }
    }

    private String formatAvatarUrl(UUID playerUuid, String playerName, SkinTexture texture) {
        String template = plugin.getPluginConfig().chatBridgeAvatarUrlTemplate();
        if (template == null || template.isBlank()) {
            template = DEFAULT_AVATAR_TEMPLATE;
        }

        if (texture != null) {
            if (containsSkinPlaceholders(template)) {
                return fillTemplate(template, playerUuid, playerName, texture);
            }
            if (!Bukkit.getOnlineMode()) {
                return texture.url();
            }
        }

        if (!Bukkit.getOnlineMode() && DEFAULT_AVATAR_TEMPLATE.equals(template) && firstNonBlank(playerName).length() > 0) {
            template = OFFLINE_NAME_AVATAR_TEMPLATE;
        }

        return fillTemplate(template, playerUuid, playerName, texture);
    }

    private String fillTemplate(String template, UUID playerUuid, String playerName, SkinTexture texture) {
        String resolvedUuid = playerUuid != null ? playerUuid.toString() : "";
        String resolvedName = playerName == null ? "" : playerName;
        return template
                .replace("{player_uuid}", urlEncode(resolvedUuid))
                .replace("{player_name}", urlEncode(resolvedName))
                .replace("{skin_texture_url}", texture != null ? texture.url() : "")
                .replace("{skin_texture_hash}", urlEncode(texture != null ? texture.hash() : ""));
    }

    private boolean containsSkinPlaceholders(String template) {
        return template.contains("{skin_texture_url}") || template.contains("{skin_texture_hash}");
    }

    private Player findOnlinePlayer(UUID playerUuid, String playerName) {
        if (playerUuid != null) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                return player;
            }
        }

        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) {
                return player;
            }
        }
        return null;
    }

    private void warnSkinsRestorerFailure(String message) {
        if (warnedSkinsRestorerFailure) {
            return;
        }
        warnedSkinsRestorerFailure = true;
        plugin.logWarning("Could not read SkinsRestorer skin data: " + message);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String urlEncode(String input) {
        return URLEncoder.encode(input == null ? "" : input, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private record SkinTexture(String url, String hash) {
    }
}
