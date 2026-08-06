package me.foesio.foDiscordBot.model;

public record UnlinkResult(Status status, LinkedAccount account) {

    public enum Status {
        SUCCESS,
        NOT_LINKED
    }
}
