package com.mapsyncer.network.payload;

public class ChunkMapData {

    public static final int MAX_PAYLOAD_BYTES = 28_000;

    public final int regionX;

    public final int regionZ;

    public final String dimension;

    public final byte[] data;

    public final long timestampSeconds;

    public final int caveLayer;

    public final int partIndex;

    public final int totalParts;

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this(regionX, regionZ, dimension, data, 0, Integer.MAX_VALUE);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data, long timestampSeconds) {
        this(regionX, regionZ, dimension, data, timestampSeconds, Integer.MAX_VALUE);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer) {
        this(regionX, regionZ, dimension, data, timestampSeconds, caveLayer, 0, 0);
    }

    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer, int partIndex, int totalParts) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimension = dimension;
        this.data = data;
        this.timestampSeconds = timestampSeconds;
        this.caveLayer = caveLayer;
        this.partIndex = partIndex;
        this.totalParts = totalParts;
    }

    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }

    public static ChunkMapData[] split(ChunkMapData original) {
        int totalParts = (original.data.length + MAX_PAYLOAD_BYTES - 1) / MAX_PAYLOAD_BYTES;
        if (totalParts <= 1) {
            return new ChunkMapData[] { original };
        }
        ChunkMapData[] parts = new ChunkMapData[totalParts];
        for (int i = 0; i < totalParts; i++) {
            int offset = i * MAX_PAYLOAD_BYTES;
            int len = Math.min(MAX_PAYLOAD_BYTES, original.data.length - offset);
            byte[] partData = new byte[len];
            System.arraycopy(original.data, offset, partData, 0, len);
            parts[i] = new ChunkMapData(original.regionX, original.regionZ, original.dimension,
                    partData, original.timestampSeconds, original.caveLayer, i, totalParts);
        }
        return parts;
    }
}
