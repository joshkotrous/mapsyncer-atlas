package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.List;

public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId, String status) {
    public static final String ID = NetworkHandler.SYNC_RESPONSE_ID;
}
