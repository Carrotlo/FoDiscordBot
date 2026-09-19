package me.foesio.foDiscordBot.api;

import java.util.*;
import me.foesio.foDiscordBot.service.RewardResetNotifier;

/** Dependency-free executable test; no Bukkit server or live database required. */
public final class RewardResetTest {
    public static void main(String[] args) {
        var event = new RewardReset(UUID.randomUUID(), RewardReset.Kind.LINKED, "boxpvp",
                RewardReset.Scope.ALL, null, null, 0);
        var received = new ArrayList<RewardReset>();
        var failures = new ArrayList<Throwable>();
        FoDiscordBotAddon legacy = new FoDiscordBotAddon() { public String id() { return "legacy"; } };
        FoDiscordBotAddon broken = new FoDiscordBotAddon() {
            public String id() { return "broken"; }
            public void onRewardsReset(RewardReset reset) { throw new IllegalStateException(); }
        };
        FoDiscordBotAddon incompatible = new FoDiscordBotAddon() {
            public String id() { return "incompatible"; }
            public void onRewardsReset(RewardReset reset) { throw new NoClassDefFoundError(); }
        };
        FoDiscordBotAddon listener = new FoDiscordBotAddon() {
            public String id() { return "listener"; }
            public void onRewardsReset(RewardReset reset) { received.add(reset); }
        };
        RewardResetNotifier.notifyAddons(List.of(legacy, broken, incompatible, listener), event, failures::add);
        if (failures.size() != 2 || received.size() != 1 || received.getFirst() != event)
            throw new AssertionError("Failed callback isolation or payload identity");
        RewardResetNotifier.notifyAddons(List.of(broken, listener), event, failure -> { throw new IllegalStateException(); });
        if (received.size() != 2) throw new AssertionError("Reporter failure blocked notification");
        UUID player = UUID.randomUUID();
        new RewardReset(UUID.randomUUID(), RewardReset.Kind.BOOSTER, "lifesteal", RewardReset.Scope.PLAYER, player, null, 3);
        new RewardReset(UUID.randomUUID(), RewardReset.Kind.LINKED, "lifesteal", RewardReset.Scope.DISCORD, null, "12345", 0);
        try {
            new RewardReset(UUID.randomUUID(), RewardReset.Kind.LINKED, "boxpvp", RewardReset.Scope.ALL, player, null, 0);
            throw new AssertionError("Ambiguous scope accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Reward reset tests passed: zero-row event, legacy addon, runtime/linkage/reporter isolation, target scope.");
    }
}
