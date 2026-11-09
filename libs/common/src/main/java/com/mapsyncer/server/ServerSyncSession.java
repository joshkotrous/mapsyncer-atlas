package com.mapsyncer.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerSyncSession {

    private static final Map<UUID, Integer> playerSyncVersions = new ConcurrentHashMap<>();

    private ServerSyncSession() {}

    public static int currentVersion(UUID playerId) {
        return playerSyncVersions.getOrDefault(playerId, 0);
    }

    public static boolean isCurrent(UUID playerId, int syncVersion) {
        return playerSyncVersions.getOrDefault(playerId, 0) == syncVersion;
    }

    public static void assignVersion(UUID playerId, int syncVersion) {
        playerSyncVersions.put(playerId, syncVersion);
    }

    public static void removeVersion(UUID playerId) {
        playerSyncVersions.remove(playerId);
    }

    public static void clearAllVersions() {
        playerSyncVersions.clear();
    }

    public static void removeCompletedThreads(Map<UUID, Thread> syncThreads) {
        syncThreads.forEach((id, thread) -> {
            if (!thread.isAlive()) syncThreads.remove(id, thread);
        });
    }

    public static void interruptOldSyncThread(UUID playerId,
            Map<UUID, Thread> syncThreads,
            Runnable clearSpeedLimit) {
        Thread oldThread = syncThreads.get(playerId);
        if (oldThread != null && oldThread.isAlive()) {
            oldThread.interrupt();
            syncThreads.remove(playerId);
            clearSpeedLimit.run();
        }
    }

    public static void finalizeSession(UUID playerId) {
        playerSyncVersions.remove(playerId);
    }
}
