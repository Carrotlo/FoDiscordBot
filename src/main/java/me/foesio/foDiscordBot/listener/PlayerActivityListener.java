package me.foesio.foDiscordBot.listener;

import me.foesio.foDiscordBot.FoDiscordBot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerActivityListener implements Listener {

    private final FoDiscordBot plugin;

    public PlayerActivityListener(FoDiscordBot plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getSkinAvatarService().cachePlayer(event.getPlayer());
        plugin.getLinkService().handlePlayerJoin(event.getPlayer());
        plugin.getBoosterService().handlePlayerJoin(event.getPlayer());
        plugin.getRankSyncService().handlePlayerJoin(event.getPlayer());
        plugin.getNetworkSyncService().syncPlayerNow(event.getPlayer());
    }
}
