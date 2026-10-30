package me.foesio.foDiscordBot.api.event;

/** Entry point that caused a committed account-link change. */
public enum AccountChangeCause {
    MINECRAFT_COMMAND,
    DISCORD_COMMAND,
    DISCORD_MESSAGE,
    API
}
