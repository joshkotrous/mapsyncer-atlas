package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.NeoForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.NeoForgePlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandler;
import com.mapsyncer.util.DimensionPathMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    private static NeoForgeNetworkHandler networkHandler;

    public MapSyncer(IEventBus modBus, ModContainer modContainer) {
        VERSION = modContainer.getModInfo().getVersion().toString();

        PlatformManager.initialize(new NeoForgePlatform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        DimensionPathMapping.getInstance().initialize(21);
        LOGGER.info("DimensionPathMapping initialized for version 1.21.X");

        networkHandler = new NeoForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized");

        modContainer.registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        modContainer.registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading event) ->
                ModConfig.bindServerConfig(event.getConfig()));

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modBus.addListener(MapPacketReceiver::register);
            NeoForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode)");
        }

        modBus.addListener(ServerSyncHandler::register);
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("MapSyncer server handlers registered (integrated server support)");
    }

    public static NeoForgeNetworkHandler getNetworkHandler() {
        return networkHandler;
    }

    @EventBusSubscriber(value = Dist.CLIENT)
    public static class ClientEventHandler {

        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketReceiver.onDisconnect();
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            MapPacketHandler.drainPendingLoadQueue();
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ConversionOrchestrator.tryInitIntegratedServerCache(event.getServer(), FMLPaths.GAMEDIR.get());

        DimensionRegistry.registerAllDimensions(event.getServer());

        Platform platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        NeoForge.EVENT_BUS.unregister(this);
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CacheGenerateCommand.register(event.getDispatcher(), "mapsyncer");
    }
}
