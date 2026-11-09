package com.mapsyncer.platform;

public final class PlatformManager {

    private static volatile Platform instance;

    private PlatformManager() {

    }

    public static void initialize(Platform platform) {
        if (instance != null) {
            throw new IllegalStateException("Platform already initialized");
        }
        instance = platform;
    }

    public static Platform getPlatform() {
        if (instance == null) {
            throw new IllegalStateException("Platform not initialized. Call initialize() first.");
        }
        return instance;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public static String getServerName() {
        if (instance == null) {
            return "";
        }
        return instance.getServerName();
    }

    public static void reset() {
        instance = null;
    }
}
