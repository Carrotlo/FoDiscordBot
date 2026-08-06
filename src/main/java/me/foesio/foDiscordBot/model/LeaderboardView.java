package me.foesio.foDiscordBot.model;

import java.util.List;

public record LeaderboardView(String title, int color, List<String> lines, String footer) {

    public String normalizedFooter() {
        return LeaderboardDefinition.normalizeFooter(footer);
    }
}
