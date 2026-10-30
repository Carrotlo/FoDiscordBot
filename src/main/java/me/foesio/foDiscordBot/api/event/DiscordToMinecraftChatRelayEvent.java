package me.foesio.foDiscordBot.api.event;

import java.util.Objects;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Controls only delivery of a Discord message to Minecraft, never the Discord message. */
public final class DiscordToMinecraftChatRelayEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final String discordUserId;
    private final String channelId;
    private final String authorName;
    private String message;
    private boolean cancelled;

    public DiscordToMinecraftChatRelayEvent(String discordUserId, String channelId, String authorName, String message) {
        super(true);
        this.discordUserId = Objects.requireNonNull(discordUserId);
        this.channelId = Objects.requireNonNull(channelId);
        this.authorName = Objects.requireNonNull(authorName);
        this.message = Objects.requireNonNull(message);
    }

    public String discordUserId() { return discordUserId; }
    public String channelId() { return channelId; }
    public String authorName() { return authorName; }
    public String message() { return message; }
    public void setMessage(String message) { this.message = Objects.requireNonNull(message); }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
