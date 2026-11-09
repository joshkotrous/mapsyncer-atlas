package com.mapsyncer.network.impl;

import com.mapsyncer.network.FabricPayloadAdapters;
import com.mapsyncer.network.NetworkHandler;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class FabricNetworkHandler implements NetworkHandler<ServerPlayer, Object> {

    private BiConsumer<SyncResponsePayload, PayloadContext> syncResponseHandler;
    private BiConsumer<SyncProgressPayload, PayloadContext> syncProgressHandler;
    private BiConsumer<ServerInstalledPayload, PayloadContext> serverInstalledHandler;
    private BiConsumer<SyncRequestPayload, PayloadContext> syncRequestHandler;

    private final AtomicBoolean payloadTypesRegistered = new AtomicBoolean(false);

    private void registerPayloadTypes() {
        if (!payloadTypesRegistered.compareAndSet(false, true)) {
            return;
        }

        PayloadTypeRegistry.playC2S().register(
                FabricPayloadAdapters.SYNC_REQUEST_TYPE,
                FabricPayloadAdapters.SYNC_REQUEST_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.SYNC_RESPONSE_TYPE,
                FabricPayloadAdapters.SYNC_RESPONSE_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.SYNC_PROGRESS_TYPE,
                FabricPayloadAdapters.SYNC_PROGRESS_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                FabricPayloadAdapters.SERVER_INSTALLED_TYPE,
                FabricPayloadAdapters.SERVER_INSTALLED_CODEC
        );
    }

    @Override
    public void registerHandlers(Object event) {

        registerPayloadTypes();

        ServerPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SYNC_REQUEST_TYPE, (wrapper, context) -> {
            if (syncRequestHandler != null) {
                syncRequestHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });
    }

    public void registerClientHandlers() {

        registerPayloadTypes();

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SYNC_RESPONSE_TYPE, (wrapper, context) -> {
            if (syncResponseHandler != null) {
                syncResponseHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SYNC_PROGRESS_TYPE, (wrapper, context) -> {
            if (syncProgressHandler != null) {
                syncProgressHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(FabricPayloadAdapters.SERVER_INSTALLED_TYPE, (wrapper, context) -> {
            if (serverInstalledHandler != null) {
                serverInstalledHandler.accept(wrapper.payload(), new PayloadContext(context));
            }
        });
    }

    @Override
    public void sendToServer(SyncRequestPayload payload) {
        ClientPlayNetworking.send(new FabricPayloadAdapters.SyncRequestWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncResponsePayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.SyncResponseWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, SyncProgressPayload payload) {
        ServerPlayNetworking.send(player, new FabricPayloadAdapters.SyncProgressWrapper(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ServerInstalledPayload payload) {
        // The installation notice is optional; only send on a negotiated channel.
        try {
            if (!ServerPlayNetworking.canSend(player, FabricPayloadAdapters.SERVER_INSTALLED_TYPE)) return;
            ServerPlayNetworking.send(player, new FabricPayloadAdapters.ServerInstalledWrapper(payload));
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
        if (platformCtx instanceof ServerPlayNetworking.Context serverCtx) {
            serverCtx.server().execute(work);
        } else if (platformCtx instanceof ClientPlayNetworking.Context clientCtx) {
            clientCtx.client().execute(work);
        }
    }

    @Override
    public ServerPlayer getPlayerFromContext(PayloadContext context) {
        Object platformCtx = context.getPlatformContext();
        if (platformCtx instanceof ServerPlayNetworking.Context serverCtx) {
            return serverCtx.player();
        }
        return null;
    }
}
