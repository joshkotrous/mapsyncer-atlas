package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.AutoSyncConfig;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AutoSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoSyncManager.class);

    private static final ScheduledExecutorService EXECUTOR =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MapSyncer-AutoSync");
            t.setDaemon(true);
            return t;
        });

    private static volatile long lastAutoSyncTimeMs = 0;
    private static volatile ScheduledFuture<?> pendingTask;
    private static volatile ScheduledFuture<?> periodicTask;
    private static volatile boolean active = false;
    private static volatile boolean periodicSync = false;

    private static volatile int serverAutoSyncIntervalMinutes = -1;
    private static volatile UpdateMode serverUpdateMode = UpdateMode.DISABLED;
    private static volatile int serverIntervalTicks = 0;

    public static Object[] getStatusKey(int intervalMinutes) {
        if (XaeroMapIntegrator.isIntegratedServerHost()) {
            return new Object[]{"mapsyncer.autosync.status.integrated_host"};
        }
        try {
            if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
                return new Object[]{"mapsyncer.autosync.status.client_disabled"};
            }
        } catch (IllegalStateException ignored) {

        }
        if (intervalMinutes <= 0) return new Object[]{"mapsyncer.autosync.status.disabled"};
        if (intervalMinutes < 1440) return new Object[]{"mapsyncer.autosync.status.minutes", intervalMinutes};
        return new Object[]{"mapsyncer.autosync.status.daily"};
    }

    public static void configureFromServer(UpdateMode mode, int intervalMinutes, int intervalTicks) {
        serverUpdateMode = mode;
        serverAutoSyncIntervalMinutes = intervalMinutes;
        serverIntervalTicks = intervalTicks;
    }

    public static void resetServerPolicy() {
        serverAutoSyncIntervalMinutes = -1;
        serverUpdateMode = UpdateMode.DISABLED;
        serverIntervalTicks = 0;
    }

    public static boolean isServerPolicyKnown() {
        return serverAutoSyncIntervalMinutes >= 0;
    }

    public static boolean isJoinAutoSyncEnabled() {
        if (XaeroMapIntegrator.isIntegratedServerHost()) return false;
        if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            return false;
        }
        if (serverUpdateMode == UpdateMode.SCHEDULED || serverUpdateMode == UpdateMode.TICK) {
            return true;
        }
        return serverAutoSyncIntervalMinutes > 0;
    }

    public static boolean shouldSyncScheduledOnJoin(long serverGenTime) {
        if (serverGenTime <= 0) {
            LOGGER.debug("Scheduled join auto-sync skipped: server has no generation data");
            return false;
        }
        long clientLastSync = getClientLastSyncTimestamp();
        if (clientLastSync >= serverGenTime) {
            LOGGER.debug("Scheduled join auto-sync skipped: client up-to-date (client={}, server={})",
                    clientLastSync, serverGenTime);
            return false;
        }
        LOGGER.info("Scheduled join auto-sync: client behind server (client={}, server={})",
                clientLastSync, serverGenTime);
        return true;
    }

    public static boolean shouldAutoSync(long serverGenTime, int intervalMinutes) {
        if (intervalMinutes <= 0) {
            LOGGER.debug("Auto-sync disabled (interval={})", intervalMinutes);
            return false;
        }
        if (serverGenTime <= 0) {
            LOGGER.debug("Auto-sync skipped: server has no generation data");
            return false;
        }

        long clientLastSync = getClientLastSyncTimestamp();
        if (serverGenTime <= clientLastSync) {
            LOGGER.debug("Auto-sync skipped: client up-to-date (client={}, server={})",
                clientLastSync, serverGenTime);
            return false;
        }

        long elapsed = System.currentTimeMillis() - lastAutoSyncTimeMs;
        long cooldown = TimeUnit.MINUTES.toMillis(intervalMinutes);
        if (elapsed < cooldown && lastAutoSyncTimeMs > 0) {
            LOGGER.debug("Auto-sync skipped: cooldown ({}m remaining)",
                (cooldown - elapsed) / 60_000);
            return false;
        }

        LOGGER.info("Auto-sync conditions met: serverGen={}, clientSync={}, interval={}m",
            serverGenTime, clientLastSync, intervalMinutes);
        return true;
    }

    public static boolean shouldAutoSyncOnJoin(long serverGenTime, int intervalMinutes) {
        if (XaeroMapIntegrator.isIntegratedServerHost()) return false;
        if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            LOGGER.debug("Join auto-sync skipped: client auto-sync disabled");
            return false;
        }
        if (hasPendingResume()) {
            LOGGER.info("Join auto-sync: resuming interrupted sync");
            return true;
        }

        if (serverUpdateMode == UpdateMode.DISABLED) {
            LOGGER.debug("Join auto-sync skipped: server incremental updates disabled");
            return false;
        }
        if (serverUpdateMode == UpdateMode.SCHEDULED) {
            LOGGER.debug("Join auto-sync: SCHEDULED mode, checking timestamps...");
            return shouldSyncScheduledOnJoin(serverGenTime);
        }
        if (intervalMinutes <= 0) {
            LOGGER.debug("Join auto-sync skipped: intervalMinutes={}", intervalMinutes);
            return false;
        }
        LOGGER.debug("Join auto-sync: TICK mode, checking conditions (serverGenTime={}, intervalMinutes={})...", serverGenTime, intervalMinutes);
        return shouldAutoSync(serverGenTime, intervalMinutes);
    }

    public static boolean hasPendingResume() {
        try {
            Path baseDir = ClientTimestampCache.getLastBaseDir();
            if (baseDir == null) {
                return false;
            }
            ClientTimestampCache cache = ClientTimestampCache.getInstance(baseDir);
            return cache != null && cache.cacheFileExists() && cache.needsResume();
        } catch (Exception e) {
            LOGGER.debug("Failed to check pending resume: {}", e.getMessage());
            return false;
        }
    }

    public static void startTickPeriodicSync(Runnable syncAction) {
        cancelPeriodic();
        if (XaeroMapIntegrator.isIntegratedServerHost()) return;
        if (!PlatformManager.getPlatform().isClientAutoSyncEnabled()) {
            return;
        }
        if (serverUpdateMode != UpdateMode.TICK || serverIntervalTicks <= 0) {
            return;
        }

        long periodMs = AutoSyncConfig.ticksToPeriodMs(serverIntervalTicks);
        LOGGER.info("Starting TICK periodic auto-sync: {} ticks ({} ms)", serverIntervalTicks, periodMs);
        periodicTask = EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                syncAction.run();
            } catch (Exception e) {
                LOGGER.error("TICK periodic auto-sync failed", e);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public static void schedule(Runnable task, int delaySeconds) {
        cancelPending();
        if (XaeroMapIntegrator.isIntegratedServerHost()) return;
        pendingTask = EXECUTOR.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.error("Auto-sync task failed", e);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    public static void markPeriodicSync() {
        periodicSync = true;
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static boolean isPeriodicSync() {
        return periodicSync;
    }

    public static void clearPeriodicSync() {
        periodicSync = false;
    }

    public static void markStarted() {
        active = true;
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static void touchSyncTime() {
        lastAutoSyncTimeMs = System.currentTimeMillis();
    }

    public static void markComplete() {
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static void cancel() {
        active = false;
        periodicSync = false;
        cancelPending();
        cancelPeriodic();
    }

    private static void cancelPending() {
        if (pendingTask != null) {
            pendingTask.cancel(false);
            pendingTask = null;
        }
    }

    private static void cancelPeriodic() {
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
    }

    public static void stopPeriodicSync() {
        cancelPending();
        cancelPeriodic();
    }

    public static void shutdown() {
        cancel();
        resetServerPolicy();
        EXECUTOR.shutdownNow();
    }

    private static long getClientLastSyncTimestamp() {
        try {
            Path baseDir = ClientTimestampCache.getLastBaseDir();
            if (baseDir == null) return 0;

            ClientTimestampCache cache = ClientTimestampCache.getInstance(baseDir);
            if (cache == null) return 0;

            return cache.getAll().values().stream()
                .mapToLong(TimestampHashEntry::timestampSeconds)
                .max().orElse(0);
        } catch (Exception e) {
            LOGGER.debug("Failed to get client last sync timestamp: {}", e.getMessage());
            return 0;
        }
    }
}
