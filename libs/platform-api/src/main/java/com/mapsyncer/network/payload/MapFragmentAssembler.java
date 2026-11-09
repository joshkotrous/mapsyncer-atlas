package com.mapsyncer.network.payload;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded reassembly of untrusted map fragments, independent of Minecraft APIs. */
public final class MapFragmentAssembler {
    public static final int MAX_PARTS = 1024;
    public static final int MAX_REGION_BYTES = 16 * 1024 * 1024;
    public static final int MAX_PENDING_REGIONS = 64;
    public static final int MAX_PENDING_BYTES = 32 * 1024 * 1024;
    private static final long TIMEOUT_MS = 2 * 60 * 1000;

    private record Key(int x, int z, String dimension, int layer) {}
    private static final class Entry {
        final ChunkMapData reference;
        final ChunkMapData[] parts;
        final long created;
        int bytes;
        int received;

        Entry(ChunkMapData chunk, long now) {
            reference = chunk;
            parts = new ChunkMapData[chunk.totalParts];
            created = now;
        }
    }

    private final Map<Key, Entry> pending = new HashMap<>();
    private final int maxParts, maxRegionBytes, maxRegions, maxPendingBytes;
    private final LongSupplier clock;
    private long pendingBytes;

    public MapFragmentAssembler() {
        this(MAX_PARTS, MAX_REGION_BYTES, MAX_PENDING_REGIONS, MAX_PENDING_BYTES,
                System::currentTimeMillis);
    }

    /** Explicit limits and clock allow deterministic resource-bound regression tests. */
    public MapFragmentAssembler(int maxParts, int maxRegionBytes, int maxRegions,
                                int maxPendingBytes, LongSupplier clock) {
        if (maxParts < 2 || maxParts > MAX_PARTS || maxRegionBytes < 1
                || maxRegionBytes > MAX_REGION_BYTES || maxRegions < 1
                || maxRegions > MAX_PENDING_REGIONS || maxPendingBytes < maxRegionBytes
                || maxPendingBytes > MAX_PENDING_BYTES || clock == null) {
            throw new IllegalArgumentException("Invalid map fragment resource limits");
        }
        this.maxParts = maxParts;
        this.maxRegionBytes = maxRegionBytes;
        this.maxRegions = maxRegions;
        this.maxPendingBytes = maxPendingBytes;
        this.clock = clock;
    }

    /** Returns a complete region or null for an incomplete or rejected packet. */
    public synchronized ChunkMapData accept(ChunkMapData chunk) {
        cleanStale();
        if (chunk == null || chunk.data == null || chunk.dimension == null
                || chunk.dimension.length() > 256 || chunk.totalParts < 0
                || chunk.totalParts > maxParts || chunk.partIndex < 0) {
            return null;
        }
        Key key = new Key(chunk.regionX, chunk.regionZ, chunk.dimension, chunk.caveLayer);
        if (chunk.totalParts <= 1) {
            // Legacy unsplit packets use totalParts=0 and index=0.
            if (chunk.partIndex != 0 || chunk.data.length > maxRegionBytes) return null;
            remove(key);
            return chunk;
        }
        if (chunk.partIndex >= chunk.totalParts || chunk.data.length > ChunkMapData.MAX_PAYLOAD_BYTES) {
            remove(key);
            return null;
        }
        Entry entry = pending.get(key);
        if (entry != null && (entry.parts.length != chunk.totalParts
                || entry.reference.timestampSeconds != chunk.timestampSeconds)) {
            remove(key);
            return null;
        }
        if (entry == null) {
            if (pending.size() >= maxRegions) return null;
            entry = new Entry(chunk, clock.getAsLong());
            pending.put(key, entry);
        }
        ChunkMapData duplicate = entry.parts[chunk.partIndex];
        if (duplicate != null) {
            if (!Arrays.equals(duplicate.data, chunk.data)) remove(key);
            return null;
        }
        // Use long arithmetic before addition or allocation to avoid overflow.
        if ((long) entry.bytes + chunk.data.length > maxRegionBytes
                || pendingBytes + chunk.data.length > maxPendingBytes) {
            remove(key);
            return null;
        }
        entry.parts[chunk.partIndex] = chunk;
        entry.bytes += chunk.data.length;
        entry.received++;
        pendingBytes += chunk.data.length;
        if (entry.received != entry.parts.length) return null;

        remove(key);
        byte[] assembled = new byte[entry.bytes];
        int offset = 0;
        for (ChunkMapData part : entry.parts) {
            System.arraycopy(part.data, 0, assembled, offset, part.data.length);
            offset += part.data.length;
        }
        ChunkMapData first = entry.parts[0];
        return new ChunkMapData(first.regionX, first.regionZ, first.dimension,
                assembled, first.timestampSeconds, first.caveLayer);
    }

    private void remove(Key key) {
        Entry entry = pending.remove(key);
        if (entry != null) pendingBytes -= entry.bytes;
    }

    public synchronized void cleanStale() {
        long now = clock.getAsLong();
        Iterator<Entry> entries = pending.values().iterator();
        while (entries.hasNext()) {
            Entry entry = entries.next();
            if (now - entry.created > TIMEOUT_MS || now < entry.created) {
                pendingBytes -= entry.bytes;
                entries.remove();
            }
        }
    }

    public synchronized void clear() {
        pending.clear();
        pendingBytes = 0;
    }

    public synchronized int pendingRegions() { return pending.size(); }
    public synchronized long pendingBytes() { return pendingBytes; }
}
