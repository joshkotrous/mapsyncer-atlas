package com.mapsyncer.server;

import com.mapsyncer.network.NetworkManager;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public class ServerSyncHandler {

    public static void register(final RegisterPayloadHandlersEvent event) {
        NetworkManager.registerHandlers(event);
        ServerSyncHandlerLogic.registerHandlers();
    }
}
