package com.mapsyncer.platform;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.mca.DimensionTypeInfo;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Set;

public interface Platform {

    PlatformType getType();

    String getServerCommandPrefix();

    String getMinecraftVersion();

    int getMajorVersion();

    String getPlatformName();

    boolean isClientEnvironment();

    BlockProperties getBlockProperties(String blockName);

    int getPatternColor(String blockName);

    int getDefaultMinBuildHeight();

    int getDefaultMaxBuildHeight();

    String getXaeroDimensionPath(String dimensionId);

    DimensionTypeInfo getDimensionTypeInfo(String dimensionId);

    DimensionScanConfig getConfigForDimension(String dimensionPath);

    int getSyncSpeedLimitKBps();

    int getSyncRadiusRegions();

    void setSyncRadiusRegions(int radius);

    int getMaxSyncPacketSize();

    int getMaxConcurrentRegions();

    boolean isDebugLoggingEnabled();

    int getClientHashThreads();

    int getMapRegionLoadIntervalTicks();

    boolean isClientAutoSyncEnabled();

    boolean isIntegratedHostSilentUpdateEnabled();

    void setClientAutoSyncEnabled(boolean enabled);

    UpdateMode getIncrementalUpdateMode();

    int getIncrementalUpdateIntervalTicks();

    int getScheduledUpdateHour();

    int getScheduledUpdateMinute();

    void setIncrementalUpdateMode(UpdateMode mode);

    void setIncrementalUpdateIntervalTicks(int interval);

    void setScheduledUpdateHour(int hour);

    void setScheduledUpdateMinute(int minute);

    void saveConfig();

    void reloadConfig();

    java.util.List<String> getDimensionConfigs();

    void setDimensionConfigs(java.util.List<String> configs);

    java.util.List<DimensionScanConfig> parseDimensionConfigs();

    Path getServerMapCacheDir();

    Path getClientXaeroWorldMapDir();

    String getCurrentServerDirectoryName();

    default String getServerName() {
        return "";
    }

    Logger getLogger();

    boolean matchesBlockPattern(String blockName, String pattern);

    java.util.Map<String, String> parseBlockProperties(String blockStateString);

    void clearBlockPropertiesCache();

    void recordUpdatedRegions(Set<RegionCoord> regions);

    record RegionCoord(int x, int z, int caveLayer) {
        public RegionCoord(int x, int z) {
            this(x, z, Integer.MAX_VALUE);
        }

        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }
}
