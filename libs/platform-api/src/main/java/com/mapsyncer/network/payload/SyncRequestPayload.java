package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncRequestPayload {
    public static final String ID = NetworkHandler.SYNC_REQUEST_ID;

    public static final int MAX_PAYLOAD_BYTES = 28_000;

    private final Map<String, ClientMeta> clientMeta;
    private final int partIndex;
    private final int totalParts;
    private final boolean syncAll;
    private final String targetDimension;
    public static final int MAX_PARTS = 512;
    public static final int MAX_META_ENTRIES = 50_000;
    public static final int MAX_ENTRIES_PER_PART = 2048;
    public static final int MAX_META_STRING_LENGTH = 512;
    public static final int MAX_ASSEMBLED_CHARS = 8_000_000;
    private final boolean silent;
    private final long requestId;

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta) {
        this(clientMeta, 0, 0, false, "", false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts) {
        this(clientMeta, partIndex, totalParts, false, "", false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension) {
        this(clientMeta, 0, 0, syncAll, targetDimension, false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension, boolean silent) {
        this(clientMeta, 0, 0, syncAll, targetDimension, silent);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts,
            boolean syncAll, String targetDimension) {
        this(clientMeta, partIndex, totalParts, syncAll, targetDimension, false);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts,
            boolean syncAll, String targetDimension, boolean silent) {
        this(clientMeta, partIndex, totalParts, syncAll, targetDimension, silent, 0);
    }

    public SyncRequestPayload(Map<String, ClientMeta> clientMeta, int partIndex, int totalParts,
            boolean syncAll, String targetDimension, boolean silent, long requestId) {
        this.requestId = requestId;
        this.clientMeta = clientMeta;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
        this.syncAll = syncAll;
        this.targetDimension = targetDimension != null ? targetDimension : "";
        this.silent = silent;
    }

    public Map<String, ClientMeta> clientMeta() { return clientMeta; }
    public int partIndex() { return partIndex; }
    public int totalParts() { return totalParts; }
    public boolean syncAll() { return syncAll; }
    public String targetDimension() { return targetDimension; }
    public boolean silent() { return silent; }
    public long requestId() { return requestId; }

    public boolean hasValidPartBounds() {
        return totalParts >= 0 && totalParts <= MAX_PARTS && partIndex >= 0
                && (totalParts <= 1 ? partIndex == 0 : partIndex < totalParts)
                && clientMeta.size() <= MAX_ENTRIES_PER_PART;
    }

    private static final int ESTIMATED_ENTRY_BYTES = 100;

    public static SyncRequestPayload[] split(Map<String, ClientMeta> metaMap, boolean syncAll, String targetDimension) {
        return split(metaMap, syncAll, targetDimension, false);
    }

    public static SyncRequestPayload[] split(Map<String, ClientMeta> metaMap, boolean syncAll, String targetDimension, boolean silent) {
        long requestId = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        // Metadata is an optimization: an oversized cache can safely use an empty
        // request, letting the server send its authoritative eligible regions.
        if (metaMap.size() > MAX_META_ENTRIES) {
            return new SyncRequestPayload[] { new SyncRequestPayload(Map.of(), 0, 0, syncAll, targetDimension, silent, requestId) };
        }
        int largestEntry = ESTIMATED_ENTRY_BYTES;
        long totalChars = 0;
        for (var entry : metaMap.entrySet()) {
            totalChars += entry.getKey().length() + entry.getValue().hash().length();
            if (totalChars > MAX_ASSEMBLED_CHARS || entry.getKey().length() > MAX_META_STRING_LENGTH
                    || entry.getValue().hash().length() > 64) {
                return new SyncRequestPayload[] { new SyncRequestPayload(Map.of(), 0, 0, syncAll, targetDimension, silent, requestId) };
            }
            largestEntry = Math.max(largestEntry, entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + entry.getValue().hash().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 18);
        }
        int headerBytes = 64 + (targetDimension == null ? 0
                : targetDimension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        int maxEntriesPerPart = Math.max(1, (MAX_PAYLOAD_BYTES - headerBytes) / largestEntry);
        if (metaMap.size() <= maxEntriesPerPart) {
            return new SyncRequestPayload[] { new SyncRequestPayload(metaMap, 0, 0, syncAll, targetDimension, silent, requestId) };
        }

        List<Map.Entry<String, ClientMeta>> entries = new ArrayList<>(metaMap.entrySet());
        int totalParts = (entries.size() + maxEntriesPerPart - 1) / maxEntriesPerPart;
        if (totalParts > MAX_PARTS) {
            return new SyncRequestPayload[] { new SyncRequestPayload(Map.of(), 0, 0, syncAll, targetDimension, silent, requestId) };
        }
        SyncRequestPayload[] parts = new SyncRequestPayload[totalParts];

        for (int i = 0; i < totalParts; i++) {
            int start = i * maxEntriesPerPart;
            int end = Math.min(start + maxEntriesPerPart, entries.size());
            Map<String, ClientMeta> partMap = new HashMap<>();
            for (int j = start; j < end; j++) {
                partMap.put(entries.get(j).getKey(), entries.get(j).getValue());
            }
            parts[i] = new SyncRequestPayload(partMap, i, totalParts, syncAll, targetDimension, silent, requestId);
        }
        return parts;
    }
}
