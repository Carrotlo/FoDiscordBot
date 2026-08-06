package me.foesio.foDiscordBot.command;

import java.util.Map;
import me.foesio.core.message.FoMessageService;
import me.foesio.foDiscordBot.FoDiscordBot;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class LinkCommand implements CommandExecutor {

    private final FoDiscordBot plugin;

    public LinkCommand(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "ingame.players-only",
                    FoMessageService.missingMessageFallback("ingame.players-only"), Map.of());
            return true;
        }

        if (!plugin.getPluginConfig().hasConfiguredBotToken()) {
            plugin.messages().send(sender, "ingame.discord.not-configured",
                    FoMessageService.missingMessageFallback("ingame.discord.not-configured"), Map.of());
            return true;
        }

        plugin.messages().send(player, "ingame.loading",
                FoMessageService.missingMessageFallback("ingame.loading"), Map.of());
        plugin.getLinkService().createLinkCode(player).whenComplete((response, throwable) ->
                plugin.getCore().scheduler().runForPlayer(player, () -> {
                    if (throwable != null) {
                        plugin.messages().send(player, "ingame.link.error",
                                FoMessageService.missingMessageFallback("ingame.link.error"), Map.of());
                        return;
                    }

                    switch (response.status()) {
                        case SUCCESS -> sendClickableLinkMessage(player, response.code().code());
                        case COOLDOWN -> plugin.messages().send(player, "ingame.link.cooldown",
                                FoMessageService.missingMessageFallback("ingame.link.cooldown"), Map.of(
                                "seconds", String.valueOf(Math.max(1L, response.remaining().toSeconds()))
                        ));
                        case ALREADY_LINKED -> plugin.messages().send(player, "ingame.link.already-linked",
                                FoMessageService.missingMessageFallback("ingame.link.already-linked"), Map.of());
                    }
                }));
        return true;
    }

    private void sendClickableLinkMessage(Player player, String code) {
        String commandToCopy = "/link " + code;
        Map<String, String> placeholders = Map.of(
                "code", code,
                "command", commandToCopy,
                "minutes", String.valueOf(Math.max(1L, plugin.getPluginConfig().codeExpiry().toMinutes()))
        );
        String hoverText = plugin.messages().render("ingame.link.copy-hover",
                FoMessageService.missingMessageFallback("ingame.link.copy-hover"), placeholders);

        BaseComponent[] renderedMessage = TextComponent.fromLegacyText(
                plugin.messages().render("ingame.link.generated",
                        FoMessageService.missingMessageFallback("ingame.link.generated"), placeholders)
        );
        ClickEvent clickEvent = new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, commandToCopy);
        HoverEvent hoverEvent = new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(hoverText).create()
        );

        for (BaseComponent component : renderedMessage) {
            component.setClickEvent(clickEvent);
            component.setHoverEvent(hoverEvent);
        }

        player.spigot().sendMessage(renderedMessage);
    }
}
