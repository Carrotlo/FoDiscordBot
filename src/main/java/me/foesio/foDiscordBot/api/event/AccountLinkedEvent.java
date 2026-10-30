package me.foesio.foDiscordBot.api.event;

import me.foesio.foDiscordBot.api.LinkedIdentity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired asynchronously after a new link commits, before unrelated reward and role work finishes. */
public final class AccountLinkedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final LinkedIdentity identity;
    private final AccountChangeCause cause;

    public AccountLinkedEvent(LinkedIdentity identity, AccountChangeCause cause) {
        super(true);
        this.identity = identity;
        this.cause = cause;
    }

    public LinkedIdentity identity() {
        return identity;
    }

    public AccountChangeCause cause() {
        return cause;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
