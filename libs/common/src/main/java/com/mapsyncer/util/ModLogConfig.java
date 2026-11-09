package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModLogConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModLogConfig.class);
    private static volatile boolean debugEnabled = false;

    private ModLogConfig() {}

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void applyDebugLogging() {
        try {
            boolean enableDebug = com.mapsyncer.platform.PlatformManager.getPlatform().isDebugLoggingEnabled();
            if (enableDebug == debugEnabled) return;
            debugEnabled = enableDebug;
            if (enableDebug) {
                setLoggerLevel("com.mapsyncer", "DEBUG");
                LOGGER.info("Debug logging enabled");
            } else {
                setLoggerLevel("com.mapsyncer", "INFO");
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to configure debug logging level", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setLoggerLevel(String packageName, String level) {
        try {

            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");

            Object logger = logManagerClass.getMethod("getLogger", String.class).invoke(null, packageName);
            Object logLevel = levelClass.getMethod("toLevel", String.class).invoke(null, level);
            configuratorClass.getMethod("setAllLevels", String.class, levelClass)
                    .invoke(null, logger.getClass().getMethod("getName").invoke(logger), logLevel);
        } catch (ClassNotFoundException ignored) {
            // Log4j2 not available
        } catch (Exception e) {
            LOGGER.warn("Failed to set logger level via reflection", e);
        }
    }
}
