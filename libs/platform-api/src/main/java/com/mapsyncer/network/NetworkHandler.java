package com.mapsyncer.network;

import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;

import java.util.function.BiConsumer;

public interface NetworkHandler<PLAYER_TYPE, EVENT_TYPE> {

    String SYNC_REQUEST_ID = "sync_request";

    String SYNC_RESPONSE_ID = "sync_response";

    String SYNC_PROGRESS_ID = "sync_progress";

    String SERVER_INSTALLED_ID = "server_installed";

    void registerHandlers(EVENT_TYPE event);

    void sendToServer(SyncRequestPayload payload);

    void sendToPlayer(PLAYER_TYPE player, SyncResponsePayload payload);

    void sendToPlayer(PLAYER_TYPE player, SyncProgressPayload payload);

    void sendToPlayer(PLAYER_TYPE player, ServerInstalledPayload payload);

    void registerSyncResponseHandler(BiConsumer<SyncResponsePayload, PayloadContext> handler);

    void registerSyncProgressHandler(BiConsumer<SyncProgressPayload, PayloadContext> handler);

    void registerServerInstalledHandler(BiConsumer<ServerInstalledPayload, PayloadContext> handler);

    void registerSyncRequestHandler(BiConsumer<SyncRequestPayload, PayloadContext> handler);

    void enqueueWork(PayloadContext context, Runnable work);

    PLAYER_TYPE getPlayerFromContext(PayloadContext context);
}
