package me.foesio.foDiscordBot.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Controls only delivery of a Minecraft chat message to Discord, never the original chat. */
public final class MinecraftToDiscordChatRelayEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerUuid;
    private final String playerName;
    private String message;
    private boolean cancelled;

    public MinecraftToDiscordChatRelayEvent(UUID playerUuid, String playerName, String message, boolean asynchronous) {
        super(asynchronous);
        this.playerUuid = Objects.requireNonNull(playerUuid);
        this.playerName = Objects.requireNonNull(playerName);
        this.message = Objects.requireNonNull(message);
    }

    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }
    public String message() { return message; }
    public void setMessage(String message) { this.message = Objects.requireNonNull(message); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
