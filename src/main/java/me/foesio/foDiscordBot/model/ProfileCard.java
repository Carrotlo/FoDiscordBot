package me.foesio.foDiscordBot.model;

import java.util.List;
import java.util.UUID;

public record ProfileCard(
        String playerName,
        UUID playerUuid,
        String thumbnailUrl,
        int color,
        String footer,
        List<ProfileField> fields
) {
}
