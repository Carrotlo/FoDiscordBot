package me.foesio.foDiscordBot.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired asynchronously after a Discord connection and its slash-command registration succeed. */
public final class DiscordBotReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public DiscordBotReadyEvent() {
        super(true);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
