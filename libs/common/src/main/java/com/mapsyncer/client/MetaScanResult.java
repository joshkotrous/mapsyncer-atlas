package com.mapsyncer.client;

import com.mapsyncer.network.payload.ClientMeta;

import java.util.Collections;
import java.util.Map;

public record MetaScanResult(Map<String, ClientMeta> meta, boolean success, int failedFiles, String failureReason) {

    public static MetaScanResult ok(Map<String, ClientMeta> meta) {
        return new MetaScanResult(
                meta != null ? meta : Collections.emptyMap(), true, 0, null);
    }

    public static MetaScanResult failure(String reason, int failedFiles) {
        return new MetaScanResult(Collections.emptyMap(), false, failedFiles, reason);
    }

    public boolean isSuccess() {
        return success;
    }
}
