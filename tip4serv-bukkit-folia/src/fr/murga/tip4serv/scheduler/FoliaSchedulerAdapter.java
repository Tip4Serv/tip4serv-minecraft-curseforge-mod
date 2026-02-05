package fr.murga.tip4serv.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduler adapter for Folia servers.
 * Uses reflection to access Folia's scheduler APIs without compile-time dependencies.
 */
public class FoliaSchedulerAdapter implements SchedulerAdapter {

    private final Plugin plugin;
    private final Object asyncScheduler;
    private final Object globalRegionScheduler;
    private final Method asyncRunNow;
    private final Method asyncRunAtFixedRate;
    private final Method asyncCancelTasks;
    private final Method globalRun;

    public FoliaSchedulerAdapter(Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;

        Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
        this.asyncScheduler = getAsyncScheduler.invoke(null);

        Method getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
        this.globalRegionScheduler = getGlobalRegionScheduler.invoke(null);

        Class<?> asyncSchedulerClass = asyncScheduler.getClass();
        this.asyncRunNow = findMethod(asyncSchedulerClass, "runNow");
        this.asyncRunAtFixedRate = findMethod(asyncSchedulerClass, "runAtFixedRate");
        this.asyncCancelTasks = findMethod(asyncSchedulerClass, "cancelTasks");

        Class<?> globalSchedulerClass = globalRegionScheduler.getClass();
        this.globalRun = findMethod(globalSchedulerClass, "run");
    }

    /**
     * Find a method by name in a class or its interfaces.
     */
    private Method findMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name + " not found in " + clazz.getName());
    }

    @Override
    public void runAsync(Runnable task) {
        try {
            Consumer<Object> consumer = scheduledTask -> task.run();
            asyncRunNow.invoke(asyncScheduler, plugin, consumer);
        } catch (Exception e) {
            plugin.getLogger().severe("[Tip4Serv] Failed to run async task: " + e.getMessage());
        }
    }

    @Override
    public void runSync(Runnable task) {
        try {
            Consumer<Object> consumer = scheduledTask -> task.run();
            globalRun.invoke(globalRegionScheduler, plugin, consumer);
        } catch (Exception e) {
            plugin.getLogger().severe("[Tip4Serv] Failed to run sync task: " + e.getMessage());
        }
    }

    @Override
    public TaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        try {
            long delayMs = Math.max(1, delayTicks * 50);
            long periodMs = Math.max(1, periodTicks * 50);

            Consumer<Object> consumer = scheduledTask -> task.run();

            Object scheduledTask = asyncRunAtFixedRate.invoke(
                    asyncScheduler, plugin, consumer, delayMs, periodMs, TimeUnit.MILLISECONDS);

            return new FoliaTaskHandle(scheduledTask);
        } catch (Exception e) {
            plugin.getLogger().severe("[Tip4Serv] Failed to schedule repeating task: " + e.getMessage());
            return new NoOpTaskHandle();
        }
    }

    @Override
    public void cancelAllTasks() {
        try {
            asyncCancelTasks.invoke(asyncScheduler, plugin);
        } catch (Exception e) {
            plugin.getLogger().severe("[Tip4Serv] Failed to cancel tasks: " + e.getMessage());
        }
    }

    /**
     * TaskHandle implementation for Folia scheduled tasks.
     */
    private static class FoliaTaskHandle implements TaskHandle {
        private final Object task;
        private final Method cancelMethod;
        private final Method isCancelledMethod;

        FoliaTaskHandle(Object task) throws ReflectiveOperationException {
            this.task = task;
            Class<?> taskClass = task.getClass();

            this.cancelMethod = taskClass.getMethod("cancel");
            this.isCancelledMethod = taskClass.getMethod("isCancelled");
        }

        @Override
        public void cancel() {
            try {
                cancelMethod.invoke(task);
            } catch (Exception ignored) {
            }
        }

        @Override
        public boolean isCancelled() {
            try {
                return (Boolean) isCancelledMethod.invoke(task);
            } catch (Exception e) {
                return true;
            }
        }
    }

    /**
     * No-op task handle for error cases.
     */
    private static class NoOpTaskHandle implements TaskHandle {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    }
}
