package com.mapsyncer.server;

public class ServerSyncHandler {

    public static void register(final Object event) {
        ServerSyncHandlerLogic.registerHandlers();
    }

    public static void register() {
        ServerSyncHandlerLogic.registerHandlers();
    }
}
