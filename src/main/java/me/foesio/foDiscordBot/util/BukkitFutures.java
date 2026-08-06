package me.foesio.foDiscordBot.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import me.foesio.core.scheduler.FoScheduler;
import me.foesio.foDiscordBot.FoDiscordBot;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitFutures {

    private BukkitFutures() {
    }

    public static <T> CompletableFuture<T> supplyAsync(JavaPlugin plugin, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduler(plugin).runAsync(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public static CompletableFuture<Void> runSync(JavaPlugin plugin, Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        scheduler(plugin).runGlobal(() -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public static <T> CompletableFuture<T> supplySync(JavaPlugin plugin, Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        scheduler(plugin).runGlobal(() -> {
            try {
                future.complete(callable.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private static FoScheduler scheduler(JavaPlugin plugin) {
        if (plugin instanceof FoDiscordBot foDiscordBot && foDiscordBot.getCore() != null) {
            return foDiscordBot.getCore().scheduler();
        }
        throw new IllegalStateException("FoPluginCore scheduler is not available yet.");
    }
}
