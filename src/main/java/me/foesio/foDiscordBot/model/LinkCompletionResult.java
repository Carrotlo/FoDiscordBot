package me.foesio.foDiscordBot.model;

public record LinkCompletionResult(Status status, LinkedAccount account, boolean grantRewards) {

    public enum Status {
        SUCCESS,
        INVALID_CODE,
        EXPIRED_CODE,
        DISCORD_ALREADY_LINKED,
        PLAYER_ALREADY_LINKED
    }
}
