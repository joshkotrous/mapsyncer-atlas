package com.mapsyncer.mca.convert.biome;

import com.mapsyncer.mca.ChunkDataParser;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.convert.model.MapRegionData;

import static com.mapsyncer.mca.convert.model.ConvertConstants.CHUNKS_PER_REGION;
import static com.mapsyncer.mca.convert.model.ConvertConstants.REGION_SIZE_BLOCKS;

public final class BiomeFillPass {

    private BiomeFillPass() {}

    public static void fill(MapRegionData data) {
        for (int rx = 0; rx < REGION_SIZE_BLOCKS; rx++) {
            for (int rz = 0; rz < REGION_SIZE_BLOCKS; rz++) {
                int chunkX = rx >> 4;
                int chunkZ = rz >> 4;
                if (chunkX >= CHUNKS_PER_REGION || chunkZ >= CHUNKS_PER_REGION) {
                    continue;
                }

                ChunkDataParser.ChunkInfo chunk = data.chunkGrid[chunkX][chunkZ];
                if (chunk == null) {
                    continue;
                }

                int lx = rx & 0xF;
                int lz = rz & 0xF;
                int[][] heightmap = chunk.heightmap();

                boolean caveMode = data.lightMode == LightMode.CAVE
                    && data.caveParams.caveStart() != Integer.MAX_VALUE;

                int sampleY;
                if (data.hasData[rx][rz]) {
                    sampleY = data.heightMap[rx][rz];
                } else if (caveMode) {
                    sampleY = data.caveParams.caveStart();
                } else if (heightmap != null) {
                    sampleY = heightmap[lx][lz];
                    data.heightMap[rx][rz] = sampleY;
                } else {
                    continue;
                }

                String biome = caveMode
                    ? BiomeQuartResolver.resolveAtY(chunk, lx, sampleY, lz)
                    : BiomeQuartResolver.resolve(chunk, lx, sampleY, lz);
                if (BiomeQuartResolver.isValidBiome(biome)) {
                    data.biomeNames[rx][rz] = biome;
                }
            }
        }
    }
}
