package com.mapsyncer.client;

import com.mapsyncer.platform.XaeroReflectionHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClientLifecycleBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientLifecycleBridge.class);

    private ClientLifecycleBridge() {}

    public static void onClientDisconnect() {
        IntegratedHostMapUpdates.reset();
        AutoSyncManager.cancel();
        MapPacketHandler.resetServerStatus();
        MapPacketHandler.clearSyncData();
        XaeroReflectionHelper.clearCache();
        XaeroMapDataHandler.clearRegionTracking();
        ClientHashManager.shutdown();
        ClientSyncWriteQueue.shutdown();
        RegionPipelineTracker.endSession();
        ClientTimestampCache.resetInstance();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }
}
