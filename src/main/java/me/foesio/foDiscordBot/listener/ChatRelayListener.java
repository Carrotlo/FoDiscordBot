package me.foesio.foDiscordBot.listener;

import me.foesio.foDiscordBot.FoDiscordBot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatRelayListener implements Listener {

    private final FoDiscordBot plugin;

    public ChatRelayListener(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        if (!plugin.getPluginConfig().chatBridgeEnabled()) {
            return;
        }

        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            return;
        }

        plugin.getLinkService().recordPlayerSnapshot(event.getPlayer());
        plugin.getNetworkSyncService().syncPlayerNow(event.getPlayer());
        plugin.getDiscordBotManager().relayMinecraftChat(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName(),
                message
        );
    }
}
