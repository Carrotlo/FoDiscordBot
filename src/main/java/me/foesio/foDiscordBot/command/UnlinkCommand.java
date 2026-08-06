package me.foesio.foDiscordBot.command;

import java.util.Map;
import me.foesio.core.message.FoMessageService;
import me.foesio.foDiscordBot.FoDiscordBot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class UnlinkCommand implements CommandExecutor {

    private final FoDiscordBot plugin;

    public UnlinkCommand(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "ingame.players-only",
                    FoMessageService.missingMessageFallback("ingame.players-only"), Map.of());
            return true;
        }

        plugin.messages().send(player, "ingame.loading",
                FoMessageService.missingMessageFallback("ingame.loading"), Map.of());
        plugin.getLinkService().unlink(player).whenComplete((response, throwable) ->
                plugin.getCore().scheduler().runForPlayer(player, () -> {
                    if (throwable != null) {
                        plugin.messages().send(player, "ingame.unlink.error",
                                FoMessageService.missingMessageFallback("ingame.unlink.error"), Map.of());
                        return;
                    }

                    switch (response.status()) {
                        case SUCCESS -> plugin.messages().send(player, "ingame.unlink.success",
                                FoMessageService.missingMessageFallback("ingame.unlink.success"), Map.of());
                        case NOT_LINKED -> plugin.messages().send(player, "ingame.unlink.not-linked",
                                FoMessageService.missingMessageFallback("ingame.unlink.not-linked"), Map.of());
                    }
                }));
        return true;
    }
}
