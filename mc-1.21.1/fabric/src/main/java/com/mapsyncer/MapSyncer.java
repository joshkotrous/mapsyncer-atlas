package com.mapsyncer;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.FabricNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.FabricPlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class MapSyncer implements ModInitializer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    private static FabricNetworkHandler networkHandler;

    private static FabricPlatform platform;

    @Override
    public void onInitialize() {

        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MOD_ID);
            if (container.isPresent()) {
                VERSION = container.get().getMetadata().getVersion().getFriendlyString();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to get mod version from Fabric Loader", e);
        }

        platform = new FabricPlatform();
        PlatformManager.initialize(platform);
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        networkHandler = new FabricNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        registerServerEvents();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CacheGenerateCommand.register(dispatcher, "mapsyncerserver");
        });

        networkHandler.registerHandlers(null);
        ServerSyncHandler.register(null);

        LOGGER.info("MapSyncer initialized (Fabric 1.21), version: {}", VERSION);
    }

    private void registerServerEvents() {

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("Server starting, initializing MapSyncer...");

            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
            ModConfig.getServerConfig(configDir);

            platform.setServer(server);

            ConversionOrchestrator.tryInitIntegratedServerCache(server, FabricLoader.getInstance().getGameDir());

            DimensionRegistry.registerAllDimensions(server);

            Platform platformImpl = PlatformManager.getPlatform();
            UpdateMode mode = platformImpl.getIncrementalUpdateMode();
            if (mode != UpdateMode.DISABLED) {
                IncrementalUpdateHandlerLogic.getInstance().start(server);
                LOGGER.info("Incremental update handler started with mode: {}", mode);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, cleaning up MapSyncer...");

            IncrementalUpdateHandlerLogic.getInstance().stop();
            com.mapsyncer.server.ConversionOrchestrator.shutdownExecutor();
            com.mapsyncer.server.PlayerJoinHandler.onServerStopped();

            platform.setServer(null);

            LOGGER.info("MapSyncer cleanup completed");
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            com.mapsyncer.server.PlayerJoinHandler.onPlayerJoin(handler.player, server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            com.mapsyncer.server.PlayerJoinHandler.onPlayerLeave(handler.player.getUUID());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            IncrementalUpdateHandlerLogic.getInstance().onServerTick();
            com.mapsyncer.server.PlayerJoinHandler.onServerTick(server);
        });
    }

    public static FabricNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    public static FabricPlatform getPlatform() {
        return platform;
    }
}
