package me.foesio.foDiscordBot.api;

import java.time.Instant;
import java.util.UUID;

/** A committed account link. This snapshot contains no link code or reward state. */
public record LinkedIdentity(UUID playerUuid, String playerName, String discordUserId,
                             String discordUsername, String discordDisplayName, Instant linkedAt) {
}
