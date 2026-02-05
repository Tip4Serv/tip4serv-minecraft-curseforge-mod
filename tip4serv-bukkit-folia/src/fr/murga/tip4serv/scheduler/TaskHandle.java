package fr.murga.tip4serv.scheduler;

/**
 * Handle to a scheduled task for cancellation.
 */
public interface TaskHandle {

    /**
     * Cancel this scheduled task.
     */
    void cancel();

    /**
     * Check if this task has been cancelled.
     * @return true if cancelled
     */
    boolean isCancelled();
}
