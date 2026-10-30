package me.foesio.foDiscordBot.service;

import me.foesio.foDiscordBot.FoDiscordBot;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/** Keeps post-commit observer failures from changing the result of a completed operation. */
final class IntegrationEventDispatcher {

    private IntegrationEventDispatcher() {
    }

    static void firePostEvent(FoDiscordBot plugin, Event event) {
        try {
            Bukkit.getPluginManager().callEvent(event);
        } catch (RuntimeException exception) {
            plugin.logSevere("Failed to dispatch " + event.getEventName() + " after completion: "
                    + exception.getMessage(), exception);
        }
    }
}
