package me.foesio.foDiscordBot.api;

import java.time.Duration;

/** Result of a one-time-code account-link request. Identity is present on success only. */
public record LinkRequestResult(Status status, LinkedIdentity identity, Duration retryAfter) {
    public enum Status { LINKED, COOLDOWN, INVALID_CODE, EXPIRED_CODE, DISCORD_ALREADY_LINKED, PLAYER_ALREADY_LINKED }
}
