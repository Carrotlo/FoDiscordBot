package me.foesio.foDiscordBot.model;

import java.time.Instant;
import java.util.UUID;

public record LinkedAccount(
        UUID playerUuid,
        String playerName,
        String discordUserId,
        String discordUsername,
        String discordDisplayName,
        Instant linkedAt,
        Instant updatedAt,
        boolean rewardsClaimed,
        Instant rewardsClaimedAt
) {

    public boolean isLinked() {
        return discordUserId != null && !discordUserId.isBlank();
    }
}
