package me.foesio.foDiscordBot.model;

import java.util.List;

public record AdvancementEntryView(
        String id,
        String fullId,
        String title,
        List<String> description,
        String icon,
        String frame,
        int current,
        int required,
        boolean completed,
        boolean visible,
        boolean hidden,
        int points
) {

    public int percent() {
        if (required <= 0) {
            return 100;
        }
        return Math.min(100, Math.max(0, (current * 100) / required));
    }

    public String descriptionText() {
        return String.join("\n", description == null ? List.of() : description);
    }
}
