package me.foesio.foDiscordBot.model;

public record DiscordUserSnapshot(
        String userId,
        String username,
        String displayName,
        String mention
) {
}
