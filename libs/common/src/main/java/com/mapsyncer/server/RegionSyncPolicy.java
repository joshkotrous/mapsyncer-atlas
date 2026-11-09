package com.mapsyncer.server;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.HashUtils;

public final class RegionSyncPolicy {

    private RegionSyncPolicy() {}

    public static boolean shouldTransfer(String serverHash, long serverTs, ClientMeta clientMeta) {
        if (clientMeta == null) {
            return true;
        }
        String clientHash = clientMeta.hash();
        if (!HashUtils.isValidHash(clientHash)) {
            return true;
        }
        if (serverHash.equals(clientHash)) {
            return false;
        }
        if (clientMeta.timestampSeconds() >= serverTs) {
            return false;
        }
        return true;
    }
}
