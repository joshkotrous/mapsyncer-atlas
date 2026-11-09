package com.mapsyncer;

import com.mapsyncer.client.MapPacketHandler;
import com.mapsyncer.client.MapPacketReceiver;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.impl.ForgeNetworkHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.platform.impl.ForgeLegacyPlatform;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.ConversionOrchestrator;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import com.mapsyncer.server.ServerSyncHandlerLogic;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MapSyncer.MOD_ID)
public class MapSyncer {

    public static final String MOD_ID = "mapsyncer";
    public static String VERSION = "unknown";
    public static final Logger LOGGER = LoggerFactory.getLogger(MapSyncer.class);

    public MapSyncer(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        ModContainer modContainer = ModLoadingContext.get().getActiveContainer();
        VERSION = modContainer.getModInfo().getVersion().toString();

        PlatformManager.initialize(new ForgeLegacyPlatform());
        LOGGER.info("Platform initialized: {}", PlatformManager.getPlatform().getPlatformName());

        DimensionPathMapping.getInstance().initialize(20);
        LOGGER.info("DimensionPathMapping initialized for version 1.20.X");

        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SERVER_SPEC);
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
        modBus.addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Loading event) ->
                ModConfig.bindServerConfig(event.getConfig()));

        ForgeNetworkHandler networkHandler = new ForgeNetworkHandler();
        NetworkManager.initialize(networkHandler);
        LOGGER.info("NetworkManager initialized for Forge 1.20.1");

        networkHandler.registerHandlers(null);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MapPacketReceiver.register(null);
            MinecraftForge.EVENT_BUS.register(ClientEventHandler.class);
            LOGGER.info("MapSyncer initialized (client mode, Forge 1.20.1)");
        }

        ServerSyncHandlerLogic.registerHandlers();
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("MapSyncer server handlers registered (integrated server support)");
    }

    @EventBusSubscriber(value = Dist.CLIENT, bus = EventBusSubscriber.Bus.FORGE)
    public static class ClientEventHandler {
        @SubscribeEvent
        public static void onPlayerLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
            MapPacketReceiver.onDisconnect();
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                MapPacketHandler.drainPendingLoadQueue();
            }
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {

        ConversionOrchestrator.tryInitIntegratedServerCache(event.getServer(), FMLPaths.GAMEDIR.get());

        DimensionRegistry.registerAllDimensions(event.getServer());

        com.mapsyncer.util.ModLogConfig.applyDebugLogging();

        Platform platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(event.getServer());
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        MinecraftForge.EVENT_BUS.unregister(this);
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CacheGenerateCommand.register(event.getDispatcher(), "mapsyncer");
    }
}
