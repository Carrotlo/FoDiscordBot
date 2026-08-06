package me.foesio.foDiscordBot.util;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import me.foesio.core.scheduler.FoScheduler;

/**
 * Small consumer adapter for cancellable repeating work until FoScheduler exposes
 * a repeating-task handle. Dispatch itself remains Folia-safe through core.
 */
public final class CoreRepeatingTask implements AutoCloseable {

    private final FoScheduler scheduler;
    private final Runnable task;
    private final long initialDelayTicks;
    private final long periodTicks;
    private final boolean async;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public CoreRepeatingTask(
            FoScheduler scheduler,
            Runnable task,
            long initialDelayTicks,
            long periodTicks,
            boolean async
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.task = Objects.requireNonNull(task, "task");
        this.initialDelayTicks = Math.max(0L, initialDelayTicks);
        this.periodTicks = Math.max(1L, periodTicks);
        this.async = async;
    }

    public void start() {
        schedule(initialDelayTicks);
    }

    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public void close() {
        cancel();
    }

    private void schedule(long delayTicks) {
        if (cancelled.get()) {
            return;
        }
        scheduler.runGlobalLater(() -> {
            if (cancelled.get()) {
                return;
            }
            if (async) {
                scheduler.runAsync(task);
            } else {
                scheduler.runGlobal(task);
            }
            schedule(periodTicks);
        }, delayTicks);
    }
}
