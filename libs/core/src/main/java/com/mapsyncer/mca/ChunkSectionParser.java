package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkSectionParser {

    private static final String DEFAULT_BLOCK_NAME = "minecraft:air";

    private static final Set<String> FLOWER_NAMES = Set.of(
        "minecraft:dandelion", "minecraft:poppy",
        "minecraft:blue_orchid", "minecraft:allium",
        "minecraft:red_tulip", "minecraft:orange_tulip",
        "minecraft:white_tulip", "minecraft:pink_tulip",
        "minecraft:oxeye_daisy", "minecraft:cornflower",
        "minecraft:lily_of_the_valley", "minecraft:wither_rose",
        "minecraft:sunflower", "minecraft:rose_bush",
        "minecraft:peony", "minecraft:azure_bluet",
        "minecraft:pitcher_plant"
    );

    public record BlockState(
        String name,
        Map<String, String> properties
    ) {

        public static final Map<String, String> EMPTY_PROPERTIES = Map.of();

        public String getFullName() {
            if (properties.isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name);
            sb.append("[");
            boolean first = true;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!first) sb.append(",");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }

        public boolean isAir() {
            return name.equals("minecraft:air") ||
                   name.equals("minecraft:cave_air") ||
                   name.equals("minecraft:void_air");
        }

        public boolean isWater() {
            return name.equals("minecraft:water") || name.equals("minecraft:flowing_water");
        }

        public boolean isLava() {
            return name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava");
        }

        public boolean isFluid() {
            return isWater() || isLava();
        }

        public boolean isGrassBlock() {
            return name.equals("minecraft:grass_block");
        }

        public boolean isTransparentOverlay() {
            return isWater() || name.equals("minecraft:glass") ||
                   name.endsWith("_stained_glass") || name.equals("minecraft:glass_pane") ||
                   name.endsWith("_stained_glass_pane") || name.equals("minecraft:ice") ||
                   name.endsWith("_ice") || name.equals("minecraft:tinted_glass");
        }

        public boolean isInvisible() {
            // Torch
            if (name.equals("minecraft:torch") || name.endsWith("_torch")) return true;
            // Short grass
            if (name.equals("minecraft:short_grass") || name.equals("minecraft:grass")) return true;
            // Glass (handled as transparent overlay, not invisible in scan)
            // Flowers (default flowers config off)
            if (isFlower()) return true;
            // Double plant non-flowers (tall_grass, large_fern)
            if (name.equals("minecraft:tall_grass") || name.equals("minecraft:large_fern")) return true;
            return false;
        }

        public boolean isFlower() {
            return FLOWER_NAMES.contains(name) ||
                   name.endsWith("_tulip") ||
                   name.contains("orchid") ||
                   name.endsWith("_pitcher_crop");
        }

        public boolean isWaterlogged() {
            return properties.containsKey("waterlogged") &&
                   "true".equals(properties.get("waterlogged"));
        }

        public boolean isWaterloggedSurface() {
            return isWaterlogged() && !isWater() && !isAir();
        }
    }

    public record SectionData(
        int sectionY,
        List<BlockState> blockPalette,
        List<String> blockNames,
        long[] blockData,
        int blockBitsPerEntry,
        List<String> biomePalette,
        long[] biomeData,
        int biomeBitsPerEntry,
        byte[] blockLight,
        byte[] skyLight,
        int blockUVal,
        long blockMask
    ) {

    }

    public static SectionData parseSection(Tag.Compound sectionTag) {
        int sectionY = sectionTag.getByte("Y");

        List<BlockState> blockPalette = new ArrayList<>();
        List<String> blockNames = new ArrayList<>();
        long[] blockData = null;
        int blockBitsPerEntry = 0;

        if (sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
            Tag.Compound blockStates = sectionTag.getCompound("block_states");

            if (blockStates.contains("palette", Tag.TAG_LIST)) {
                Tag.ListTag paletteList = blockStates.getList("palette", Tag.TAG_COMPOUND);
                for (int i = 0; i < paletteList.items().size(); i++) {
                    BlockState blockState = parsePaletteEntry(paletteList.items().get(i));
                    blockPalette.add(blockState);
                    blockNames.add(blockState.name());
                }
            }

            if (blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
                blockData = blockStates.getLongArray("data");
            }

            blockBitsPerEntry = calculateBitsPerEntry(blockPalette.size(), blockData);
        }

        List<String> biomePalette = new ArrayList<>();
        long[] biomeData = null;
        int biomeBitsPerEntry = 0;

        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            Tag.Compound biomes = sectionTag.getCompound("biomes");

            if (biomes.contains("palette", Tag.TAG_LIST)) {
                Tag.ListTag paletteList = biomes.getList("palette", Tag.TAG_STRING);
                for (int i = 0; i < paletteList.items().size(); i++) {
                    Tag item = paletteList.items().get(i);
                    if (item instanceof Tag.StringTag biomeTag) {
                        biomePalette.add(biomeTag.value());
                    }
                }
            }

            if (biomes.contains("data", Tag.TAG_LONG_ARRAY)) {
                biomeData = biomes.getLongArray("data");
            }

            biomeBitsPerEntry = calculateBiomeBitsPerEntry(biomePalette.size(), biomeData);
        }

        byte[] decodedBlockLight = null;
        byte[] decodedSkyLight = null;
        byte[] rawBlockLight = sectionTag.getByteArray("BlockLight");
        byte[] rawSkyLight = sectionTag.getByteArray("SkyLight");
        if (rawBlockLight != null && rawBlockLight.length == 2048) {
            decodedBlockLight = new byte[4096];
            for (int i = 0; i < 2048; i++) {
                int b = rawBlockLight[i] & 0xFF;
                int idx = i << 1;
                decodedBlockLight[idx] = (byte) (b & 0xF);
                decodedBlockLight[idx + 1] = (byte) ((b >> 4) & 0xF);
            }
        }
        if (rawSkyLight != null && rawSkyLight.length == 2048) {
            decodedSkyLight = new byte[4096];
            for (int i = 0; i < 2048; i++) {
                int b = rawSkyLight[i] & 0xFF;
                int idx = i << 1;
                decodedSkyLight[idx] = (byte) (b & 0xF);
                decodedSkyLight[idx + 1] = (byte) ((b >> 4) & 0xF);
            }
        }

        int blockUVal = 0;
        long blockMask = 0;
        if (blockData != null && blockData.length > 0 && blockBitsPerEntry > 0) {
            blockUVal = 64 / blockBitsPerEntry;
            blockMask = (1L << blockBitsPerEntry) - 1L;
        }

        return new SectionData(
            sectionY, blockPalette, blockNames, blockData, blockBitsPerEntry,
            biomePalette, biomeData, biomeBitsPerEntry,
            decodedBlockLight, decodedSkyLight,
            blockUVal, blockMask
        );
    }

    private static BlockState parsePaletteEntry(Tag entry) {
        if (entry instanceof Tag.StringTag str) {
            return new BlockState(str.value(), BlockState.EMPTY_PROPERTIES);
        }
        if (entry instanceof Tag.Compound compound) {
            return parseBlockState(compound);
        }
        return new BlockState(DEFAULT_BLOCK_NAME, BlockState.EMPTY_PROPERTIES);
    }

    private static BlockState parseBlockState(Tag.Compound stateTag) {
        String name = resolveBlockName(stateTag);
        if (name.isEmpty()) {
            return new BlockState(DEFAULT_BLOCK_NAME, BlockState.EMPTY_PROPERTIES);
        }

        Tag.Compound propsTag = resolvePropertiesTag(stateTag);

        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Tag> entry : propsTag.children().entrySet()) {
            Tag propTag = entry.getValue();
            if (propTag instanceof Tag.StringTag str) {
                properties.put(entry.getKey(), str.value());
            }
        }

        if (properties.isEmpty()) {
            return new BlockState(name, BlockState.EMPTY_PROPERTIES);
        }

        return new BlockState(name, properties);
    }

    private static String resolveBlockName(Tag.Compound stateTag) {
        String name = stateTag.getString("Name");
        if (!name.isEmpty()) {
            return name;
        }
        name = stateTag.getString("id");
        if (!name.isEmpty()) {
            return name;
        }
        return stateTag.getString("");
    }

    private static Tag.Compound resolvePropertiesTag(Tag.Compound stateTag) {
        if (stateTag.contains("Properties", Tag.TAG_COMPOUND)) {
            return stateTag.getCompound("Properties");
        }
        return stateTag.getCompound("properties");
    }

    private static int calculateBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;
        }

        if (paletteSize <= 16) {
            return 4;
        }
        // ceil(log2(c)) = 32 - numberOfLeadingZeros(c - 1)
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private static int calculateBiomeBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;
        }

        // ceil(log2(c)) = 32 - numberOfLeadingZeros(c - 1)
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    public static BlockState getBlockStateAt(SectionData section, int x, int y, int z) {
        if (section.blockPalette.isEmpty()) {
            return new BlockState("minecraft:air", Map.of());
        }

        if (section.blockPalette.size() == 1) {
            return section.blockPalette.get(0);
        }

        if (section.blockData == null || section.blockBitsPerEntry == 0) {
            return new BlockState("minecraft:air", Map.of());
        }

        int blockIndex = (y << 8) | (z << 4) | x;

        int u = section.blockUVal();
        long mask = section.blockMask();
        int paletteIndex = readBitsFast(section.blockData, blockIndex, u, section.blockBitsPerEntry(), mask);

        if (paletteIndex < 0 || paletteIndex >= section.blockPalette.size()) {
            return new BlockState("minecraft:air", Map.of());
        }

        return section.blockPalette.get(paletteIndex);
    }

    public static String getBlockAt(SectionData section, int x, int y, int z) {
        return getBlockStateAt(section, x, y, z).name();
    }

    public static String getBiomeAt(SectionData section, int x, int y, int z) {
        return getBiomeAt(section, x, y, z, false);
    }

    public static String getBiomeAt(SectionData section, int x, int y, int z, boolean smoothBoundary) {

        if (section.biomePalette.isEmpty()) {
            return null;
        }

        if (section.biomePalette.size() == 1) {
            return section.biomePalette.get(0);
        }

        if (section.biomeData == null || section.biomeBitsPerEntry == 0) {
            return null;
        }

        int voxelY = y >> 2;
        int voxelZ = z >> 2;
        int voxelX = x >> 2;

        if (smoothBoundary) {

            int relX = x & 3;
            int relZ = z & 3;

            if (relX >= 2 && voxelX < 3) {
                voxelX++;
            }
            if (relZ >= 2 && voxelZ < 3) {
                voxelZ++;
            }
        }

        int voxelIndex = (voxelY << 4) | (voxelZ << 2) | voxelX;

        int paletteIndex = readBitsFromArray(section.biomeData, voxelIndex, section.biomeBitsPerEntry);

        if (paletteIndex < 0 || paletteIndex >= section.biomePalette.size()) {
            return null;
        }

        return section.biomePalette.get(paletteIndex);
    }

    public static int readBitsFromArray(long[] data, int index, int bitsPerEntry) {
        if (data == null || data.length == 0 || bitsPerEntry <= 0) {
            return 0;
        }
        int u = 64 / bitsPerEntry;
        long mask = (1L << bitsPerEntry) - 1L;
        return readBitsFast(data, index, u, bitsPerEntry, mask);
    }

    public static int readBitsFast(long[] data, int index, int u, int bitsPerEntry, long mask) {
        int longIndex = index / u;
        if (longIndex >= data.length) return 0;
        int bitOffset = (index % u) * bitsPerEntry;
        return (int) ((data[longIndex] >>> bitOffset) & mask);
    }

    public static byte getBlockLight(SectionData section, int x, int y, int z) {
        byte[] d = section.blockLight();
        if (d != null && d.length == 4096) return d[(y << 8) | (z << 4) | x];
        return 0;
    }

    public static byte getSkyLight(SectionData section, int x, int y, int z) {
        byte[] d = section.skyLight();
        if (d != null && d.length == 4096) return d[(y << 8) | (z << 4) | x];
        return 0;
    }

    public static byte getLightValue(byte[] lightArray, int x, int y, int z) {
        if (lightArray == null || lightArray.length != 2048) {
            return 0;
        }

        int yzx = (y << 8) | (z << 4) | x;

        return (byte) ((lightArray[yzx >> 1] >> (4 * (yzx & 1))) & 0xF);
    }

    public static LightData parseLightData(SectionData section) {
        byte[] blockLight = new byte[4096];
        byte[] skyLight = new byte[4096];

        if (section.blockLight() != null && section.blockLight().length == 2048) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        blockLight[idx] = getLightValue(section.blockLight(), x, y, z);
                    }
                }
            }
        }

        if (section.skyLight() != null && section.skyLight().length == 2048) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        skyLight[idx] = getLightValue(section.skyLight(), x, y, z);
                    }
                }
            }
        }

        return new LightData(section.sectionY(), blockLight, skyLight);
    }

    public record LightData(
        int sectionY,
        byte[] blockLight,
        byte[] skyLight
    ) {

        public boolean hasLightData() {
            return blockLight != null || skyLight != null;
        }

        public byte getBlockLightAt(int x, int localY, int z) {
            if (blockLight == null) return 0;
            int idx = (localY << 8) | (z << 4) | x;
            return idx < blockLight.length ? blockLight[idx] : 0;
        }

        public byte getSkyLightAt(int x, int localY, int z) {
            if (skyLight == null) return 0;
            int idx = (localY << 8) | (z << 4) | x;
            return idx < skyLight.length ? skyLight[idx] : 0;
        }

        public byte getEffectiveLightSurface(int x, int localY, int z) {
            return getBlockLightAt(x, localY, z);
        }

        public byte getEffectiveLightCave(int x, int localY, int z,
                                          boolean hasSkyAccess, boolean hasOverlay) {
            byte blockLight = getBlockLightAt(x, localY, z);

            if (blockLight >= 15) {
                return blockLight;
            }

            if (hasSkyAccess && !hasOverlay) {
                return 15;
            }

            if (!hasOverlay) {
                byte skyLight = getSkyLightAt(x, localY, z);
                return (byte) Math.max(blockLight, skyLight);
            }

            return blockLight;
        }

        public byte getEffectiveLight(int x, int localY, int z,
                                       LightMode lightMode,
                                       boolean hasSkyAccess, boolean hasOverlay,
                                       boolean worldHasSkylight) {
            return lightMode.calculateEffectiveLight(
                getBlockLightAt(x, localY, z),
                getSkyLightAt(x, localY, z),
                hasSkyAccess, hasOverlay, false, worldHasSkylight
            );
        }
    }
}
