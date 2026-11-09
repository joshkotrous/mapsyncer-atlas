package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

public record SyncProgressPayload(int processed, int total, String status) {
    public static final String ID = NetworkHandler.SYNC_PROGRESS_ID;
}
