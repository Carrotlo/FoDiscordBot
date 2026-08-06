package me.foesio.foDiscordBot.service;

import java.awt.Color;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.foesio.core.discord.DiscordWebhookMessage;
import me.foesio.core.discord.DiscordWebhookService;
import me.foesio.core.discord.DiscordWebhookSettings;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.number.LargeNumberParser;
import me.foesio.foDiscordBot.FoDiscordBot;
import me.foesio.foDiscordBot.model.AdvancementEntryView;
import me.foesio.foDiscordBot.model.AdvancementProfileView;
import me.foesio.foDiscordBot.model.AdvancementTabView;
import me.foesio.foDiscordBot.model.LeaderboardView;
import me.foesio.foDiscordBot.model.ProfileCard;
import me.foesio.foDiscordBot.model.ProfileField;
import me.foesio.foDiscordBot.util.BukkitFutures;
import me.foesio.foDiscordBot.util.CoreRepeatingTask;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.NotNull;

public final class DiscordBotManager extends ListenerAdapter {

    private static final String RAW_LINK_PREFIX = "/link ";
    private static final int DISCORD_MESSAGE_LIMIT = 2_000;
    private static final int MINECRAFT_MESSAGE_LIMIT = 256;
    private static final int ADVANCEMENT_COLOR = 0x03fc88;
    private static final int MAX_ADVANCEMENT_FIELDS = 12;
    private static final int MAX_ADVANCEMENT_TAB_BUTTONS = 20;
    private static final int DISCORD_BUTTONS_PER_ROW = 5;
    private static final int ADVANCEMENT_PROGRESS_BAR_SEGMENTS = 10;
    private static final Duration ADVANCEMENT_SESSION_TTL = Duration.ofMinutes(15);
    private static final String ADVANCEMENT_CUSTOM_ID_PREFIX = "adv:";

    private final FoDiscordBot plugin;
    private final LinkService linkService;
    private final ProfileService profileService;
    private final LeaderboardService leaderboardService;
    private final AdvancementService advancementService;
    private final Map<String, Instant> interactionCooldowns = new ConcurrentHashMap<>();
    private final Map<String, AdvancementSession> advancementSessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> relayWebhookUrls = new ConcurrentHashMap<>();
    private final Set<String> relayWarnings = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean relayQueueDrainRunning = new AtomicBoolean(false);

    private volatile JDA jda;
    private CoreRepeatingTask relayQueueTask;

    public DiscordBotManager(
            FoDiscordBot plugin,
            LinkService linkService,
            ProfileService profileService,
            LeaderboardService leaderboardService,
            AdvancementService advancementService
    ) {
        this.plugin = plugin;
        this.linkService = linkService;
        this.profileService = profileService;
        this.leaderboardService = leaderboardService;
        this.advancementService = advancementService;
    }

    public synchronized void startAsync() {
        if (!plugin.getPluginConfig().shouldRunDiscordNode()) {
            if (plugin.getPluginConfig().hasConfiguredBotToken() && plugin.getPluginConfig().networkEnabled()) {
                plugin.logInfo("Network mode is enabled and this node is not primary-discord-node. Skipping Discord gateway startup.");
            } else {
                plugin.logWarning("Discord bot token is missing. Discord features will be disabled until a token is configured. Set discord.token in config.yml and use /fodiscordbotadmin reload.");
            }
            return;
        }
        String token = plugin.getPluginConfig().botToken();

        BukkitFutures.supplyAsync(plugin, () -> {
            startBlocking(token);
            return null;
        }).exceptionally(throwable -> {
            plugin.logSevere("Failed to start Discord bot: " + throwable.getMessage());
            return null;
        });
    }

    public synchronized void reloadAsync() {
        shutdown();
        startAsync();
    }

    public boolean isDiscordAvailable() {
        return jda != null && plugin.getPluginConfig().shouldRunDiscordNode();
    }

    public synchronized void shutdown() {
        JDA active = jda;
        jda = null;
        advancementSessions.clear();
        relayWebhookUrls.clear();
        relayWarnings.clear();
        if (relayQueueTask != null) {
            relayQueueTask.cancel();
            relayQueueTask = null;
        }
        if (active == null) {
            return;
        }

        active.setAutoReconnect(false);
        active.shutdown();

        try {
            if (!active.awaitShutdown(10, TimeUnit.SECONDS)) {
                active.shutdownNow();
                active.awaitShutdown(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            active.shutdownNow();
        }
    }

    public void handleConfigReload() {
        relayWebhookUrls.clear();
        relayWarnings.clear();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "link" -> handleDiscordLink(event);
            case "unlink" -> handleDiscordUnlink(event);
            case "ip" -> handleIp(event);
            case "profile" -> handleProfile(event);
            case "leaderboard" -> handleLeaderboard(event);
            case "advancements" -> {
                if (advancementService.discordAvailable()) {
                    handleAdvancements(event);
                } else {
                    event.reply(plugin.messages().renderConfigured("discord.advancements.disabled"))
                            .setEphemeral(true)
                            .queue();
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        String commandName = event.getName();
        String focused = event.getFocusedOption().getName();
        if (!"profile".equals(commandName) && !"leaderboard".equals(commandName) && !"advancements".equals(commandName)) {
            return;
        }

        if ("advancements".equals(commandName)) {
            if (!advancementService.discordAvailable()) {
                event.replyChoices(List.of()).queue();
                return;
            }
            if ("gamemode".equals(focused)) {
                handleAdvancementGamemodeAutocomplete(event);
                return;
            }
            if ("player".equals(focused)) {
                handleAdvancementPlayerAutocomplete(event);
            }
            return;
        }

        if ("gamemode".equals(focused)) {
            handleGamemodeAutocomplete(event);
            return;
        }

        if ("leaderboard".equals(commandName) && "board".equals(focused)) {
            handleBoardAutocomplete(event);
        }
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String customId = event.getComponentId();
        if (customId == null || !customId.startsWith(ADVANCEMENT_CUSTOM_ID_PREFIX)) {
            return;
        }
        if (!advancementService.discordAvailable()) {
            event.reply(plugin.messages().renderConfigured("discord.advancements.disabled"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        handleAdvancementButton(event, customId);
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        String rawContent = event.getMessage().getContentRaw().trim();
        if (rawContent.length() > RAW_LINK_PREFIX.length()
                && rawContent.regionMatches(true, 0, RAW_LINK_PREFIX, 0, RAW_LINK_PREFIX.length())) {
            String code = rawContent.substring(RAW_LINK_PREFIX.length()).trim();
            if (code.isEmpty()) {
                return;
            }

            linkService.completeDiscordLink(code, event.getAuthor()).whenComplete((response, throwable) -> {
                if (throwable != null) {
                    replyToRawLinkMessage(event, plugin.messages().renderConfigured("discord.generic-error"));
                    return;
                }

                String message = switch (response.status()) {
                    case SUCCESS -> plugin.messages().render("discord.link.success",
                            FoMessageService.missingMessageFallback("discord.link.success"), Map.of(
                            "player_name", response.account().playerName(),
                            "discord_mention", event.getAuthor().getAsMention()
                    ));
                    case COOLDOWN -> plugin.messages().render("discord.cooldown",
                            FoMessageService.missingMessageFallback("discord.cooldown"), Map.of(
                            "seconds", String.valueOf(Math.max(1L, response.remaining().toSeconds()))
                    ));
                    case INVALID_CODE -> plugin.messages().renderConfigured("discord.link.invalid-code");
                    case EXPIRED_CODE -> plugin.messages().renderConfigured("discord.link.expired-code");
                    case DISCORD_ALREADY_LINKED -> plugin.messages().renderConfigured("discord.link.discord-already-linked");
                    case PLAYER_ALREADY_LINKED -> plugin.messages().renderConfigured("discord.link.player-already-linked");
                };
                replyToRawLinkMessage(event, message);
                if (response.status() == LinkService.DiscordLinkStatus.SUCCESS
                        && plugin.getPluginConfig().removeLinkMessageAfterSuccess()
                        && event.isFromGuild()) {
                    event.getMessage().delete().queue(null, ignored -> {
                    });
                }
            });
            return;
        }

        handleDiscordChatBridge(event);
    }

    private void handleDiscordLink(SlashCommandInteractionEvent event) {
        String code = event.getOption("code") != null ? event.getOption("code").getAsString() : "";
        event.deferReply(true).queue();

        linkService.completeDiscordLink(code, event.getUser()).whenComplete((response, throwable) -> {
            if (throwable != null) {
                event.getHook().editOriginal(plugin.messages().renderConfigured("discord.generic-error")).queue();
                return;
            }

            String message = switch (response.status()) {
                case SUCCESS -> plugin.messages().render("discord.link.success",
                        FoMessageService.missingMessageFallback("discord.link.success"), Map.of(
                        "player_name", response.account().playerName(),
                        "discord_mention", event.getUser().getAsMention()
                ));
                case COOLDOWN -> plugin.messages().render("discord.cooldown",
                        FoMessageService.missingMessageFallback("discord.cooldown"), Map.of(
                        "seconds", String.valueOf(Math.max(1L, response.remaining().toSeconds()))
                ));
                case INVALID_CODE -> plugin.messages().renderConfigured("discord.link.invalid-code");
                case EXPIRED_CODE -> plugin.messages().renderConfigured("discord.link.expired-code");
                case DISCORD_ALREADY_LINKED -> plugin.messages().renderConfigured("discord.link.discord-already-linked");
                case PLAYER_ALREADY_LINKED -> plugin.messages().renderConfigured("discord.link.player-already-linked");
            };
            event.getHook().editOriginal(message).queue();
        });
    }

    private void handleDiscordUnlink(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue();

        linkService.unlinkDiscordUser(event.getUser()).whenComplete((response, throwable) -> {
            if (throwable != null) {
                event.getHook().editOriginal(plugin.messages().renderConfigured("discord.generic-error")).queue();
                return;
            }

            String message = switch (response.status()) {
                case SUCCESS -> plugin.messages().render("discord.unlink.success",
                        FoMessageService.missingMessageFallback("discord.unlink.success"), Map.of(
                        "player_name", response.account().playerName(),
                        "discord_mention", event.getUser().getAsMention()
                ));
                case NOT_LINKED -> plugin.messages().renderConfigured("discord.unlink.not-linked");
            };
            event.getHook().editOriginal(message).queue();
        });
    }

    private void handleIp(SlashCommandInteractionEvent event) {
        if (!plugin.getPluginConfig().serverIpCommandEnabled()) {
            event.reply(plugin.messages().renderConfigured("discord.ip.disabled"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.reply(plugin.messages().render("discord.ip.text",
                FoMessageService.missingMessageFallback("discord.ip.text"), Map.of(
                "ip", plugin.getPluginConfig().serverIp()
        ))).queue();
    }

    private void handleProfile(SlashCommandInteractionEvent event) {
        Duration remaining = checkInteractionCooldown(event.getUser().getId());
        if (!remaining.isZero() && !remaining.isNegative()) {
            event.reply(plugin.messages().render("discord.cooldown",
                    FoMessageService.missingMessageFallback("discord.cooldown"), Map.of(
                    "seconds", String.valueOf(Math.max(1L, remaining.toSeconds()))
            ))).setEphemeral(true).queue();
            return;
        }

        String target = event.getOption("target") != null ? event.getOption("target").getAsString() : "";
        String gamemode = event.getOption("gamemode") != null ? event.getOption("gamemode").getAsString() : "";
        String normalizedGamemode = normalize(gamemode);
        event.deferReply(false).queue();
        profileService.listAvailableGamemodes().whenComplete((gamemodes, throwable) -> {
            if (throwable != null) {
                event.getHook().editOriginal(plugin.messages().renderConfigured("discord.generic-error")).queue();
                return;
            }

            if (normalizedGamemode.isBlank() || !gamemodes.contains(normalizedGamemode)) {
                event.getHook().editOriginal(plugin.messages().render("discord.profile.unknown-gamemode",
                        FoMessageService.missingMessageFallback("discord.profile.unknown-gamemode"), Map.of(
                        "gamemode", gamemode
                ))).queue();
                return;
            }

            profileService.buildProfile(target, normalizedGamemode).whenComplete((response, profileThrowable) -> {
                if (profileThrowable != null) {
                    event.getHook().editOriginal(plugin.messages().renderConfigured("discord.generic-error")).queue();
                    return;
                }

                if (response.status() != ProfileService.LookupStatus.SUCCESS || response.card() == null) {
                    event.getHook().editOriginal(plugin.messages().renderConfigured("discord.profile.not-found")).queue();
                    return;
                }

                event.getHook().editOriginalEmbeds(createProfileEmbed(response.card()))
                        .setAllowedMentions(Collections.emptyList())
                        .queue();
            });
        });
    }

    private void handleLeaderboard(SlashCommandInteractionEvent event) {
        Duration remaining = checkInteractionCooldown(event.getUser().getId());
        if (!remaining.isZero() && !remaining.isNegative()) {
            event.reply(plugin.messages().render("discord.cooldown",
                    FoMessageService.missingMessageFallback("discord.cooldown"), Map.of(
                    "seconds", String.valueOf(Math.max(1L, remaining.toSeconds()))
            ))).setEphemeral(true).queue();
            return;
        }

        String gamemode = event.getOption("gamemode") != null ? event.getOption("gamemode").getAsString() : "";
        String board = event.getOption("board") != null ? event.getOption("board").getAsString() : "";
        event.deferReply(false).queue();
        leaderboardService.buildLeaderboard(gamemode, board).whenComplete((response, throwable) -> {
            if (throwable != null) {
                event.getHook().editOriginal(plugin.messages().renderConfigured("discord.generic-error")).queue();
                return;
            }

            switch (response.status()) {
                case SUCCESS -> event.getHook().editOriginalEmbeds(createLeaderboardEmbed(response.view()))
                        .setAllowedMentions(Collections.emptyList())
                        .queue();
                case UNAVAILABLE -> event.getHook().editOriginal(plugin.messages().renderConfigured("discord.leaderboard.unavailable")).queue();
                case UNKNOWN_GAMEMODE -> event.getHook().editOriginal(plugin.messages().render("discord.leaderboard.unknown-gamemode",
                        FoMessageService.missingMessageFallback("discord.leaderboard.unknown-gamemode"), Map.of(
                        "gamemode", gamemode
                ))).queue();
                case NO_DATA -> event.getHook().editOriginal(plugin.messages().renderConfigured("discord.leaderboard.no-data")).queue();
                case UNKNOWN_BOARD -> event.getHook().editOriginal(plugin.messages().render("discord.leaderboard.unknown-board",
                        FoMessageService.missingMessageFallback("discord.leaderboard.unknown-board"), Map.of(
                        "board", board
                ))).queue();
            }
        });
    }

    private void handleAdvancements(SlashCommandInteractionEvent event) {
        Duration remaining = checkInteractionCooldown(event.getUser().getId());
        if (!remaining.isZero() && !remaining.isNegative()) {
            event.reply(plugin.messages().render("discord.cooldown",
                    FoMessageService.missingMessageFallback("discord.cooldown"), Map.of(
                    "seconds", String.valueOf(Math.max(1L, remaining.toSeconds()))
            ))).setEphemeral(true).queue();
            return;
        }

        String gamemode = event.getOption("gamemode") != null ? event.getOption("gamemode").getAsString() : "";
        String player = event.getOption("player") != null ? event.getOption("player").getAsString() : "";
        event.deferReply(false).queue();
        advancementService.buildAdvancements(gamemode, player).whenComplete((response, throwable) -> {
            if (throwable != null) {
                event.getHook().editOriginal(plugin.messages().renderConfigured("discord.generic-error")).queue();
                return;
            }

            switch (response.status()) {
                case SUCCESS -> {
                    String token = createAdvancementSession(response.profile());
                    event.getHook().editOriginalEmbeds(createAdvancementEmbed(response.profile(), 0))
                            .setContent("")
                            .setComponents(createAdvancementComponents(token, response.profile(), 0))
                            .setAllowedMentions(Collections.emptyList())
                            .queue();
                }
                case UNKNOWN_GAMEMODE -> event.getHook().editOriginal(plugin.messages().render("discord.advancements.unknown-gamemode",
                        FoMessageService.missingMessageFallback("discord.advancements.unknown-gamemode"), Map.of(
                        "gamemode", gamemode
                ))).queue();
                case UNAVAILABLE -> event.getHook().editOriginal(plugin.messages().renderConfigured("discord.advancements.unavailable")).queue();
                case NOT_FOUND -> event.getHook().editOriginal(plugin.messages().renderConfigured("discord.advancements.not-found")).queue();
            }
        });
    }

    private void handleGamemodeAutocomplete(CommandAutoCompleteInteractionEvent event) {
        String focused = normalize(event.getFocusedOption().getValue());
        leaderboardService.listAvailableGamemodes().whenComplete((gamemodes, throwable) -> {
            if (throwable != null) {
                event.replyChoices(List.of()).queue();
                return;
            }
            event.replyChoices(toChoices(gamemodes, focused)).queue();
        });
    }

    private void handleBoardAutocomplete(CommandAutoCompleteInteractionEvent event) {
        String gamemode = event.getOption("gamemode") != null ? event.getOption("gamemode").getAsString() : "";
        String focused = normalize(event.getFocusedOption().getValue());
        leaderboardService.listBoardsForGamemode(gamemode).whenComplete((boards, throwable) -> {
            if (throwable != null) {
                event.replyChoices(List.of()).queue();
                return;
            }
            event.replyChoices(toChoices(boards, focused)).queue();
        });
    }

    private void handleAdvancementGamemodeAutocomplete(CommandAutoCompleteInteractionEvent event) {
        String focused = normalize(event.getFocusedOption().getValue());
        advancementService.listAdvancementGamemodes().whenComplete((gamemodes, throwable) -> {
            if (throwable != null) {
                event.replyChoices(List.of()).queue();
                return;
            }
            event.replyChoices(toChoices(gamemodes, focused)).queue();
        });
    }

    private void handleAdvancementPlayerAutocomplete(CommandAutoCompleteInteractionEvent event) {
        String gamemode = event.getOption("gamemode") != null ? event.getOption("gamemode").getAsString() : "";
        String focused = event.getFocusedOption().getValue();
        advancementService.listAdvancementPlayers(gamemode, focused).whenComplete((players, throwable) -> {
            if (throwable != null) {
                event.replyChoices(List.of()).queue();
                return;
            }
            event.replyChoices(toChoices(players, normalize(focused))).queue();
        });
    }

    private void handleAdvancementButton(ButtonInteractionEvent event, String customId) {
        cleanupExpiredAdvancementSessions();

        String[] parts = customId.split(":");
        if (parts.length < 3) {
            event.reply(plugin.messages().renderConfigured("discord.advancements.expired"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String token = parts[1];
        AdvancementSession session = advancementSessions.get(token);
        if (session == null || session.expired()) {
            advancementSessions.remove(token);
            event.reply(plugin.messages().renderConfigured("discord.advancements.expired"))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        int page = parsePage(parts[parts.length - 1]);
        AdvancementProfileView profile = session.profile();
        page = clampPage(profile, page);
        advancementSessions.put(token, new AdvancementSession(profile, Instant.now().plus(ADVANCEMENT_SESSION_TTL)));
        event.editMessageEmbeds(createAdvancementEmbed(profile, page))
                .setComponents(createAdvancementComponents(token, profile, page))
                .setAllowedMentions(Collections.emptyList())
                .queue();
    }

    private List<Command.Choice> toChoices(List<String> values, String focusedLower) {
        return values.stream()
                .filter(value -> !value.isBlank())
                .filter(value -> focusedLower.isBlank() || value.toLowerCase(Locale.ROOT).startsWith(focusedLower))
                .limit(25)
                .map(value -> new Command.Choice(value, value))
                .toList();
    }

    private void startBlocking(String token) {
        shutdown();

        JDA built = JDABuilder.createLight(token, Collections.emptyList())
                .enableIntents(EnumSet.of(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.DIRECT_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT
                ))
                .addEventListeners(this)
                .setAutoReconnect(true)
                .build();

        try {
            built.awaitReady();
            syncCommands(built);
            jda = built;
            startRelayQueueConsumer();
            plugin.logInfo("Discord bot connected successfully.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting Discord bot", exception);
        } catch (Exception exception) {
            built.shutdown();
            try {
                if (!built.awaitShutdown(5, TimeUnit.SECONDS)) {
                    built.shutdownNow();
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                built.shutdownNow();
            }
            throw new RuntimeException(exception);
        }
    }

    private void syncCommands(JDA readyJda) {
        List<CommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("link", "Link your Discord account with a Minecraft account")
                .addOption(OptionType.STRING, "code", "Your in-game link code", true));
        commands.add(Commands.slash("unlink", "Unlink your Discord account from its Minecraft account"));
        if (plugin.getPluginConfig().serverIpCommandEnabled()) {
            commands.add(Commands.slash("ip", "Show the Minecraft server IP"));
        }
        commands.add(Commands.slash("profile", "Show a Minecraft profile in Discord")
                .addOption(OptionType.STRING, "target", "Discord mention/ID or Minecraft IGN", true)
                .addOption(OptionType.STRING, "gamemode", "Gamemode id", true, true));
        commands.add(Commands.slash("leaderboard", "Show a configured leaderboard board for a gamemode")
                .addOption(OptionType.STRING, "gamemode", "Gamemode id", true, true)
                .addOption(OptionType.STRING, "board", "The configured board alias", true, true));
        if (advancementService.discordAvailable()) {
            commands.add(Commands.slash("advancements", "Show a player's FoAdvancements progress")
                    .addOption(OptionType.STRING, "gamemode", "Gamemode id", true, true)
                    .addOption(OptionType.STRING, "player", "Minecraft player name or UUID", true, true));
        }

        String guildId = plugin.getPluginConfig().normalizedGuildId();
        if (guildId != null) {
            Guild guild = readyJda.getGuildById(guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue();
                return;
            }
            plugin.logWarning("Configured Discord guild " + guildId + " was not found. Falling back to global slash commands.");
        }

        readyJda.updateCommands().addCommands(commands).queue();
    }

    private Duration checkInteractionCooldown(String userId) {
        Instant now = Instant.now();
        Instant previousUse = interactionCooldowns.get(userId);
        if (previousUse != null) {
            Duration elapsed = Duration.between(previousUse, now);
            Duration cooldown = plugin.getPluginConfig().discordCommandCooldown();
            if (elapsed.compareTo(cooldown) < 0) {
                return cooldown.minus(elapsed);
            }
        }

        interactionCooldowns.put(userId, now);
        return Duration.ZERO;
    }

    private void replyToRawLinkMessage(MessageReceivedEvent event, String message) {
        event.getMessage()
                .reply(message)
                .setAllowedMentions(Collections.emptyList())
                .mentionRepliedUser(false)
                .queue();
    }

    public void relayMinecraftChat(UUID playerUuid, String playerName, String rawMessage) {
        String channelId = plugin.getPluginConfig().normalizedChatBridgeChannelId();
        if (channelId == null) {
            return;
        }

        JDA active = jda;
        if (active == null) {
            enqueueNetworkChatRelay(playerUuid, playerName, rawMessage);
            return;
        }

        String sanitizedMessage = sanitizeMinecraftToDiscord(rawMessage);
        if (sanitizedMessage.isBlank()) {
            return;
        }

        TextChannel channel = active.getTextChannelById(channelId);
        if (channel == null) {
            warnRelayOnce("missing-channel:" + channelId, "Configured chat bridge channel " + channelId + " was not found.");
            return;
        }

        String username = sanitizeWebhookUsername(formatRelayUsername(playerName, plugin.getPluginConfig().normalizedGamemodeId()));
        String avatarUrl = buildMinecraftAvatarUrl(playerUuid, playerName);
        sendRelayMessage(channel, username, avatarUrl, sanitizedMessage).exceptionally(throwable -> {
            plugin.logWarning("Failed to relay Minecraft chat to Discord: " + throwable.getMessage());
            return null;
        });
    }

    private void enqueueNetworkChatRelay(UUID playerUuid, String playerName, String rawMessage) {
        if (!plugin.getPluginConfig().networkEnabled()) {
            return;
        }

        String sanitizedMessage = sanitizeMinecraftToDiscord(rawMessage);
        if (sanitizedMessage.isBlank()) {
            return;
        }

        BukkitFutures.supplyAsync(plugin, () -> {
            try {
                linkService.repository().enqueueChatRelayMessage(
                        playerUuid,
                        playerName,
                        buildMinecraftAvatarUrl(playerUuid, playerName),
                        plugin.getPluginConfig().normalizedGamemodeId(),
                        sanitizedMessage,
                        Instant.now()
                );
                return null;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).exceptionally(throwable -> {
            plugin.logWarning("Failed to queue Minecraft chat relay for network delivery: " + throwable.getMessage());
            return null;
        });
    }

    private void handleDiscordChatBridge(MessageReceivedEvent event) {
        String configuredChannelId = plugin.getPluginConfig().normalizedChatBridgeChannelId();
        if (configuredChannelId == null || !event.isFromGuild() || !configuredChannelId.equals(event.getChannel().getId())) {
            return;
        }

        String relayedMessage = buildDiscordToMinecraftMessage(event.getMessage());
        if (relayedMessage.isBlank()) {
            return;
        }

        String authorName = sanitizeForMinecraft(firstNonBlank(
                event.getMember() != null ? event.getMember().getEffectiveName() : null,
                event.getAuthor().getGlobalName(),
                event.getAuthor().getName()
        ));
        String formatted = formatIngameRelayMessage(authorName, relayedMessage);
        BukkitFutures.runSync(plugin, () -> plugin.getServer().broadcastMessage(formatted));
    }

    private CompletableFuture<Void> sendRelayMessage(TextChannel channel, String username, String avatarUrl, String content) {
        return resolveRelayWebhookUrl(channel).thenCompose(webhookUrl -> {
            if (webhookUrl == null || webhookUrl.isBlank()) {
                return channel.sendMessage("**" + username + "** >> " + content)
                        .setAllowedMentions(Collections.emptyList())
                        .submit()
                        .thenApply(ignored -> null);
            }
            return postWebhookMessage(channel.getId(), webhookUrl, username, avatarUrl, content, true);
        });
    }

    private void startRelayQueueConsumer() {
        if (!plugin.getPluginConfig().networkEnabled() || !plugin.getPluginConfig().shouldRunDiscordNode()) {
            return;
        }
        if (relayQueueTask != null) {
            relayQueueTask.cancel();
        }
        relayQueueTask = new CoreRepeatingTask(
                plugin.getCore().scheduler(),
                this::drainRelayQueueOnce,
                40L,
                40L,
                true
        );
        relayQueueTask.start();
    }

    private void drainRelayQueueOnce() {
        if (!plugin.getPluginConfig().networkEnabled() || !plugin.getPluginConfig().shouldRunDiscordNode()) {
            return;
        }
        if (!relayQueueDrainRunning.compareAndSet(false, true)) {
            return;
        }

        BukkitFutures.supplyAsync(plugin, () -> {
            try {
                return linkService.repository().findPendingChatRelayMessages(25);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).thenCompose(messages -> {
            if (messages.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (LinkRepository.QueuedChatRelayMessage message : messages) {
                chain = chain.thenCompose(ignored -> relayQueuedChatMessage(message));
            }
            return chain;
        }).whenComplete((ignored, throwable) -> {
            relayQueueDrainRunning.set(false);
            if (throwable != null) {
                plugin.logWarning("Failed to drain network chat relay queue: " + throwable.getMessage());
            }
        });
    }

    private CompletableFuture<Void> relayQueuedChatMessage(LinkRepository.QueuedChatRelayMessage queuedMessage) {
        String channelId = plugin.getPluginConfig().normalizedChatBridgeChannelId();
        JDA active = jda;
        if (active == null || channelId == null) {
            return CompletableFuture.completedFuture(null);
        }

        TextChannel channel = active.getTextChannelById(channelId);
        if (channel == null) {
            warnRelayOnce("missing-channel:" + channelId, "Configured chat bridge channel " + channelId + " was not found.");
            return CompletableFuture.completedFuture(null);
        }

        String username = sanitizeWebhookUsername(formatRelayUsername(queuedMessage.playerName(), queuedMessage.gamemodeId()));
        String avatarUrl = firstNonBlank(
                queuedMessage.avatarUrl(),
                buildMinecraftAvatarUrl(queuedMessage.playerUuid(), queuedMessage.playerName())
        );
        return sendRelayMessage(channel, username, avatarUrl, queuedMessage.message())
                .thenCompose(ignored -> BukkitFutures.supplyAsync(plugin, () -> {
                    try {
                        linkService.repository().deleteQueuedChatRelayMessage(queuedMessage.id());
                        return null;
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                }));
    }

    private CompletableFuture<Void> postWebhookMessage(
            String channelId,
            String webhookUrl,
            String username,
            String avatarUrl,
            String content,
            boolean retryIfStale
    ) {
        DiscordWebhookSettings settings = new DiscordWebhookSettings(
                true,
                webhookUrl,
                username,
                avatarUrl,
                false,
                Duration.ofSeconds(10),
                Map.of()
        );
        DiscordWebhookMessage message = DiscordWebhookMessage.builder()
                .content(content)
                .username(username)
                .avatarUrl(avatarUrl)
                .suppressMentions(true)
                .build();

        return DiscordWebhookService.create(plugin, settings).send(message).thenCompose(result -> {
            if (result.sent()) {
                return CompletableFuture.completedFuture(null);
            }

            if (retryIfStale && (result.httpStatus() == 401 || result.httpStatus() == 403 || result.httpStatus() == 404)) {
                relayWebhookUrls.remove(channelId);
                JDA active = jda;
                if (active == null) {
                    return CompletableFuture.completedFuture(null);
                }

                TextChannel refreshedChannel = active.getTextChannelById(channelId);
                if (refreshedChannel == null) {
                    return CompletableFuture.failedFuture(new IOException("Configured chat bridge channel " + channelId + " was not found."));
                }

                return resolveRelayWebhookUrl(refreshedChannel).thenCompose(refreshedWebhookUrl -> {
                    if (refreshedWebhookUrl == null || refreshedWebhookUrl.isBlank()) {
                        return refreshedChannel.sendMessage("**" + username + "** >> " + content)
                                .setAllowedMentions(Collections.emptyList())
                                .submit()
                                .thenApply(ignored -> null);
                    }
                    return postWebhookMessage(channelId, refreshedWebhookUrl, username, avatarUrl, content, false);
                });
            }

            String error = result.error().isBlank()
                    ? "Discord webhook send failed with HTTP " + result.httpStatus() + "."
                    : result.error();
            return CompletableFuture.failedFuture(new IOException(error));
        });
    }

    private CompletableFuture<String> resolveRelayWebhookUrl(TextChannel channel) {
        return relayWebhookUrls.computeIfAbsent(channel.getId(), ignored -> createOrFindRelayWebhookUrl(channel));
    }

    private CompletableFuture<String> createOrFindRelayWebhookUrl(TextChannel channel) {
        if (!channel.getGuild().getSelfMember().hasPermission(channel, Permission.MANAGE_WEBHOOKS)) {
            warnRelayOnce(
                    "missing-manage-webhooks:" + channel.getId(),
                    "FoDiscordBot needs Manage Webhooks in #" + channel.getName() + " to use per-player names and skin avatars for chat relay."
            );
            CompletableFuture<String> unavailable = CompletableFuture.completedFuture(null);
            unavailable.whenComplete((ignored, throwable) -> relayWebhookUrls.remove(channel.getId(), unavailable));
            return unavailable;
        }

        JDA active = jda;
        if (active == null) {
            CompletableFuture<String> unavailable = CompletableFuture.completedFuture(null);
            unavailable.whenComplete((ignored, throwable) -> relayWebhookUrls.remove(channel.getId(), unavailable));
            return unavailable;
        }

        String configuredName = sanitizeWebhookName(plugin.getPluginConfig().chatBridgeWebhookName());
        CompletableFuture<String> future = channel.retrieveWebhooks().submit().thenCompose(webhooks -> {
            for (Webhook webhook : webhooks) {
                if (webhook.getOwner() != null && active.getSelfUser().getId().equals(webhook.getOwner().getId())) {
                    return CompletableFuture.completedFuture(webhook.getUrl());
                }
                if (configuredName.equalsIgnoreCase(webhook.getName())) {
                    return CompletableFuture.completedFuture(webhook.getUrl());
                }
            }
            return channel.createWebhook(configuredName).submit().thenApply(Webhook::getUrl);
        });

        future.whenComplete((value, throwable) -> {
            if (throwable != null || value == null || value.isBlank()) {
                relayWebhookUrls.remove(channel.getId(), future);
            }
        });
        return future;
    }

    private String buildMinecraftAvatarUrl(UUID playerUuid, String playerName) {
        return plugin.getSkinAvatarService().avatarUrl(playerUuid, playerName);
    }

    private String buildDiscordToMinecraftMessage(Message message) {
        List<String> parts = new ArrayList<>();
        String content = sanitizeForMinecraft(message.getContentDisplay());
        if (!content.isBlank()) {
            parts.add(content);
        }

        for (Message.Attachment attachment : message.getAttachments()) {
            String attachmentLine = sanitizeForMinecraft("[Attachment: " + attachment.getFileName() + "] " + attachment.getUrl());
            if (!attachmentLine.isBlank()) {
                parts.add(attachmentLine);
            }
        }

        String combined = String.join(" ", parts).trim();
        return truncate(combined, MINECRAFT_MESSAGE_LIMIT);
    }

    private String formatIngameRelayMessage(String discordName, String message) {
        String template = plugin.messages().renderConfigured("ingame.chat-bridge.format");
        return template
                .replace("{discord_name}", discordName)
                .replace("{message}", message);
    }

    private String sanitizeMinecraftToDiscord(String input) {
        String normalized = input == null ? "" : input
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        normalized = ChatColor.stripColor(normalized);
        normalized = truncate(normalized, DISCORD_MESSAGE_LIMIT);
        return normalized;
    }

    private String sanitizeForMinecraft(String input) {
        String normalized = input == null ? "" : input
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\u00A7', ' ')
                .trim();
        normalized = normalized.replace("&", "＆");
        return truncate(normalized, MINECRAFT_MESSAGE_LIMIT);
    }

    private String sanitizeWebhookName(String input) {
        String normalized = firstNonBlank(input, "Minecraft Chat");
        normalized = normalized.replace('\r', ' ').replace('\n', ' ').trim();
        // Remove "discord" (case insensitive) from webhook name as Discord doesn't allow it
        normalized = normalized.replaceAll("(?i)discord", "");
        // Remove any double spaces that might result from removing "discord"
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return truncate(normalized, 32);
    }

    private String sanitizeWebhookUsername(String input) {
        String normalized = firstNonBlank(input, "Minecraft");
        normalized = normalized.replace('\r', ' ').replace('\n', ' ').trim();
        // Remove "discord" (case insensitive) from webhook username as Discord doesn't allow it
        normalized = normalized.replaceAll("(?i)discord", "");
        // Remove any double spaces that might result from removing "discord"
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return truncate(normalized, 32);
    }

    private String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input == null ? "" : input;
        }
        if (maxLength <= 3) {
            return input.substring(0, maxLength);
        }
        return input.substring(0, maxLength - 3) + "...";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String formatRelayUsername(String playerName, String gamemodeId) {
        return plugin.getPluginConfig().chatBridgeRelayNameFormat()
                .replace("{player_name}", firstNonBlank(playerName, "Minecraft"))
                .replace("{gamemode}", firstNonBlank(gamemodeId, plugin.getPluginConfig().normalizedGamemodeId()));
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private void warnRelayOnce(String key, String message) {
        if (relayWarnings.add(key)) {
            plugin.logWarning(message);
        }
    }

    private MessageEmbed createProfileEmbed(ProfileCard card) {
        // Handle same-line fields by combining them into description
        StringBuilder description = new StringBuilder();
        for (ProfileField field : card.fields()) {
            if (field.sameLine()) {
                description.append(field.name()).append(" ").append(field.value()).append("\n");
            }
        }
        
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(new Color(card.color()))
                .setTitle(card.playerName())
                .setThumbnail(card.thumbnailUrl())
                .setFooter(card.footer());
        
        // Set description if there are same-line fields
        if (description.length() > 0) {
            builder.setDescription(description.toString().trim());
        }
        
        // Add regular fields
        for (ProfileField field : card.fields()) {
            if (!field.sameLine()) {
                builder.addField(field.name(), field.value(), field.inline());
            }
        }
        
        return builder.build();
    }

    private MessageEmbed createLeaderboardEmbed(LeaderboardView view) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(new Color(view.color()))
                .setTitle(view.title())
                .setDescription(String.join("\n", view.lines()));

        String footer = view.normalizedFooter();
        if (footer != null) {
            builder.setFooter(footer);
        }

        return builder.build();
    }

    private MessageEmbed createAdvancementEmbed(AdvancementProfileView profile, int requestedPage) {
        int page = clampPage(profile, requestedPage);
        AdvancementDisplayPage displayPage = resolveAdvancementPage(profile, page);
        if (page == 0 || profile.tabs().isEmpty()) {
            return createAdvancementOverviewEmbed(profile, page);
        }
        return createAdvancementTabEmbed(profile, displayPage);
    }

    private MessageEmbed createAdvancementOverviewEmbed(AdvancementProfileView profile, int page) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(new Color(ADVANCEMENT_COLOR))
                .setTitle("🏆 " + profile.playerName() + " Advancements")
                .setThumbnail(buildMinecraftAvatarUrl(profile.playerUuid(), profile.playerName()))
                .setDescription(String.join("\n",
                        "🎮 **Gamemode:** `" + profile.gamemodeId() + "`",
                        "📊 **Progress:** " + formatProgress(profile.completed(), profile.total()),
                        "⭐ **Points:** `" + profile.points() + "`"
                ))
                .setFooter(advancementFooter(profile, page));

        if (profile.tabs().isEmpty()) {
            builder.addField("📁 Tabs", "No advancement data available.", false);
            return builder.build();
        }

        int shown = 0;
        int firstTabPage = 1;
        for (AdvancementTabView tab : profile.tabs()) {
            if (shown >= 24) {
                break;
            }
            int tabPageCount = advancementTabPageCount(tab);
            int entryCount = advancementEntryCount(tab);
            builder.addField(
                    truncate("📁 " + firstNonBlank(tab.title(), tab.id()), 256),
                    advancementOverviewTabValue(tab, entryCount, firstTabPage, tabPageCount),
                    true
            );
            shown++;
            firstTabPage += tabPageCount;
        }
        if (profile.tabs().size() > shown) {
            builder.addField("➕ More", "`" + (profile.tabs().size() - shown) + "` more tabs available with Next.", false);
        }
        return builder.build();
    }

    private MessageEmbed createAdvancementTabEmbed(AdvancementProfileView profile, AdvancementDisplayPage displayPage) {
        AdvancementTabView tab = displayPage.tab();
        String tabTitle = firstNonBlank(tab.title(), tab.id());

        StringBuilder description = new StringBuilder();
        if (tab.description() != null && !tab.description().isEmpty()) {
            description.append("📝 ").append(truncate(String.join("\n", tab.description()), 560)).append("\n\n");
        }
        description.append("📊 **Progress:** ").append(formatProgress(tab.completed(), tab.total())).append("\n");
        description.append("📄 **Tab page:** `").append(displayPage.entryPage() + 1).append("/")
                .append(displayPage.tabPageCount()).append("`");
        description.append(" • 📜 **Entries:** `").append(displayPage.entryCount()).append("`");

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(new Color(ADVANCEMENT_COLOR))
                .setTitle("📁 " + profile.playerName() + " - " + tabTitle)
                .setThumbnail(buildMinecraftAvatarUrl(profile.playerUuid(), profile.playerName()))
                .setDescription(description.toString())
                .setFooter(advancementFooter(profile, displayPage.page()));

        int start = displayPage.entryPage() * MAX_ADVANCEMENT_FIELDS;
        int end = start + MAX_ADVANCEMENT_FIELDS;
        int shown = 0;
        int entryIndex = 0;
        for (AdvancementEntryView entry : tab.advancements()) {
            if (!displayAdvancement(entry)) {
                continue;
            }

            if (entryIndex >= start && entryIndex < end) {
                builder.addField(
                        advancementEntryTitle(entry),
                        advancementEntryValue(entry),
                        false
                );
                shown++;
            }
            entryIndex++;
        }

        if (shown == 0) {
            builder.addField("📜 Advancements", "No advancements in this tab yet.", false);
        }

        return builder.build();
    }

    private String advancementEntryTitle(AdvancementEntryView entry) {
        String state = entry.completed() ? "✅ " : advancementHidden(entry) ? "🔒 " : "⬜ ";
        return truncate(state + firstNonBlank(entry.title(), entry.id()), 256);
    }

    private String advancementEntryValue(AdvancementEntryView entry) {
        List<String> lines = new ArrayList<>();
        lines.add("📊 " + formatProgress(entry.current(), entry.required()) + " • ⭐ Points `" + entry.points() + "`");
        if (advancementHidden(entry)) {
            lines.add("🔒 Hidden in-game");
        }
        if (entry.description() != null && !entry.description().isEmpty()) {
            lines.add("📝 " + truncate(String.join("\n", entry.description()), 650));
        }
        return truncate(String.join("\n", lines), 1_024);
    }

    private List<ActionRow> createAdvancementComponents(String token, AdvancementProfileView profile, int requestedPage) {
        int pageCount = advancementPageCount(profile);
        if (pageCount <= 1) {
            return List.of();
        }

        int page = clampPage(profile, requestedPage);
        List<ActionRow> rows = new ArrayList<>();
        rows.addAll(createAdvancementTabRows(token, profile, page));
        Button previous = Button.secondary(advancementButtonId(token, "prev", Math.max(0, page - 1)), "◀ Prev")
                .withDisabled(page <= 0);
        Button overview = Button.primary(advancementButtonId(token, "overview", 0), "🏠 Overview")
                .withDisabled(page == 0);
        Button next = Button.secondary(advancementButtonId(token, "next", Math.min(pageCount - 1, page + 1)), "Next ▶")
                .withDisabled(page >= pageCount - 1);
        rows.add(ActionRow.of(previous, overview, next));
        return List.copyOf(rows);
    }

    private List<ActionRow> createAdvancementTabRows(String token, AdvancementProfileView profile, int page) {
        if (profile == null || profile.tabs() == null || profile.tabs().isEmpty()) {
            return List.of();
        }

        AdvancementDisplayPage currentPage = resolveAdvancementPage(profile, page);
        int tabCount = profile.tabs().size();
        int firstTab = 0;
        if (tabCount > MAX_ADVANCEMENT_TAB_BUTTONS) {
            int center = Math.max(0, currentPage.tabIndex());
            firstTab = Math.max(0, Math.min(center - (MAX_ADVANCEMENT_TAB_BUTTONS / 2), tabCount - MAX_ADVANCEMENT_TAB_BUTTONS));
        }
        int lastTab = Math.min(tabCount, firstTab + MAX_ADVANCEMENT_TAB_BUTTONS);

        List<ActionRow> rows = new ArrayList<>();
        List<Button> currentRow = new ArrayList<>();
        int firstPageForTab = 1;
        for (int tabIndex = 0; tabIndex < tabCount; tabIndex++) {
            AdvancementTabView tab = profile.tabs().get(tabIndex);
            int tabPageCount = advancementTabPageCount(tab);
            if (tabIndex >= firstTab && tabIndex < lastTab) {
                boolean active = tabIndex == currentPage.tabIndex();
                currentRow.add(advancementTabButton(token, tabIndex, tab, firstPageForTab, active));
                if (currentRow.size() == DISCORD_BUTTONS_PER_ROW) {
                    rows.add(ActionRow.of(currentRow));
                    currentRow.clear();
                }
            }
            firstPageForTab += tabPageCount;
        }

        if (!currentRow.isEmpty()) {
            rows.add(ActionRow.of(currentRow));
        }
        return rows;
    }

    private Button advancementTabButton(String token, int tabIndex, AdvancementTabView tab, int page, boolean active) {
        String label = truncate("📁 " + (tabIndex + 1) + " " + firstNonBlank(tab.title(), tab.id()), 80);
        Button button = active
                ? Button.primary(advancementButtonId(token, "tab" + tabIndex, page), label)
                : Button.secondary(advancementButtonId(token, "tab" + tabIndex, page), label);
        return active ? button.withDisabled(true) : button;
    }

    private String createAdvancementSession(AdvancementProfileView profile) {
        cleanupExpiredAdvancementSessions();

        String token;
        do {
            token = UUID.randomUUID().toString().substring(0, 8);
        } while (advancementSessions.containsKey(token));

        advancementSessions.put(token, new AdvancementSession(profile, Instant.now().plus(ADVANCEMENT_SESSION_TTL)));
        return token;
    }

    private void cleanupExpiredAdvancementSessions() {
        advancementSessions.entrySet().removeIf(entry -> entry.getValue().expired());
    }

    private String advancementButtonId(String token, String action, int page) {
        return ADVANCEMENT_CUSTOM_ID_PREFIX + token + ":" + action + ":" + page;
    }

    private int advancementPageCount(AdvancementProfileView profile) {
        if (profile == null || profile.tabs() == null || profile.tabs().isEmpty()) {
            return 1;
        }

        int pages = 1;
        for (AdvancementTabView tab : profile.tabs()) {
            pages += advancementTabPageCount(tab);
        }
        return pages;
    }

    private int clampPage(AdvancementProfileView profile, int page) {
        int max = Math.max(0, advancementPageCount(profile) - 1);
        return Math.max(0, Math.min(page, max));
    }

    private int parsePage(String input) {
        try {
            return LargeNumberParser.parse(input).orElseThrow().intValueExact();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String advancementFooter(AdvancementProfileView profile, int page) {
        String version = firstNonBlank(profile.pluginVersion(), "unknown");
        return "FoAdvancements " + version + " | Page " + (page + 1) + "/" + advancementPageCount(profile);
    }

    private AdvancementDisplayPage resolveAdvancementPage(AdvancementProfileView profile, int page) {
        if (page <= 0 || profile == null || profile.tabs() == null || profile.tabs().isEmpty()) {
            return AdvancementDisplayPage.overview();
        }

        int currentPage = 1;
        for (int tabIndex = 0; tabIndex < profile.tabs().size(); tabIndex++) {
            AdvancementTabView tab = profile.tabs().get(tabIndex);
            int tabPageCount = advancementTabPageCount(tab);
            if (page < currentPage + tabPageCount) {
                return new AdvancementDisplayPage(
                        page,
                        tabIndex,
                        tab,
                        page - currentPage,
                        tabPageCount,
                        advancementEntryCount(tab)
                );
            }
            currentPage += tabPageCount;
        }

        return AdvancementDisplayPage.overview();
    }

    private int advancementTabPageCount(AdvancementTabView tab) {
        int entryCount = advancementEntryCount(tab);
        return Math.max(1, (entryCount + MAX_ADVANCEMENT_FIELDS - 1) / MAX_ADVANCEMENT_FIELDS);
    }

    private int advancementEntryCount(AdvancementTabView tab) {
        if (tab == null || tab.advancements() == null) {
            return 0;
        }

        int count = 0;
        for (AdvancementEntryView entry : tab.advancements()) {
            if (displayAdvancement(entry)) {
                count++;
            }
        }
        return count;
    }

    private boolean displayAdvancement(AdvancementEntryView entry) {
        return entry != null;
    }

    private boolean advancementHidden(AdvancementEntryView entry) {
        return entry != null && !entry.completed() && (entry.hidden() || !entry.visible());
    }

    private String advancementOverviewTabValue(AdvancementTabView tab, int entryCount, int firstPage, int tabPageCount) {
        List<String> lines = new ArrayList<>();
        lines.add("📊 " + formatProgress(tab.completed(), tab.total()));
        lines.add("📜 `" + entryCount + "` entries");
        lines.add(tabPageCount == 1
                ? "📄 Page `" + (firstPage + 1) + "`"
                : "📄 Pages `" + (firstPage + 1) + "-" + (firstPage + tabPageCount) + "`");
        return String.join("\n", lines);
    }

    private String formatProgress(int completed, int total) {
        int percent = progressPercent(completed, total);
        return progressBar(percent) + " `" + Math.max(0, completed) + "/" + Math.max(0, total) + "` (`" + percent + "%`)";
    }

    private int progressPercent(int completed, int total) {
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, (completed * 100) / total));
    }

    private String progressBar(int percent) {
        int filled = (int) Math.round((Math.max(0, Math.min(100, percent)) / 100.0) * ADVANCEMENT_PROGRESS_BAR_SEGMENTS);
        if (percent > 0 && filled == 0) {
            filled = 1;
        }

        StringBuilder builder = new StringBuilder(ADVANCEMENT_PROGRESS_BAR_SEGMENTS * 2);
        for (int index = 0; index < ADVANCEMENT_PROGRESS_BAR_SEGMENTS; index++) {
            builder.append(index < filled ? "🟩" : "⬛");
        }
        return builder.toString();
    }

    private record AdvancementSession(AdvancementProfileView profile, Instant expiresAt) {
        private boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private record AdvancementDisplayPage(
            int page,
            int tabIndex,
            AdvancementTabView tab,
            int entryPage,
            int tabPageCount,
            int entryCount
    ) {
        private static AdvancementDisplayPage overview() {
            return new AdvancementDisplayPage(0, -1, null, 0, 1, 0);
        }
    }
}
