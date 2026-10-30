package me.foesio.foDiscordBot.api;

/** Outcome of requesting Discord slash-command registration. */
public enum CommandRefreshResult {
    UPDATED,
    UNAVAILABLE,
    FAILED
}
