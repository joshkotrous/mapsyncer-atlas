package com.mapsyncer.server;

import com.mapsyncer.server.IncrementalUpdateHandlerLogic;
import net.minecraft.server.MinecraftServer;

public class IncrementalUpdateHandler {

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
