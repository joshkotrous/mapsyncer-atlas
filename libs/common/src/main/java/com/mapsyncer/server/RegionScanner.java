package com.mapsyncer.server;

import com.mapsyncer.mca.McaContentProbe;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScanner.class);

    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    public record RegionCoords(int x, int z) {
    }

    public record RegionFileEntry(RegionCoords coords, Path path, long lastModifiedMillis, long sizeBytes) {}

    public record RegionScanResult(List<RegionCoords> regions, int skippedEmptyCount, List<RegionFileEntry> fileEntries) {
    }

    public record DimensionRegions(net.minecraft.resources.ResourceKey<Level> dimension, List<RegionCoords> regions,
                                   int skippedEmptyCount, List<RegionFileEntry> fileEntries) {
    }

    public static List<DimensionRegions> scanAllDimensions(MinecraftServer server) {
        List<DimensionNames> dimNames = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimId = DimensionApiHelper.getDimId(level.dimension());
            final String finalDimId = dimId;
            if (!dimNames.stream().anyMatch(d -> d.name().equals(finalDimId))) {
                dimNames.add(new DimensionNames(dimId, level.dimension()));
            }
        }

        List<DimensionRegions> result = new ArrayList<>();
        for (DimensionNames dn : dimNames) {
            RegionScanResult scanResult = scanRegionDir(server.getWorldPath(LevelResource.ROOT), dn.key());
            result.add(new DimensionRegions(dn.key(), scanResult.regions(), scanResult.skippedEmptyCount(), scanResult.fileEntries()));
        }
        return result;
    }

    public static RegionScanResult scanDimension(ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        return scanRegionDir(worldRoot, level.dimension());
    }

    public static Path getRegionDir(ServerLevel level) {
        try {
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
            if (!Files.exists(worldRoot)) return null;
            worldRoot = worldRoot.toRealPath();

            DimensionPathMapping mapping = DimensionPathMapping.getInstance();
            String dimId = DimensionApiHelper.getDimId(level.dimension());

            Path regionDir = mapping.detectRegionDir(worldRoot, dimId);

            if (regionDir != null && Files.exists(regionDir)) {
                return regionDir.toRealPath();
            }

            LOGGER.warn("Region directory not found for dimension {} after detection", dimId);
            return null;
        } catch (IOException e) {
            LOGGER.error("Failed to get region directory", e);
            return null;
        }
    }

    private static RegionScanResult scanRegionDir(Path worldRoot, net.minecraft.resources.ResourceKey<Level> dimensionKey) {
        DimensionPathMapping mapping = DimensionPathMapping.getInstance();
        String dimId = DimensionApiHelper.getDimId(dimensionKey);

        Path regionDir = mapping.detectRegionDir(worldRoot, dimId);

        if (regionDir == null || !Files.exists(regionDir)) {
            LOGGER.warn("Region directory not found for dimension: {}", dimId);
            return new RegionScanResult(List.of(), 0, List.of());
        }

        return scanRegionDirectory(regionDir);
    }

    public static List<RegionFileEntry> listRegionFiles(Path regionDir) {
        List<RegionFileEntry> entries = new ArrayList<>();
        if (!Files.exists(regionDir)) {
            return entries;
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    long size = attrs.size();
                    if (size == 0 || !McaContentProbe.hasAnyChunk(file)) {
                        continue;
                    }
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    entries.add(new RegionFileEntry(
                            new RegionCoords(regionX, regionZ),
                            file,
                            attrs.lastModifiedTime().toMillis(),
                            size));
                } catch (IOException e) {
                    LOGGER.warn("Failed to read attributes for {}", fileName, e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list region directory: {}", regionDir, e);
        }
        return entries;
    }

    public static RegionScanResult scanRegionDirectory(Path regionDir) {
        List<RegionCoords> regions = new ArrayList<>();
        if (!Files.exists(regionDir)) {
            return new RegionScanResult(regions, 0, List.of());
        }

        int skippedEmpty = 0;
        List<RegionFileEntry> fileEntries = new ArrayList<>();

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        long fileSize = attrs.size();
                        if (fileSize == 0) {
                            skippedEmpty++;
                            LOGGER.debug("Skipping empty MCA file: {} (0 bytes)", fileName);
                            continue;
                        }
                        if (!McaContentProbe.hasAnyChunk(file)) {
                            skippedEmpty++;
                            LOGGER.debug("Skipping header-only MCA file: {} ({} bytes)", fileName, fileSize);
                            continue;
                        }
                        int regionX = Integer.parseInt(matcher.group(1));
                        int regionZ = Integer.parseInt(matcher.group(2));
                        RegionCoords coords = new RegionCoords(regionX, regionZ);
                        regions.add(coords);
                        fileEntries.add(new RegionFileEntry(
                                coords, file, attrs.lastModifiedTime().toMillis(), fileSize));
                    } catch (IOException e) {
                        LOGGER.warn("Failed to check file attributes for {}", fileName, e);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }

        if (skippedEmpty > 0) {
            LOGGER.info("Skipped {} empty (0KB) MCA files in {}", skippedEmpty, regionDir);
        }

        return new RegionScanResult(regions, skippedEmpty, fileEntries);
    }

    private record DimensionNames(String name, net.minecraft.resources.ResourceKey<Level> key) {}
}
