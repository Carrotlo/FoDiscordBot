package me.foesio.foDiscordBot.service;

import java.util.List;
import java.util.function.Consumer;
import me.foesio.foDiscordBot.api.FoDiscordBotAddon;
import me.foesio.foDiscordBot.api.RewardReset;

/** Best-effort local notification; never retries or repeats the host reset. */
public final class RewardResetNotifier {
    private RewardResetNotifier() {}

    public static void notifyAddons(List<FoDiscordBotAddon> addons, RewardReset reset,
                                   Consumer<Throwable> failureReporter) {
        for (FoDiscordBotAddon addon : List.copyOf(addons)) {
            try {
                addon.onRewardsReset(reset);
            } catch (RuntimeException | LinkageError failure) {
                try {
                    failureReporter.accept(failure);
                } catch (RuntimeException | LinkageError ignored) {
                    // A diagnostic failure cannot change a committed reset or starve other addons.
                }
            }
        }
    }
}
