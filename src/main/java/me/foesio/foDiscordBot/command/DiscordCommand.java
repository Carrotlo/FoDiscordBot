package me.foesio.foDiscordBot.command;

import java.util.Map;
import me.foesio.core.message.FoMessageService;
import me.foesio.foDiscordBot.FoDiscordBot;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DiscordCommand implements CommandExecutor {

    private final FoDiscordBot plugin;

    public DiscordCommand(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.getPluginConfig().hasConfiguredBotToken()) {
            plugin.messages().send(sender, "ingame.discord.not-configured",
                    FoMessageService.missingMessageFallback("ingame.discord.not-configured"), Map.of());
            return true;
        }

        String inviteUrl = plugin.getPluginConfig().inviteUrl();
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "ingame.discord.text",
                    FoMessageService.missingMessageFallback("ingame.discord.text"), Map.of("url", inviteUrl));
            return true;
        }

        TextComponent message = new TextComponent(plugin.messages().render("ingame.discord.text",
                FoMessageService.missingMessageFallback("ingame.discord.text"), Map.of("url", inviteUrl)));
        message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, inviteUrl));
        message.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(plugin.messages().render("ingame.discord.hover",
                        FoMessageService.missingMessageFallback("ingame.discord.hover"), Map.of())).create()));
        player.spigot().sendMessage(message);
        return true;
    }
}
