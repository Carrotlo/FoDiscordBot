package me.foesio.foDiscordBot.model;

import java.util.List;

public record PendingRewardState(
        LinkedAccount account,
        List<String> rewardCommands,
        String encodedCommandSnapshot,
        int nextCommandIndex
) {
}
