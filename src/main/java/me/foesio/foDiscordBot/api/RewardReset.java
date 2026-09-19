package me.foesio.foDiscordBot.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable scope of a successfully completed command-driven reward reset. */
public record RewardReset(UUID operationId, Kind kind, String gamemodeId,
                          Scope scope, UUID playerUuid, String discordId, int changedRows) {
    public enum Kind { LINKED, BOOSTER }
    public enum Scope { ALL, PLAYER, DISCORD }

    public RewardReset {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        if (gamemodeId == null || gamemodeId.isBlank()) throw new IllegalArgumentException("gamemodeId");
        if (changedRows < 0) throw new IllegalArgumentException("changedRows");
        boolean validTarget = switch (scope) {
            case ALL -> playerUuid == null && discordId == null;
            case PLAYER -> playerUuid != null && discordId == null;
            case DISCORD -> playerUuid == null && discordId != null && discordId.length() >= 5
                    && discordId.length() <= 32 && discordId.chars().allMatch(Character::isDigit);
        };
        if (!validTarget) throw new IllegalArgumentException("Target does not match reset scope");
    }
}
