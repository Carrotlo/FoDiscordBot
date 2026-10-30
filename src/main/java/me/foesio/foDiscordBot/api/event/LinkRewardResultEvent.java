package me.foesio.foDiscordBot.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Reports a completed link reward attempt, excluding already-claimed and unlinked checks. */
public final class LinkRewardResultEvent extends Event {
    public enum Result { GRANTED, FAILED }
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUuid;
    private final String gamemodeId;
    private final Result result;

    public LinkRewardResultEvent(UUID playerUuid, String gamemodeId, Result result) {
        super(true);
        this.playerUuid = Objects.requireNonNull(playerUuid);
        this.gamemodeId = Objects.requireNonNull(gamemodeId);
        this.result = Objects.requireNonNull(result);
    }

    public UUID playerUuid() { return playerUuid; }
    public String gamemodeId() { return gamemodeId; }
    public Result result() { return result; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
