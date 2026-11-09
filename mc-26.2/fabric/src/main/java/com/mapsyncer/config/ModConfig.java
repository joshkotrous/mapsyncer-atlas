package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.util.PropertiesHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Properties;

import com.mapsyncer.config.DimensionConfigParser;
import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ScanMode;

public class ModConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModConfig.class);

    private static final String SERVER_CONFIG_FILE_NAME = "mapsyncer-server.properties";

    private static final String CLIENT_CONFIG_FILE_NAME = "mapsyncer-client.properties";

    private static volatile ServerConfig serverInstance;

    private static volatile ClientConfig clientInstance;

    private static volatile Path serverConfigPath;

    private static volatile Path clientConfigPath;

    public static ServerConfig getServerConfig(Path configDir) {
        if (serverInstance == null) {
            synchronized (ServerConfig.class) {
                if (serverInstance == null) {
                    serverConfigPath = configDir.resolve(SERVER_CONFIG_FILE_NAME);
                    serverInstance = new ServerConfig(serverConfigPath);
                    LOGGER.info("ServerConfig initialized with path: {}", serverConfigPath);
                }
            }
        }
        return serverInstance;
    }

    public static ClientConfig getClientConfig(Path configDir) {
        if (clientInstance == null) {
            synchronized (ClientConfig.class) {
                if (clientInstance == null) {
                    clientConfigPath = configDir.resolve(CLIENT_CONFIG_FILE_NAME);
                    clientInstance = new ClientConfig(clientConfigPath);
                    LOGGER.info("ClientConfig initialized with path: {}", clientConfigPath);
                }
            }
        }
        return clientInstance;
    }

    public static void resetInstance() {
        if (serverInstance != null) {
            serverInstance = null;
            serverConfigPath = null;
            LOGGER.info("ServerConfig instance reset");
        }
        if (clientInstance != null) {
            clientInstance = null;
            clientConfigPath = null;
            LOGGER.info("ClientConfig instance reset");
        }
    }

    public static ServerConfig SERVER() {
        if (serverInstance == null) {
            throw new IllegalStateException("ServerConfig not initialized. Call getServerConfig() first.");
        }
        return serverInstance;
    }

    public static ClientConfig CLIENT() {
        if (clientInstance == null) {
            throw new IllegalStateException("ClientConfig not initialized. Call getClientConfig() first.");
        }
        return clientInstance;
    }

    public static class ClientConfig {

        private volatile int hashThreads;

        private volatile int mapRegionLoadIntervalTicks = 1;

        private volatile boolean autoSyncEnabled = true;

        private volatile boolean integratedHostSilentUpdates = true;

        private final Path configFile;

        public ClientConfig(Path configFile) {
            this.configFile = configFile;

            int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            this.hashThreads = defaultThreads;
            load();

            if (!Files.exists(configFile)) {
                save();
            }
        }

        private void load() {
            if (!Files.exists(configFile)) {
                LOGGER.info("Client config file not found, using defaults (hashThreads={})", hashThreads);
                return;
            }

            try (InputStream is = Files.newInputStream(configFile)) {
                Properties props = new Properties();
                props.load(is);

                int maxThreads = Runtime.getRuntime().availableProcessors();
                int loadedThreads = Integer.parseInt(props.getProperty("hashThreads", String.valueOf(hashThreads)));

                hashThreads = Math.max(1, Math.min(maxThreads, loadedThreads));

                String intervalProp = props.getProperty("mapRegionLoadIntervalTicks");
                if (intervalProp == null) {
                    intervalProp = props.getProperty("mapRegionLoadsPerTick", "1");
                }
                int loadedInterval = Integer.parseInt(intervalProp);
                mapRegionLoadIntervalTicks = Math.max(-1, Math.min(100, loadedInterval));

                autoSyncEnabled = Boolean.parseBoolean(
                        props.getProperty("autoSyncEnabled", "true"));
                integratedHostSilentUpdates = Boolean.parseBoolean(
                        props.getProperty("integratedHostSilentUpdates", "true"));

                LOGGER.info("Loaded client config from: {} (hashThreads={}, mapRegionLoadIntervalTicks={}, autoSyncEnabled={})", configFile, hashThreads, mapRegionLoadIntervalTicks, autoSyncEnabled);
            } catch (Exception e) {
                LOGGER.warn("Failed to load client config, using defaults: {}", e.getMessage());
            }
        }

        public void save() {
            try {
                Files.createDirectories(configFile.getParent());

                int maxThreads = Runtime.getRuntime().availableProcessors();

                StringBuilder sb = new StringBuilder();
                sb.append("# MapSyncer Client Configuration\n");
                sb.append("#\n");
                sb.append("# Client settings\n");
                sb.append("#\n");
                sb.append("# Number of hashing threads used for parallel map synchronization\n");
                sb.append("# Number of threads for hash computation during map sync\n");
                sb.append("#\n");
                sb.append("# Defaults to half the available processors to avoid blocking the game thread\n");
                sb.append("# Default uses half of available processors to avoid blocking game main thread\n");
                sb.append("#\n");
                sb.append("# Suggested thread counts:\n");
                sb.append("#   1-2 cores: use 1 thread\n");
                sb.append("#   4 cores: use 2 threads (default on most systems)\n");
                sb.append("#   8+ cores: use 4-8 threads for faster synchronization\n");
                sb.append("# Thread count recommendations:\n");
                sb.append("#   1-2 cores: use 1 thread\n");
                sb.append("#   4 cores: use 2 threads (default for most setups)\n");
                sb.append("#   8+ cores: use 4-8 threads for faster sync\n");
                sb.append("#\n");
                sb.append("# Default: " + hashThreads + " (half of the " + maxThreads + " available processors)\n");
                sb.append("# Default: " + hashThreads + " (half of " + maxThreads + " available processors)\n");
                sb.append("# Range: 1 - " + maxThreads + "\n");
                sb.append("# Range: 1 - " + maxThreads + "\n");
                sb.append("hashThreads=" + hashThreads + "\n");
                sb.append("#\n");
                sb.append("# Client tick interval for passing out-of-view regions to Xaero (1 = one per tick)\n");
                sb.append("# Tick interval between loading each out-of-view region into Xaero (1 = every tick)\n");
                sb.append("# Limits the Xaero MapProcessor queue to prevent out-of-memory failures\n");
                sb.append("# Limits Xaero MapProcessor queue size to prevent OOM\n");
                sb.append("#\n");
                sb.append("#  -1 = unlimited (drain in one pass)\n");
                sb.append("#   0 = load only regions in view\n");
                sb.append("# 1-100 = load one region every N ticks (default: 1)\n");
                sb.append("#  -1 = Unlimited (drain all at once)\n");
                sb.append("#   0 = View-distance only\n");
                sb.append("# 1-100 = one region every N ticks (default: 1)\n");
                sb.append("# The legacy mapRegionLoadsPerTick key remains readable\n");
                sb.append("# Legacy key mapRegionLoadsPerTick is still accepted on load\n");
                sb.append("mapRegionLoadIntervalTicks=" + mapRegionLoadIntervalTicks + "\n");
                sb.append("#\n");
                sb.append("# Enable automatic join sync for TICK/SCHEDULED and periodic online sync for TICK\n");
                sb.append("# Enable join auto-sync (TICK/SCHEDULED) and online periodic sync (TICK only)\n");
                sb.append("# Manual /mapsyncer sync remains available\n");
                sb.append("# Manual /mapsyncer sync is always available\n");
                sb.append("autoSyncEnabled=" + autoSyncEnabled + "\n");
                sb.append("# Integrated-server host only: update out-of-view maps silently; false disables local watching and refresh\n");
                sb.append("# Host-only silent out-of-view map updates; false disables the local watcher and refresh queue\n");
                sb.append("integratedHostSilentUpdates=" + integratedHostSilentUpdates + "\n");

                Files.writeString(configFile, sb.toString());

                LOGGER.info("Saved client config to: {} (hashThreads={}, mapRegionLoadIntervalTicks={}, autoSyncEnabled={})", configFile, hashThreads, mapRegionLoadIntervalTicks, autoSyncEnabled);
            } catch (Exception e) {
                LOGGER.error("Failed to save client config: {}", e.getMessage());
            }
        }

        public int getHashThreads() {
            return hashThreads;
        }

        public int getMapRegionLoadIntervalTicks() {
            return mapRegionLoadIntervalTicks;
        }

        public void setHashThreads(int value) {
            int maxThreads = Runtime.getRuntime().availableProcessors();
            hashThreads = Math.max(1, Math.min(maxThreads, value));
        }

        public boolean isIntegratedHostSilentUpdateEnabled() { return integratedHostSilentUpdates; }

        public void setIntegratedHostSilentUpdates(boolean enabled) { integratedHostSilentUpdates = enabled; }

        public boolean isAutoSyncEnabled() {
            return autoSyncEnabled;
        }

        public void setAutoSyncEnabled(boolean enabled) {
            autoSyncEnabled = enabled;
        }

        public void setMapRegionLoadIntervalTicks(int value) {
            mapRegionLoadIntervalTicks = Math.max(-1, Math.min(100, value));
        }
    }

    public static class ServerConfig {

        private volatile boolean enableDebugLogging = false;
        private volatile int maxConcurrentRegions = ConcurrentRegionsConfig.AUTO;
        private volatile int maxSyncPacketSize = 262144;
        private volatile int syncSpeedLimitKBps = 1024;
        private volatile int syncRadiusRegions = 0;

        private volatile String serverName = "";

        private volatile UpdateMode incrementalUpdateMode = UpdateMode.DISABLED;
        private volatile int incrementalUpdateIntervalTicks = 6000;
        private volatile int scheduledUpdateHour = 4;
        private volatile int scheduledUpdateMinute = 0;

        private volatile ScanMode defaultScanMode = ScanMode.SURFACE;
        private volatile int defaultCaveStart = 63;
        private volatile List<String> dimensionConfigs = new ArrayList<>();

        private final Path configFile;

        public ServerConfig(Path configFile) {
            this.configFile = configFile;
            load();

            if (dimensionConfigs.isEmpty()) {
                dimensionConfigs = getDefaultDimensionConfigStrings();
            }

            if (!Files.exists(configFile)) {
                save();
            }
        }

        private List<String> getDefaultDimensionConfigStrings() {
            return DimensionConfigParser.getDefaultDimensionConfigStrings();
        }

        private void load() {
            if (!Files.exists(configFile)) {
                LOGGER.info("Config file not found, using defaults");
                return;
            }

            try {
                String fileText = Files.readString(configFile);
                String propsText = DimensionConfigParser.stripDimensionConfigsListBlock(fileText);
                Properties props = new Properties();
                try (StringReader reader = new StringReader(propsText)) {
                    props.load(reader);
                }

                enableDebugLogging = Boolean.parseBoolean(
                        PropertiesHelper.get(props, "enableDebugLogging", "enable_debug_logging", "false"));
                maxConcurrentRegions = ConcurrentRegionsConfig.clampConfigured(Integer.parseInt(
                        PropertiesHelper.get(props, "maxConcurrentRegions", "max_concurrent_regions", "0")));
                maxSyncPacketSize = Integer.parseInt(
                        PropertiesHelper.get(props, "maxSyncPacketSize", "max_sync_packet_size", "262144"));
                syncRadiusRegions = Math.max(0, Integer.parseInt(
                        PropertiesHelper.get(props, "syncRadiusRegions", "sync_radius_regions", "0")));
                syncSpeedLimitKBps = Integer.parseInt(
                        PropertiesHelper.get(props, "syncSpeedLimitKBps", "sync_speed_limit_kbps", "1024"));

                serverName = PropertiesHelper.get(props, "serverName", "server_name", "");

                incrementalUpdateMode = UpdateMode.valueOf(
                        PropertiesHelper.get(props, "incrementalUpdateMode", "incremental_update_mode", "DISABLED"));
                incrementalUpdateIntervalTicks = Integer.parseInt(
                        PropertiesHelper.get(props, "incrementalUpdateIntervalTicks", "incremental_update_interval_ticks", "6000"));
                scheduledUpdateHour = Integer.parseInt(
                        PropertiesHelper.get(props, "scheduledUpdateHour", "scheduled_update_hour", "4"));
                scheduledUpdateMinute = Integer.parseInt(
                        PropertiesHelper.get(props, "scheduledUpdateMinute", "scheduled_update_minute", "0"));

                defaultScanMode = ScanMode.valueOf(
                        PropertiesHelper.get(props, "defaultScanMode", "default_scan_mode", "SURFACE"));
                defaultCaveStart = Integer.parseInt(
                        PropertiesHelper.get(props, "defaultCaveStart", "default_cave_start", "63"));

                dimensionConfigs.clear();
                dimensionConfigs.addAll(DimensionConfigParser.loadDimensionConfigEntries(fileText, props));

                LOGGER.info("Loaded config from: {}", configFile);
            } catch (Exception e) {
                LOGGER.warn("Failed to load config, using defaults: {}", e.getMessage());
            }
        }

        public void reload() {
            load();
            DimensionConfigParser.invalidateCache();
        }

        public void save() {
            try {
                Files.createDirectories(configFile.getParent());

                StringBuilder sb = new StringBuilder();
                sb.append("# MapSyncer Server Configuration\n");
                sb.append("#\n");

                sb.append("# ========================================\n");
                sb.append("# General settings\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# Enable debug logging for map generation\n");
                sb.append("# Enable debug logging for map generation\n");
                sb.append("enableDebugLogging=" + enableDebugLogging + "\n");
                sb.append("\n");
                sb.append("# Concurrent region conversions; 0 = automatic (logical processors minus 2, minimum 1, maximum 16)\n");
                sb.append("# Max concurrent region conversions; 0 = auto (logical CPUs - 2, min 1, max 16)\n");
                sb.append("# Range: 0 - 16 (0 = automatic)\n");
                sb.append("maxConcurrentRegions=" + maxConcurrentRegions + "\n");
                sb.append("\n");
                sb.append("# Maximum sync packet size in bytes\n");
                sb.append("# Maximum sync packet size in bytes\n");
                sb.append("#\n");
                sb.append("# Reference packet sizes (divide evenly into 1024 KB/s):\n");
                sb.append("#   65536  = 64 KB  (conservative; 16 packets/second at 1024 KB/s)\n");
                sb.append("#   131072 = 128 KB (balanced; 8 packets/second at 1024 KB/s)\n");
                sb.append("#   262144 = 256 KB (recommended; 4 packets/second at 1024 KB/s)\n");
                sb.append("#   524288 = 512 KB (efficient; 2 packets/second at 1024 KB/s)\n");
                sb.append("#   1048576 = 1 MB (maximum; 1 packet/second at 1024 KB/s)\n");
                sb.append("# Default: 256 KB (recommended); range: 64 KB - 1 MB\n");
                sb.append("#\n");
                sb.append("# Size options for quick reference (all divide 1024KB/s evenly):\n");
                sb.append("#   65536  = 64KB  (conservative, 16 packets/s at 1024KB/s)\n");
                sb.append("#   131072 = 128KB (balanced, 8 packets/s at 1024KB/s)\n");
                sb.append("#   262144 = 256KB (recommended, 4 packets/s at 1024KB/s)\n");
                sb.append("#   524288 = 512KB (efficient, 2 packets/s at 1024KB/s)\n");
                sb.append("#   1048576 = 1MB  (maximum, 1 packet/s at 1024KB/s)\n");
                sb.append("# Default: 256KB (recommended), Range: 64KB - 1MB\n");
                sb.append("maxSyncPacketSize=" + maxSyncPacketSize + "\n");
                sb.append("\n");
                sb.append("# Sync transfer limit in KB/s (0 = unlimited)\n");
                sb.append("# Sync speed limit in KB/s (0 = unlimited)\n");
                sb.append("#\n");
                sb.append("# Reference transfer speeds:\n");
                sb.append("#   100 = 100 KB/s (slow; for limited bandwidth)\n");
                sb.append("#   512 = 512 KB/s (moderate; half a MiB/s)\n");
                sb.append("#   1024 = 1024 KB/s = 1 MiB/s (default; recommended)\n");
                sb.append("#   5120 = 5120 KB/s = 5 MiB/s (fast; suitable for a LAN)\n");
                sb.append("#   10240 = 10240 KB/s = 10 MiB/s (very fast)\n");
                sb.append("#\n");
                sb.append("# Speed options for quick reference:\n");
                sb.append("#   100  = 100KB/s  (slow, suitable for limited bandwidth)\n");
                sb.append("#   512  = 512KB/s  (moderate, half MiB)\n");
                sb.append("#   1024 = 1024KB/s = 1MiB/s (default, recommended)\n");
                sb.append("#   5120 = 5120KB/s = 5MiB/s (fast, suitable for LAN)\n");
                sb.append("#   10240 = 10240KB/s = 10MiB/s (very fast)\n");
                sb.append("#\n");
                sb.append("# Default: 1024 (1 MiB/s); range: 0 - 10240\n");
                sb.append("# Default: 1024 (1MiB/s), Range: 0 - 10240\n");
                sb.append("syncSpeedLimitKBps=" + syncSpeedLimitKBps + "\n");
                sb.append("# Player-centered region radius (square), current dimension only; 0 = unlimited\n");
                sb.append("syncRadiusRegions=" + syncRadiusRegions + "\n");
                sb.append("\n");
                sb.append("# ========================================\n");
                sb.append("# Multi-entry cache reuse\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# Common server identity for reusing the same map cache across multiple entry points\n");
                sb.append("# Name client save directories with this identity instead of the IP, sharing one cache across addresses\n");
                sb.append("# Leave empty to fall back to IP-based naming (legacy behavior)\n");
                sb.append("#\n");
                sb.append("# Server identity name for multi-entry cache reuse\n");
                sb.append("# Client uses this name instead of IP for save directory\n");
                sb.append("# Multiple entry points/domains/IPs share the same map cache\n");
                sb.append("# Leave empty to fall back to IP naming (backward compatible)\n");
                sb.append("serverName=" + serverName + "\n");
                sb.append("\n");

                sb.append("# ========================================\n");
                sb.append("# Incremental update settings\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# Update mode: DISABLED, TICK (periodic ticks), or SCHEDULED (daily)\n");
                sb.append("# Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)\n");
                sb.append("incrementalUpdateMode=" + incrementalUpdateMode.name() + "\n");
                sb.append("\n");
                sb.append("# TICK interval (20 ticks = 1 second; default 6000 = 5 minutes)\n");
                sb.append("# Interval in server ticks for TICK mode (20 ticks = 1 second, default 6000 = 5 minutes)\n");
                sb.append("# Range: 2400 - 72000\n");
                sb.append("incrementalUpdateIntervalTicks=" + incrementalUpdateIntervalTicks + "\n");
                sb.append("\n");
                sb.append("# SCHEDULED update hour (0-23, server local time zone)\n");
                sb.append("# Hour of day for SCHEDULED mode (0-23, uses server's local timezone)\n");
                sb.append("scheduledUpdateHour=" + scheduledUpdateHour + "\n");
                sb.append("\n");
                sb.append("# SCHEDULED update minute (0-59)\n");
                sb.append("# Minute of hour for SCHEDULED mode (0-59)\n");
                sb.append("scheduledUpdateMinute=" + scheduledUpdateMinute + "\n");
                sb.append("\n");

                sb.append("# ========================================\n");
                sb.append("# Dimension scan settings\n");
                sb.append("# ========================================\n");
                sb.append("#\n");
                sb.append("# Default layer plan for dimensions not in dimension_configs (SURFACE = surface only; CAVE = one cave layer)\n");
                sb.append("# Default layer plan fallback for dimensions not in dimension_configs\n");
                sb.append("# SURFACE = surface only; CAVE = single cave layer at defaultCaveStart\n");
                sb.append("defaultScanMode=" + defaultScanMode.name() + "\n");
                sb.append("\n");
                sb.append("# caveStart Y when defaultScanMode=CAVE (the caves(Y) layer plan)\n");
                sb.append("# Cave start Y when defaultScanMode=CAVE (maps to layerPlan caves(Y))\n");
                sb.append("# Range: -512 - 512\n");
                sb.append("defaultCaveStart=" + defaultCaveStart + "\n");
                sb.append("\n");
                DimensionConfigParser.appendEntriesToPropertiesFile(sb, dimensionConfigs);

                Files.writeString(configFile, sb.toString());
                DimensionConfigParser.invalidateCache();

                LOGGER.info("Saved config to: {}", configFile);
            } catch (Exception e) {
                LOGGER.error("Failed to save config: {}", e.getMessage());
            }
        }

        public boolean getEnableDebugLogging() {
            return enableDebugLogging;
        }

        public int getMaxConcurrentRegions() {
            return maxConcurrentRegions;
        }

        public int getMaxSyncPacketSize() {
            return maxSyncPacketSize;
        }

        public int getSyncRadiusRegions() { return syncRadiusRegions; }

        public void setSyncRadiusRegions(int value) { syncRadiusRegions = Math.max(0, value); }

        public int getSyncSpeedLimitKBps() {
            return syncSpeedLimitKBps;
        }

        public String getServerName() {
            return serverName;
        }

        public UpdateMode getIncrementalUpdateMode() {
            return incrementalUpdateMode;
        }

        public int getIncrementalUpdateIntervalTicks() {
            return incrementalUpdateIntervalTicks;
        }

        public int getScheduledUpdateHour() {
            return scheduledUpdateHour;
        }

        public int getScheduledUpdateMinute() {
            return scheduledUpdateMinute;
        }

        public ScanMode getDefaultScanMode() {
            return defaultScanMode;
        }

        public int getDefaultCaveStart() {
            return defaultCaveStart;
        }

        public List<String> getDimensionConfigs() {
            return new ArrayList<>(dimensionConfigs);
        }

        public void setEnableDebugLogging(boolean value) {
            enableDebugLogging = value;
        }

        public void setMaxConcurrentRegions(int value) {
            maxConcurrentRegions = ConcurrentRegionsConfig.clampConfigured(value);
        }

        public void setMaxSyncPacketSize(int value) {
            maxSyncPacketSize = Math.max(65536, Math.min(1048576, value));
        }

        public void setSyncSpeedLimitKBps(int value) {
            syncSpeedLimitKBps = Math.max(0, Math.min(10240, value));
        }

        public void setIncrementalUpdateMode(UpdateMode value) {
            incrementalUpdateMode = value;
        }

        public void setIncrementalUpdateIntervalTicks(int value) {
            incrementalUpdateIntervalTicks = Math.max(2400, Math.min(72000, value));
        }

        public void setScheduledUpdateHour(int value) {
            scheduledUpdateHour = Math.max(0, Math.min(23, value));
        }

        public void setScheduledUpdateMinute(int value) {
            scheduledUpdateMinute = Math.max(0, Math.min(59, value));
        }

        public void setDefaultScanMode(ScanMode value) {
            defaultScanMode = value;
        }

        public void setDefaultCaveStart(int value) {
            defaultCaveStart = Math.max(-512, Math.min(512, value));
        }

        public void setDimensionConfigs(List<String> value) {
            dimensionConfigs = new ArrayList<>(value);
            DimensionConfigParser.invalidateCache();
        }

        public List<DimensionScanConfig> parseDimensionConfigs() {
            return DimensionConfigParser.parseDimensionConfigs(dimensionConfigs);
        }

        private DimensionScanConfig parseConfigString(String configStr) {
            return DimensionConfigParser.parseConfigString(configStr);
        }

        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            return DimensionConfigParser.getConfigForDimension(
                dimensionPath, dimensionConfigs, defaultScanMode, defaultCaveStart);
        }
    }
}
