package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.platform.UpdateMode;

public record ServerInstalledPayload(
        String version,
        long lastGenerationTimestamp,
        int autoSyncIntervalMinutes,
        UpdateMode updateMode,
        int incrementalUpdateIntervalTicks,
        String serverName) {

    public static final String ID = NetworkHandler.SERVER_INSTALLED_ID;

    public ServerInstalledPayload(String version, long lastGenerationTimestamp, int autoSyncIntervalMinutes) {
        this(version, lastGenerationTimestamp, autoSyncIntervalMinutes, UpdateMode.DISABLED, 0, "");
    }

    public ServerInstalledPayload(String version, long lastGenerationTimestamp, int autoSyncIntervalMinutes,
                                   UpdateMode updateMode, int incrementalUpdateIntervalTicks) {
        this(version, lastGenerationTimestamp, autoSyncIntervalMinutes, updateMode, incrementalUpdateIntervalTicks, "");
    }
}
