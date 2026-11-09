package com.mapsyncer.client;

import com.mapsyncer.sync.SyncOutcome;
import com.mapsyncer.sync.SyncPhase;

public final class ClientSyncSession {

    private static final ClientSyncSession INSTANCE = new ClientSyncSession();

    public static final long STALE_TIMEOUT_MS = 10 * 60 * 1000L;

    private volatile int generation = 0;
    private volatile SyncPhase phase = SyncPhase.IDLE;
    private volatile long startedAt = 0;
    private volatile boolean reflectionFailed = false;
    private volatile SyncOutcome outcome = SyncOutcome.NONE;

    private volatile String serverName = "";

    private ClientSyncSession() {}

    public static ClientSyncSession get() {
        return INSTANCE;
    }

    public int generation() {
        return generation;
    }

    public SyncPhase phase() {
        return phase;
    }

    public long startedAt() {
        return startedAt;
    }

    public boolean reflectionFailed() {
        return reflectionFailed;
    }

    public SyncOutcome outcome() {
        return outcome;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String name) {
        this.serverName = name != null ? name : "";
    }

    public boolean isCurrent(int gen) {
        return gen == generation;
    }

    public boolean isSessionActive() {
        return phase != SyncPhase.IDLE;
    }

    public boolean isStale() {
        if (phase != SyncPhase.RECEIVING || startedAt == 0) {
            return false;
        }
        return System.currentTimeMillis() - startedAt > STALE_TIMEOUT_MS;
    }

    public void invalidate() {
        generation++;
        resetSession();
    }

    public void beginReceiving() {
        phase = SyncPhase.RECEIVING;
        startedAt = System.currentTimeMillis();
        reflectionFailed = false;
        outcome = SyncOutcome.NONE;
    }

    public void markReflectionFailed() {
        reflectionFailed = true;
        if (outcome == SyncOutcome.NONE || outcome == SyncOutcome.SUCCESS) {
            outcome = SyncOutcome.PARTIAL_SUCCESS;
        }
    }

    public void setOutcome(SyncOutcome newOutcome) {
        outcome = newOutcome;
    }

    public void beginDrainingReload() {
        phase = SyncPhase.DRAINING_RELOAD;
    }

    public void completeSession() {
        phase = SyncPhase.IDLE;
        startedAt = 0;
        reflectionFailed = false;
    }

    private void resetSession() {
        phase = SyncPhase.IDLE;
        startedAt = 0;
        reflectionFailed = false;
        outcome = SyncOutcome.NONE;
    }
}
