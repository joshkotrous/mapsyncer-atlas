package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;

public class MapPacketReceiver {

    public static void register(final Object event) {
        NetworkManager.registerHandlers(event);
        MapPacketHandler.registerHandlers();
    }

    public static boolean isSyncInProgress() { return MapPacketHandler.isSyncInProgress(); }
    public static boolean isServerInstalled() { return MapPacketHandler.isServerInstalled(); }
    public static void resetServerStatus() { MapPacketHandler.resetServerStatus(); }
    public static void clearSyncData() { MapPacketHandler.clearSyncData(); }
    public static void clearReceivedChunks() { MapPacketHandler.clearReceivedChunks(); }
    public static boolean isSyncStale() { return MapPacketHandler.isSyncStale(); }
    public static void prepareSyncForDimension(String targetDimension) { MapPacketHandler.prepareSyncForDimension(targetDimension); }
    public static void onDisconnect() { MapPacketHandler.onDisconnect(); }
}
