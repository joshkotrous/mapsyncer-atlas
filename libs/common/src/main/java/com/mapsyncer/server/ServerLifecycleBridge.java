package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager;
import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.XaeroMapDataHandler;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.BlockColorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerLifecycleBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerLifecycleBridge.class);

    private ServerLifecycleBridge() {}

    public static void onServerStopped() {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        ConversionOrchestrator.shutdownExecutor();

        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        MapPacketHandler.clearReceivedChunks();
        XaeroMapDataHandler.clearRegionTracking();
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();
        PlatformManager.getPlatform().clearBlockPropertiesCache();
        ClientHashManager.shutdown();

        ServerSyncHandlerLogic.cleanup();
        ServerMapCache.configure(java.nio.file.Path.of("server_map_cache"), null);
        DimensionRegistry.resetRegistration();

        LOGGER.info("Singleton cache cleanup completed");
    }
}
