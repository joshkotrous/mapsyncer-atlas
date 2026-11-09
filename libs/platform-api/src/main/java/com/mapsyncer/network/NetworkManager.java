package com.mapsyncer.network;

import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

public final class NetworkManager {

    private static volatile NetworkHandler<?, ?> instance;

    private NetworkManager() {}

    public static void initialize(NetworkHandler<?, ?> handler) {
        if (instance != null) {
            throw new IllegalStateException("NetworkHandler already initialized");
        }
        instance = handler;
    }

    public static NetworkHandler<?, ?> getHandler() {
        if (instance == null) {
            throw new IllegalStateException("NetworkHandler not initialized");
        }
        return instance;
    }

    public static boolean isInitialized() {
        return instance != null;
    }

    public static void sendToServer(SyncRequestPayload payload) {
        getHandler().sendToServer(payload);
    }

    public static void sendToPlayer(Object player, SyncResponsePayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    public static void sendToPlayer(Object player, SyncProgressPayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    public static void sendToPlayer(Object player, ServerInstalledPayload payload) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.sendToPlayer(player, payload);
    }

    public static void registerHandlers(Object event) {
        @SuppressWarnings("unchecked")
        NetworkHandler<Object, Object> handler = (NetworkHandler<Object, Object>) getHandler();
        handler.registerHandlers(event);
    }
}
