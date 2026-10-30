package me.foesio.foDiscordBot.api.event;

import me.foesio.foDiscordBot.api.LinkedIdentity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired asynchronously after a link removal commits. Identity describes the former link. */
public final class AccountUnlinkedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final LinkedIdentity formerIdentity;
    private final AccountChangeCause cause;

    public AccountUnlinkedEvent(LinkedIdentity formerIdentity, AccountChangeCause cause) {
        super(true);
        this.formerIdentity = formerIdentity;
        this.cause = cause;
    }

    public LinkedIdentity formerIdentity() {
        return formerIdentity;
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
