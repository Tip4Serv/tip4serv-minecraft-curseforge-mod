package fr.murga.tip4serv.scheduler;

/**
 * Abstraction layer for scheduling tasks across Bukkit/Spigot/Paper and Folia.
 * This allows the plugin to work on both traditional Bukkit servers and Folia
 * without compile-time dependencies on Folia APIs.
 */
public interface SchedulerAdapter {

    /**
     * Run a task asynchronously (off main/region thread).
     * Safe to call from any thread.
     * @param task The task to run
     */
    void runAsync(Runnable task);

    /**
     * Run a task on the appropriate thread for global operations.
     * - Bukkit/Spigot/Paper: Main server thread
     * - Folia: Global region scheduler
     * @param task The task to run
     */
    void runSync(Runnable task);

    /**
     * Schedule an async repeating task.
     * @param task The task to run
     * @param delayTicks Initial delay in ticks (20 ticks = 1 second)
     * @param periodTicks Period between executions in ticks
     * @return A task handle for cancellation
     */
    TaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks);

    /**
     * Cancel all scheduled tasks for this plugin.
     */
    void cancelAllTasks();
}
