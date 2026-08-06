package me.foesio.foDiscordBot.model;

import java.util.List;
import java.util.UUID;

public record AdvancementProfileView(
        String gamemodeId,
        UUID playerUuid,
        String playerName,
        String pluginVersion,
        int points,
        int completed,
        int total,
        List<AdvancementTabView> tabs
) {
}
