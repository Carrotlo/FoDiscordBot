package me.foesio.foDiscordBot.model;

import java.util.List;

public record LeaderboardDefinition(
        String alias,
        String title,
        List<String> lines,
        String emptyText,
        String footer
) {

    public String normalizedFooter() {
        return normalizeFooter(footer);
    }

    public static String normalizeFooter(String footer) {
        if (footer == null || footer.isBlank() || "none".equalsIgnoreCase(footer.trim())) {
            return null;
        }
        return footer.trim();
    }
}
