package fr.murga.tip4serv.scheduler;

import org.bukkit.plugin.Plugin;

/**
 * Factory for creating the appropriate scheduler adapter based on server type.
 * Detects Folia at runtime and returns the correct implementation.
 */
public class SchedulerFactory {

    private static Boolean isFolia = null;

    /**
     * Detects if running on a Folia server.
     * Uses class detection for the Folia-specific RegionizedServer class.
     *
     * @return true if running on Folia, false otherwise
     */
    public static boolean isFolia() {
        if (isFolia == null) {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                isFolia = true;
            } catch (ClassNotFoundException e) {
                isFolia = false;
            }
        }
        return isFolia;
    }

    /**
     * Creates the appropriate scheduler adapter based on server type.
     *
     * @param plugin The plugin instance
     * @return A scheduler adapter for the current server type
     */
    public static SchedulerAdapter createScheduler(Plugin plugin) {
        if (isFolia()) {
            try {
                plugin.getLogger().info("[Tip4Serv] Folia detected - using Folia scheduler");
                return new FoliaSchedulerAdapter(plugin);
            } catch (ReflectiveOperationException e) {
                plugin.getLogger().severe("[Tip4Serv] Failed to initialize Folia scheduler: " + e.getMessage());
                plugin.getLogger().warning("[Tip4Serv] Falling back to Bukkit scheduler (may not work correctly on Folia)");
                return new BukkitSchedulerAdapter(plugin);
            }
        } else {
            plugin.getLogger().info("[Tip4Serv] Using standard Bukkit scheduler");
            return new BukkitSchedulerAdapter(plugin);
        }
    }
}
