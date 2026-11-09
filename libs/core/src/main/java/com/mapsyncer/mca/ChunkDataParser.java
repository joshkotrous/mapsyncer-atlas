package com.mapsyncer.mca;

import com.mapsyncer.mca.convert.biome.BiomeQuartGrid;
import com.mapsyncer.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkDataParser {

    private static final Set<String> ACCEPTABLE_STATUSES = Set.of(
        "minecraft:features",
        "minecraft:light",
        "minecraft:spawn",
        "minecraft:heightmaps",
        "minecraft:full",

        "features",
        "light",
        "spawn",
        "heightmaps",
        "full"
    );

    private static boolean shouldSkipChunk(String status) {
        if (status == null || status.isEmpty()) {
            return true;
        }

        String normalizedStatus = status.contains(":") ? status : "minecraft:" + status;

        return !ACCEPTABLE_STATUSES.contains(normalizedStatus);
    }

    public record ChunkInfo(
        int chunkX,
        int chunkZ,
        int yPos,
        int chunkBottomY,
        String status,
        int[][] heightmap,
        List<ChunkSectionParser.SectionData> sections,
        int minSectionY,
        ChunkSectionParser.SectionData[] sectionLookup,
        BiomeQuartGrid biomeGrid
    ) {}

    public static ChunkInfo parseChunk(int localX, int localZ, Tag.Compound chunkNbt, int worldHeightRange) {

        String status = chunkNbt.getString("Status");

        if (shouldSkipChunk(status)) {
            return null;
        }

        Tag.Compound rootTag;
        if (chunkNbt.contains("sections", Tag.TAG_LIST)) {
            rootTag = chunkNbt;
        } else if (chunkNbt.contains("Level", Tag.TAG_COMPOUND)) {

            rootTag = chunkNbt.getCompound("Level");
        } else {
            return null;
        }

        int yPos = chunkNbt.getInt("yPos");
        int chunkBottomY = yPos * 16;

        int[][] heightmap = parseHeightmap(rootTag, chunkBottomY, worldHeightRange);

        List<ChunkSectionParser.SectionData> sections = new ArrayList<>();
        if (rootTag.contains("sections", Tag.TAG_LIST)) {
            Tag.ListTag sectionsList = rootTag.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < sectionsList.items().size(); i++) {
                Tag item = sectionsList.items().get(i);
                if (!(item instanceof Tag.Compound sectionTag)) {
                    continue;
                }
                ChunkSectionParser.SectionData section = ChunkSectionParser.parseSection(sectionTag);
                sections.add(section);
            }
        }

        sections.sort((a, b) -> Integer.compare(b.sectionY(), a.sectionY()));

        int minSectionY = 0;
        ChunkSectionParser.SectionData[] sectionLookup = null;
        if (!sections.isEmpty()) {
            int maxY = sections.get(0).sectionY();
            minSectionY = sections.get(sections.size() - 1).sectionY();
            int range = maxY - minSectionY + 1;
            sectionLookup = new ChunkSectionParser.SectionData[range];
            for (ChunkSectionParser.SectionData sec : sections) {
                int idx = sec.sectionY() - minSectionY;
                if (idx >= 0 && idx < range) sectionLookup[idx] = sec;
            }
        }

        BiomeQuartGrid biomeGrid = BiomeQuartGrid.build(sections, minSectionY, sectionLookup);

        return new ChunkInfo(localX, localZ, yPos, chunkBottomY, status, heightmap, sections, minSectionY, sectionLookup, biomeGrid);
    }

    private static int[][] parseHeightmap(Tag.Compound rootTag, int chunkBottomY, int worldHeightRange) {
        int[][] heightmap = new int[16][16];

        if (rootTag.contains("Heightmaps", Tag.TAG_COMPOUND)) {
            Tag.Compound heightmaps = rootTag.getCompound("Heightmaps");

            if (heightmaps.contains("WORLD_SURFACE", Tag.TAG_LONG_ARRAY)) {
                long[] data = heightmaps.getLongArray("WORLD_SURFACE");
                int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
                if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                    decodeHeightmapLongArray(data, bitsPerHeight, chunkBottomY, heightmap);
                    return heightmap;
                }
            }

            if (heightmaps.contains("MOTION_BLOCKING_NO_LEAVES", Tag.TAG_LONG_ARRAY)) {
                long[] data = heightmaps.getLongArray("MOTION_BLOCKING_NO_LEAVES");
                int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
                if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                    decodeHeightmapLongArray(data, bitsPerHeight, chunkBottomY, heightmap);
                    return heightmap;
                }
            }
        }

        if (rootTag.contains("HeightMap", Tag.TAG_INT_ARRAY)) {
            int[] data = rootTag.getIntArray("HeightMap");
            if (data.length == 256) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        heightmap[x][z] = data[z * 16 + x];
                    }
                }
                return heightmap;
            }
        }

        return heightmap;
    }

    private static int calculateBitsPerHeight(int longArrayLength, int worldHeightRange) {

        if (worldHeightRange > 0) {
            // b = ceil(log2(h))
            return 32 - Integer.numberOfLeadingZeros(worldHeightRange - 1);
        }

        // l = ceil(256/u) => u ≈ ceil(256/l)
        // b = floor(64/u)
        if (longArrayLength <= 0) return 0;
        int u = (256 + longArrayLength - 1) / longArrayLength; // ceil(256/l)
        return 64 / u;
    }

    private static void decodeHeightmapLongArray(long[] data, int bitsPerHeight, int chunkBottomY, int[][] heightmap) {
        if (data == null || data.length == 0 || bitsPerHeight <= 0) {
            return;
        }

        // u = floor(64/b)
        int u = 64 / bitsPerHeight;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // Wiki: i = x + 16*z
                int i = x + 16 * z;

                int longIndex = i / u;
                int bitOffset = (i % u) * bitsPerHeight;

                if (longIndex >= data.length) {
                    heightmap[x][z] = chunkBottomY;
                    continue;
                }

                long rawValue = (data[longIndex] >>> bitOffset) & ((1L << bitsPerHeight) - 1L);

                heightmap[x][z] = chunkBottomY + (int) rawValue;
            }
        }
    }

    public static ChunkSectionParser.BlockState getBlockStateAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;
        ChunkSectionParser.SectionData[] lookup = chunk.sectionLookup();
        if (lookup != null) {
            int idx = sectionY - chunk.minSectionY();
            if (idx >= 0 && idx < lookup.length && lookup[idx] != null) {
                return ChunkSectionParser.getBlockStateAt(lookup[idx], x, localY, z);
            }
        }
        return new ChunkSectionParser.BlockState("minecraft:air", Map.of());
    }

    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z) {
        return getBiomeAt(chunk, x, worldY, z, false);
    }

    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z, boolean smoothBoundary) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;
        ChunkSectionParser.SectionData[] lookup = chunk.sectionLookup();
        if (lookup != null) {
            int idx = sectionY - chunk.minSectionY();
            if (idx >= 0 && idx < lookup.length && lookup[idx] != null) {
                return ChunkSectionParser.getBiomeAt(lookup[idx], x, localY, z, smoothBoundary);
            }
        }
        return null;
    }

    public static byte getBlockLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBlockLight(section, x, localY, z);
            }
        }

        return 0;
    }

    public static byte getSkyLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getSkyLight(section, x, localY, z);
            }
        }

        return 0;
    }

    public static boolean hasSkyAccess(ChunkInfo chunk, int x, int worldY, int z) {
        int surfaceY = chunk.heightmap()[x][z];
        return worldY >= surfaceY;
    }

    public static byte getEffectiveLight(ChunkInfo chunk, int x, int worldY, int z,
                                          LightMode lightMode, boolean hasOverlay,
                                          boolean worldHasSkylight) {
        byte blockLight = getBlockLightAt(chunk, x, worldY, z);
        byte skyLight = getSkyLightAt(chunk, x, worldY, z);
        boolean hasSkyAccess = hasSkyAccess(chunk, x, worldY, z);

        return lightMode.calculateEffectiveLight(blockLight, skyLight, hasSkyAccess, hasOverlay, false, worldHasSkylight);
    }

    public static byte getEffectiveLightCave(ChunkInfo chunk, int x, int worldY, int z,
                                              boolean hasOverlay) {
        byte blockLight = getBlockLightAt(chunk, x, worldY, z);

        if (blockLight >= 15) {
            return blockLight;
        }

        boolean hasSkyAccess = hasSkyAccess(chunk, x, worldY, z);

        if (hasSkyAccess && !hasOverlay) {
            return 15;
        }

        if (!hasOverlay) {
            byte skyLight = getSkyLightAt(chunk, x, worldY, z);
            return (byte) Math.max(blockLight, skyLight);
        }

        return blockLight;
    }

    public static int getHeightmapStartY(ChunkInfo chunk, int x, int z, int worldTopY) {
        int heightMapValue = chunk.heightmap()[x][z];

        int startY = heightMapValue + 3;

        return Math.min(startY, worldTopY - 1);
    }
}
