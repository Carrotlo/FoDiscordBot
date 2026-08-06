package me.foesio.foDiscordBot.model;

import java.util.List;

public record AdvancementTabView(
        String id,
        String title,
        List<String> description,
        String icon,
        String background,
        int completed,
        int total,
        List<AdvancementEntryView> advancements
) {
}
