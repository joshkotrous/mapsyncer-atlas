package com.mapsyncer.network.impl;

import com.mapsyncer.network.FabricPayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

public class FabricNetworkHandler implements NetworkHandler<ServerPlayer, Object> {

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricNetworkHandler.class);

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    private record ServerPlayerContext(net.minecraft.server.MinecraftServer server, ServerPlayer player) {}

    @Override
    public void registerHandlers(Object event) {
        ServerPlayNetworking.registerGlobalReceiver(
                FabricPayloadAdapters.SYNC_REQUEST_ID,
                (server, player, handler, buf, responseSender) -> {
                    if (syncRequestHandler != null) {
                        SyncRequestPayload payload = FabricPayloadAdapters.readSyncRequest(buf);
                        syncRequestHandler.accept(payload, new PayloadContext(new ServerPlayerContext(server, player)));
                    } else {
                        LOGGER.warn("Sync request from {} ignored: handler not registered", player.getName().getString());
                    }
                }
        );
    }

    public void registerClientHandlers() {
        FabricClientNetworkHandler.init(this);
    }

    public BiConsumer<SyncResponsePayload, PayloadContext> getSyncResponseHandler() {
        return syncResponseHandler;
    }

    public BiConsumer<SyncProgressPayload, PayloadContext> getSyncProgressHandler() {
        return syncProgressHandler;
    }

    public BiConsumer<ServerInstalledPayload, PayloadContext> getServerInstalledHandler() {
        return serverInstalledHandler;
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeSyncRequest(buf, payload);
        FabricClientNetworkHandler.sendToServer(FabricPayloadAdapters.SYNC_REQUEST_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeSyncResponse(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.SYNC_RESPONSE_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        FabricPayloadAdapters.writeSyncProgress(buf, payload);
        ServerPlayNetworking.send(player, FabricPayloadAdapters.SYNC_PROGRESS_ID, buf);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        // The installation notice is optional; only send on a negotiated channel.
        try {
            if (!ServerPlayNetworking.canSend(player, FabricPayloadAdapters.SERVER_INSTALLED_ID)) return;
            FriendlyByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
            FabricPayloadAdapters.writeServerInstalled(buf, payload);
            ServerPlayNetworking.send(player, FabricPayloadAdapters.SERVER_INSTALLED_ID, buf);
        } catch (RuntimeException e) {
            com.mapsyncer.MapSyncer.LOGGER.warn("Could not send server_installed to {}", player.getUUID(), e);
        }
    }

    @Override
    public void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler) {
        this.syncResponseHandler = handler;
    }

    @Override
    public void registerSyncProgressHandler(BiConsumer<SyncProgressPayload, PayloadContext> handler) {
        this.syncProgressHandler = handler;
    }

    @Override
    public void registerServerInstalledHandler(BiConsumer<ServerInstalledPayload, PayloadContext> handler) {
        this.serverInstalledHandler = handler;
    }

    @Override
    public void registerSyncRequestHandler(BiConsumer<SyncRequestPayload, PayloadContext> handler) {
        this.syncRequestHandler = handler;
    }

    @Override
    public void enqueueWork(PayloadContext context, Runnable work) {
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayerContext spc) {
            spc.server().execute(work);
        } else {
            FabricClientNetworkHandler.enqueueClientWork(work);
        }
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayerContext spc) {
            return spc.player();
        }
        return null;
    }
}
