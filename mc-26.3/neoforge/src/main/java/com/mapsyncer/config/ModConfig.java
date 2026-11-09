package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.UpdateMode;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.ScanMode;

public class ModConfig {

    public static final ModConfigSpec CLIENT_SPEC;

    public static final ClientConfig CLIENT;

    public static final ModConfigSpec SERVER_SPEC;

    public static final ServerConfig SERVER;

    private static List<String> getDefaultDimensionConfigStrings() {
        return DimensionConfigParser.getDefaultDimensionConfigStrings();
    }

    static {
        var clientPair = new ModConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT = clientPair.getLeft();
        CLIENT_SPEC = clientPair.getRight();

        var serverPair = new ModConfigSpec.Builder().configure(ServerConfig::new);
        SERVER = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    public static void saveClientConfig() {
        CLIENT_SPEC.save();
    }

    public static void bindServerConfig(net.neoforged.fml.config.ModConfig config) {
        if (config.getType() == net.neoforged.fml.config.ModConfig.Type.SYNCED) {
            boundServerConfig = config;
        }
    }

    public static void reloadServerFromDisk() {
        net.neoforged.fml.config.IConfigSpec.ILoadedConfig loaded = boundServerConfig != null
                ? boundServerConfig.getLoadedConfig() : null;
        if (loaded != null) {
            Path path = boundServerConfig.getFullPath();
            CommentedFileConfig disk = CommentedFileConfig.of(path);
            disk.load();
            try {
                loaded.config().clear();
                loaded.config().putAll(disk);
                SERVER_SPEC.acceptConfig(loaded);
            } finally {
                disk.close();
            }
        }
        DimensionConfigParser.invalidateCache();
    }

    private static volatile net.neoforged.fml.config.ModConfig boundServerConfig;

    public static class ClientConfig {

        public final IntValue hashThreads;

        public final IntValue mapRegionLoadIntervalTicks;

        public final BooleanValue autoSyncEnabled;

        public final BooleanValue integratedHostSilentUpdates;

        public ClientConfig(ModConfigSpec.Builder builder) {
            builder.push("client");
            builder.comment("Client settings");

            int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            int maxThreads = Runtime.getRuntime().availableProcessors();

            hashThreads = builder
                    .comment("Number of hashing threads used for parallel map synchronization",
                             "Number of threads for hash computation during map sync",
                             "",
                             "Defaults to half the available processors to avoid blocking the game thread",
                             "Default uses half of available processors to avoid blocking game main thread",
                             "",
                             "Suggested thread counts:",
                             "  1-2 cores: use 1 thread",
                             "  4 cores: use 2 threads (default on most systems)",
                             "  8+ cores: use 4-8 threads for faster synchronization",
                             "Thread count recommendations:",
                             "  1-2 cores: use 1 thread",
                             "  4 cores: use 2 threads (default for most setups)",
                             "  8+ cores: use 4-8 threads for faster sync",
                             "",
                             "Default: " + defaultThreads + " (half of the " + maxThreads + " available processors)",
                             "Default: " + defaultThreads + " (half of " + maxThreads + " available processors)",
                             "Range: 1 - " + maxThreads,
                             "Range: 1 - " + maxThreads)
                    .defineInRange("hashThreads", defaultThreads, 1, maxThreads);

            mapRegionLoadIntervalTicks = builder
                    .comment("Client tick interval for passing out-of-view regions to Xaero (1 = one per tick).",
                             "Tick interval between loading each out-of-view region into Xaero (1 = every tick).",
                             "",
                             "  -1 = unlimited (drain in one pass)",
                             "  0 = load only regions in view",
                             "  1-100 = load one region every N ticks (default: 1)",
                             "  -1 = Unlimited (drain all at once)",
                             "  0  = View-distance only",
                             "  1-100 = one region every N ticks (default: 1)")
                    .defineInRange("mapRegionLoadIntervalTicks", 1, -1, 100);

            autoSyncEnabled = builder
                    .comment("Enable automatic join sync for TICK/SCHEDULED and periodic online sync for TICK",
                             "Enable join auto-sync when server uses TICK or SCHEDULED; online periodic sync when TICK",
                             "Manual /mapsyncer sync remains available",
                             "Manual /mapsyncer sync is always available")
                    .define("autoSyncEnabled", true);

            integratedHostSilentUpdates = builder
                    .comment("Integrated-server host only: update out-of-view maps silently; false disables local watching and refresh",
                             "Host only: silently refresh distant map data; false disables the watcher and refresh queue")
                    .define("integratedHostSilentUpdates", true);

            builder.pop();
        }

        public int getHashThreads() {
            return hashThreads.get();
        }

        public int getMapRegionLoadIntervalTicks() {
            return mapRegionLoadIntervalTicks.get();
        }

        public boolean isIntegratedHostSilentUpdateEnabled() { return integratedHostSilentUpdates.get(); }

        public void setIntegratedHostSilentUpdates(boolean enabled) { integratedHostSilentUpdates.set(enabled); }

        public boolean isAutoSyncEnabled() {
            return autoSyncEnabled.get();
        }

        public void setAutoSyncEnabled(boolean enabled) {
            autoSyncEnabled.set(enabled);
        }
    }

    public static class ServerConfig {

        public final BooleanValue enableDebugLogging;

        public final IntValue maxConcurrentRegions;

        public final IntValue maxSyncPacketSize;

        public final IntValue syncSpeedLimitKBps;
        public final IntValue syncRadiusRegions;

        public final EnumValue<UpdateMode> incrementalUpdateMode;

        public final IntValue incrementalUpdateIntervalTicks;

        public final IntValue scheduledUpdateHour;

        public final IntValue scheduledUpdateMinute;

        public final EnumValue<ScanMode> defaultScanMode;

        public final IntValue defaultCaveStart;

        public final ConfigValue<List<? extends String>> dimensionConfigs;

        public ServerConfig(ModConfigSpec.Builder builder) {
            builder.push("general");
            builder.comment("General settings");

            enableDebugLogging = builder
                    .comment("Enable debug logging for map generation",
                             "Enable debug logging for map generation")
                    .define("enableDebugLogging", false);
            maxConcurrentRegions = builder
                    .comment("Maximum concurrent region conversions; 0 = automatic (logical processors minus 2, minimum 1, maximum 16)",
                             "Max regions to convert concurrently; 0 = auto (logical CPUs - 2, min 1, max 16)")
                    .defineInRange("maxConcurrentRegions", 0, 0, 16);
            maxSyncPacketSize = builder
                    .comment("Maximum sync packet size in bytes",
                             "Maximum sync packet size in bytes",
                             "",
                             "Reference packet sizes (divide evenly into 1024 KB/s):",
                             "  65536 = 64 KB (conservative; 16 packets/second at 1024 KB/s)",
                             "  131072 = 128 KB (balanced; 8 packets/second at 1024 KB/s)",
                             "  262144 = 256 KB (recommended; 4 packets/second at 1024 KB/s)",
                             "  524288 = 512 KB (efficient; 2 packets/second at 1024 KB/s)",
                             "  1048576 = 1 MB (maximum; 1 packet/second at 1024 KB/s)",
                             "Default: 256 KB (recommended); range: 64 KB - 1 MB",
                             "",
                             "Size options for quick reference (all divide 1024KB/s evenly):",
                             "  65536  = 64KB  (conservative, 16 packets/s at 1024KB/s)",
                             "  131072 = 128KB (balanced, 8 packets/s at 1024KB/s)",
                             "  262144 = 256KB (recommended, 4 packets/s at 1024KB/s)",
                             "  524288 = 512KB (efficient, 2 packets/s at 1024KB/s)",
                             "  1048576 = 1MB  (maximum, 1 packet/s at 1024KB/s)",
                             "Default: 256KB (recommended), Range: 64KB - 1MB")
                    .defineInRange("maxSyncPacketSize", 262144, 65536, 1048576);
            syncRadiusRegions = builder
                    .comment("Square radius in regions around the player; 0 = unlimited. When enabled, send only the current dimension",
                             "Player-centered region radius (square); 0 = unlimited. Current dimension only when enabled.")
                    .defineInRange("syncRadiusRegions", 0, 0, Integer.MAX_VALUE);
            syncSpeedLimitKBps = builder
                    .comment("Sync transfer limit in KB/s (0 = unlimited)",
                             "Sync speed limit in KB/s (0 = unlimited)",
                             "",
                             "Reference transfer speeds:",
                             "  100 = 100 KB/s (slow; for limited bandwidth)",
                             "  512 = 512 KB/s (moderate; half a MiB/s)",
                             "  1024 = 1024 KB/s = 1 MiB/s (default; recommended)",
                             "  5120 = 5120 KB/s = 5 MiB/s (fast; suitable for a LAN)",
                             "  10240 = 10240 KB/s = 10 MiB/s (very fast)",
                             "",
                             "Speed options for quick reference:",
                             "  100  = 100KB/s  (slow, suitable for limited bandwidth)",
                             "  512  = 512KB/s  (moderate, half MiB)",
                             "  1024 = 1024KB/s = 1MiB/s (default, recommended)",
                             "  5120 = 5120KB/s = 5MiB/s (fast, suitable for LAN)",
                             "  10240 = 10240KB/s = 10MiB/s (very fast)",
                             "",
                             "Default: 1024 (1 MiB/s); range: 0 - 10240",
                             "Default: 1024 (1MiB/s), Range: 0 - 10240")
                    .defineInRange("syncSpeedLimitKBps", 1024, 0, 10240);

            builder.pop();

            builder.push("incremental_update");
            builder.comment("Incremental update settings");

            incrementalUpdateMode = builder
                    .comment("Update mode: DISABLED, TICK (periodic ticks), or SCHEDULED (daily)",
                             "Incremental update mode: DISABLED (off), TICK (periodic by ticks), SCHEDULED (daily at specific time)")
                    .defineEnum("incrementalUpdateMode", UpdateMode.DISABLED);

            incrementalUpdateIntervalTicks = builder
                    .comment("TICK interval (20 ticks = 1 second; default 6000 = 5 minutes)",
                             "Interval in server ticks for TICK mode (20 ticks = 1 second, default 6000 = 5 minutes)")
                    .defineInRange("incrementalUpdateIntervalTicks", 6000, 2400, 72000);

            scheduledUpdateHour = builder
                    .comment("SCHEDULED update hour (0-23, server local time zone)",
                             "Hour of day for SCHEDULED mode (0-23, uses server's local timezone)")
                    .defineInRange("scheduledUpdateHour", 4, 0, 23);

            scheduledUpdateMinute = builder
                    .comment("SCHEDULED update minute (0-59)",
                             "Minute of hour for SCHEDULED mode (0-59)")
                    .defineInRange("scheduledUpdateMinute", 0, 0, 59);

            builder.pop();

            builder.push("dimension_scan");
            builder.comment("Dimension scan settings");

            defaultScanMode = builder
                    .comment("Default layer plan for dimensions not in dimension_configs (SURFACE = surface only; CAVE = one cave layer)",
                             "Default layer plan fallback for dimensions not in dimension_configs",
                             "SURFACE = surface only; CAVE = single cave layer at default_cave_start")
                    .defineEnum("default_scan_mode", ScanMode.SURFACE);

            defaultCaveStart = builder
                    .comment("caveStart Y when default_scan_mode=CAVE (the caves(Y) layer plan)",
                             "Cave start Y when default_scan_mode=CAVE (maps to layerPlan caves(Y))")
                    .defineInRange("default_cave_start", 63, -512, 512);

            dimensionConfigs = builder
                    .comment("Dimension scan configuration list (one string per dimension)",
                             "Recommended: \"dimension = layerPlan\"",
                             "layerPlan: SURFACE, ALL, explicit Y (such as 63), or combinations (such as SURFACE,63)",
                             "Example: \"minecraft:the_nether = SURFACE,63\"",
                             "Legacy formats \"dimension|layerPlan\" and \"dimension|SURFACE|63|...\" remain readable",
                             "Per-dimension scan configuration list (one string per dimension)",
                             "Preferred: \"dimension = layerPlan\"",
                             "layerPlan: SURFACE, ALL, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)",
                             "Example: \"minecraft:the_nether = SURFACE,63\"",
                             "Legacy \"dimension|layerPlan\" and \"dimension|SURFACE|63|…\" still accepted")
                    .defineList("dimension_configs", getDefaultDimensionConfigStrings(),
                        obj -> obj instanceof String);

            builder.pop();
        }

        public List<DimensionScanConfig> parseDimensionConfigs() {
            return DimensionConfigParser.parseDimensionConfigs(dimensionConfigs.get());
        }

        private DimensionScanConfig parseConfigString(String configStr) {
            return DimensionConfigParser.parseConfigString(configStr);
        }

        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            return DimensionConfigParser.getConfigForDimension(
                dimensionPath, dimensionConfigs.get(), defaultScanMode.get(), defaultCaveStart.get());
        }
    }
}
