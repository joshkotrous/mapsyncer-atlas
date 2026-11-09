package com.mapsyncer.config;

public final class TimeoutConfig {

    public static final long TASK_TIMEOUT_SECONDS = 60;

    public static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

    public static final long SERVER_RESPONSE_TIMEOUT_MS = 5000;

    public static final long MAX_SPEED_LIMIT_CYCLE_MS = 1000;

    private TimeoutConfig() {}
}
