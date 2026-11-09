package com.mapsyncer.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(value = {Dist.CLIENT, Dist.DEDICATED_SERVER}, bus = EventBusSubscriber.Bus.GAME)
public class IncrementalUpdateHandler {

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        IncrementalUpdateHandlerLogic.getInstance().onServerTick();
    }

    public static void start(MinecraftServer server) {
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static void stop() {
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    public static boolean isRunning() {
        return IncrementalUpdateHandlerLogic.getInstance().isRunning();
    }

    public static int getTickCounter() {
        return IncrementalUpdateHandlerLogic.getInstance().getTickCounter();
    }

    public static String getStatusInfo() {
        return IncrementalUpdateHandlerLogic.getInstance().getStatusInfo();
    }

    public static void resetInstance() {
        IncrementalUpdateHandlerLogic.resetInstance();
    }
}
