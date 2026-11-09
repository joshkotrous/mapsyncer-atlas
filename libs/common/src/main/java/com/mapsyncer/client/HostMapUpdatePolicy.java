package com.mapsyncer.client;

public final class HostMapUpdatePolicy {
    private HostMapUpdatePolicy() {}
    public static boolean outsideWritingRange(int regionX, int regionZ, int playerChunkX, int playerChunkZ, int distance) {
        long minX = (long) regionX * 32, minZ = (long) regionZ * 32;
        return minX > (long) playerChunkX + distance || minX + 31 < (long) playerChunkX - distance
                || minZ > (long) playerChunkZ + distance || minZ + 31 < (long) playerChunkZ - distance;
    }
}
