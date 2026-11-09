package com.mapsyncer.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@EventBusSubscriber(value = Dist.CLIENT, modid = "mapsyncer")
public class ClientJoinHandler {

    @SubscribeEvent
    public static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        SyncResumeHelper.onPlayerLoggingIn();
    }

    public static void clearSyncState() {
        SyncResumeHelper.clearSyncState();
    }
}
