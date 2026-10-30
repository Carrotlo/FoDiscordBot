package me.foesio.foDiscordBot.api;

/** Result of an external unlink request. Former identity is present on success only. */
public record UnlinkRequestResult(Status status, LinkedIdentity formerIdentity) {
    public enum Status { UNLINKED, NOT_LINKED }
}
