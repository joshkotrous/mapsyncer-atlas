package com.mapsyncer.config;

public final class ConcurrentRegionsConfig {

    public static final int MAX_CONCURRENT = 16;

    public static final int AUTO = 0;

    private ConcurrentRegionsConfig() {}

    public static int resolve(int configured) {
        if (configured > 0) {
            return Math.max(1, Math.min(MAX_CONCURRENT, configured));
        }
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(MAX_CONCURRENT, processors - 2));
    }

    public static int clampConfigured(int configured) {
        return Math.max(AUTO, Math.min(MAX_CONCURRENT, configured));
    }
}
