package me.foesio.foDiscordBot.model;

import java.time.Instant;
import java.util.UUID;

public record PendingLinkCode(
        String code,
        UUID playerUuid,
        String playerName,
        Instant createdAt,
        Instant expiresAt
) {
}
