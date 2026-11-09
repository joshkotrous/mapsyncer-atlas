package com.mapsyncer.network.impl;

import com.mapsyncer.network.FabricPayloadAdapters;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class FabricClientNetworkHandler {

    private static FabricNetworkHandler networkHandler;

    public static void init(FabricNetworkHandler handler) {
        networkHandler = handler;
        registerReceivers();
    }

    private static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SYNC_RESPONSE_ID,
                (client, handler, buf, responseSender) -> {
                    if (networkHandler != null) {
                        var h = networkHandler.getSyncResponseHandler();
                        if (h != null) {
                            SyncResponsePayload payload = FabricPayloadAdapters.readSyncResponse(buf);
                            h.accept(payload, new PayloadContext(client));
                        }
                    }
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SYNC_PROGRESS_ID,
                (client, handler, buf, responseSender) -> {
                    if (networkHandler != null) {
                        var h = networkHandler.getSyncProgressHandler();
                        if (h != null) {
                            SyncProgressPayload payload = FabricPayloadAdapters.readSyncProgress(buf);
                            h.accept(payload, new PayloadContext(client));
                        }
                    }
                }
        );

        ClientPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SERVER_INSTALLED_ID,
                (client, handler, buf, responseSender) -> {
                    if (networkHandler != null) {
                        var h = networkHandler.getServerInstalledHandler();
                        if (h != null) {
                            ServerInstalledPayload payload = FabricPayloadAdapters.readServerInstalled(buf);
                            h.accept(payload, new PayloadContext(client));
                        }
                    }
                }
        );
    }

    public static void sendToServer(ResourceLocation channelId, FriendlyByteBuf buf) {
        ClientPlayNetworking.send(channelId, buf);
    }

    public static void enqueueClientWork(Runnable work) {
        net.minecraft.client.Minecraft.getInstance().execute(work);
    }
}
