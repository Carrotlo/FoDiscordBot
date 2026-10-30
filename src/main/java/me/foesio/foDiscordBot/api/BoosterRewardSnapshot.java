package me.foesio.foDiscordBot.api;

import java.util.UUID;

/** Stored local booster reward state, not a live Discord role check. */
public record BoosterRewardSnapshot(UUID playerUuid, String gamemodeId, boolean currentReward,
                                    boolean linkBroken, String rewardedDiscordUserId) {
}
