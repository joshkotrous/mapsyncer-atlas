package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CheckedOutputStream;
import java.util.zip.CRC32;

public final class XaeroMapDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapDataHandler.class);

    private XaeroMapDataHandler() {}

    private static final Set<RegionCoord> updatedRegions = ConcurrentHashMap.newKeySet();

    private static final Set<RegionCoord> preUnloadedRegions = ConcurrentHashMap.newKeySet();

    public record RegionCoord(int x, int z, int caveLayer) {

        public RegionCoord(int x, int z) {
            this(x, z, Integer.MAX_VALUE);
        }

        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    public static Set<RegionCoord> getUpdatedRegions() {
        return Set.copyOf(updatedRegions);
    }

    public static Set<RegionCoord> getPreUnloadedRegions() {
        return Set.copyOf(preUnloadedRegions);
    }

    static Set<RegionCoord> getPreUnloadedRegionsInternal() {
        return preUnloadedRegions;
    }

    public static void clearPreUnloadedRegions() {
        preUnloadedRegions.clear();
    }

    public static void clearRegionTracking() {
        updatedRegions.clear();
        preUnloadedRegions.clear();
        LOGGER.debug("Cleared region tracking sets");
    }

    public static void recordUpdatedRegions(List<ChunkMapData> chunks) {
        updatedRegions.clear();

        for (ChunkMapData chunk : chunks) {
            updatedRegions.add(new RegionCoord(chunk.regionX, chunk.regionZ, chunk.caveLayer));
        }
        LOGGER.debug("Recorded {} updated regions for selective reset", updatedRegions.size());
    }

    public static void recordUpdatedRegionCoords(Set<RegionCoord> coords) {
        updatedRegions.clear();
        updatedRegions.addAll(coords);
        LOGGER.debug("Recorded {} updated region coords for selective reset", updatedRegions.size());
    }

    public record RegionWriteResult(Path mwDir, Path outputFile, String crc32Hash) {}

    public static RegionWriteResult writeChunkData(ChunkMapData chunk, Path serverDir, int worldId) {
        return writeChunkData(chunk, serverDir, worldId, false);
    }

    public static RegionWriteResult writeChunkData(ChunkMapData chunk, Path serverDir, int worldId,
            boolean integratedHost) {
        return writeChunkData(chunk, serverDir, worldId, integratedHost, null);
    }

    public static RegionWriteResult writeChunkData(ChunkMapData chunk, Path serverDir, int worldId,
            boolean integratedHost, String selectedMultiworld) {
        String xaeroDim = chunk.dimension;
        if (!com.mapsyncer.util.DimensionPathMapping.isSafeXaeroFolder(xaeroDim)) {
            LOGGER.error("Rejected unsafe map dimension: {}", xaeroDim);
            return null;
        }
        Path dimDir = serverDir.resolve(xaeroDim);

        if (selectedMultiworld != null && !selectedMultiworld.isEmpty()
                && !isSafeMultiworld(selectedMultiworld)) {
            LOGGER.error("Rejected unsafe Xaero multiworld: {}", selectedMultiworld);
            return null;
        }
        Path mwDir = selectedMultiworld != null
                ? (selectedMultiworld.isEmpty() ? dimDir : dimDir.resolve(selectedMultiworld))
                : (integratedHost ? dimDir : findExistingMwDir(dimDir));
        if (mwDir == null) {

            mwDir = dimDir.resolve("mw$" + worldId);
        } else {
            LOGGER.debug("Using existing Xaero mw$ directory: {} (server worldId={})", mwDir, worldId);
        }

        Path targetDir;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            targetDir = mwDir;
        } else {
            targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.caveLayer));
        }

        Path outputFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip");

        if (!HashUtils.isValidRegionZip(chunk.data)) {
            LOGGER.error("Refusing to write invalid region zip: {} ({} bytes)", outputFile, chunk.data.length);
            return null;
        }

        CRC32 crc32 = new CRC32();
        Path tempFile = null;
        try {
            Files.createDirectories(targetDir);

            tempFile = Files.createTempFile(targetDir,
                    chunk.regionX + "_" + chunk.regionZ + ".zip.", ".mapsyncer.tmp");
            try (OutputStream fileOut = Files.newOutputStream(tempFile);
                 CheckedOutputStream checkedOut = new CheckedOutputStream(fileOut, crc32)) {
                checkedOut.write(chunk.data);
            }
            moveReplacing(tempFile, outputFile);
            tempFile = null;
            LOGGER.debug("Wrote map file: {} (layer={}, {} bytes)", outputFile,
                chunk.isSurfaceLayer() ? "surface" : chunk.caveLayer, chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    LOGGER.debug("Failed to delete temporary map file: {}", tempFile, e);
                }
            }
        }

        return new RegionWriteResult(mwDir, outputFile, String.format("%08x", crc32.getValue()));
    }

    public static boolean isSafeMultiworld(String name) {
        return name != null && !name.isBlank() && !name.equals(".") && !name.equals("..")
                && !name.endsWith(".") && !name.endsWith(" ")
                && !name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        final int maxAttempts = 8;
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                if (attempt >= maxAttempts || !Files.exists(source)) {
                    throw e;
                }
                LOGGER.debug("Retrying map file replace (attempt {}/{}): {} -> {}",
                        attempt, maxAttempts, source, target);
                try {
                    Thread.sleep(100L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    public static Path writeMapData(List<ChunkMapData> chunks, Path serverDir, int worldId) {
        return writeMapData(chunks, serverDir, worldId, false);
    }

    public static Path writeMapData(List<ChunkMapData> chunks, Path serverDir, int worldId, boolean integratedHost) {
        return writeMapData(chunks, serverDir, worldId, integratedHost, dimension -> null);
    }

    public static Path writeMapData(List<ChunkMapData> chunks, Path serverDir, int worldId, boolean integratedHost,
            java.util.function.Function<String, String> multiworldResolver) {
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);

        Path lastMwDir = null;
        for (ChunkMapData chunk : chunks) {
            RegionWriteResult result = writeChunkData(chunk, serverDir, worldId, integratedHost,
                    multiworldResolver.apply(chunk.dimension));
            if (result == null) {
                continue;
            }
            lastMwDir = result.mwDir();

            if (tsCache != null) {
                String relativePath = buildRelativePathForCache(chunk);
                tsCache.update(relativePath, chunk.timestampSeconds, result.crc32Hash());
                LOGGER.debug("Updated timestamp cache for {}: ts={}s, hash={}",
                        relativePath, chunk.timestampSeconds, result.crc32Hash());
            }
        }

        if (tsCache != null) {
            tsCache.save();
        }
        LOGGER.info("Saved timestamp cache for {} regions", chunks.size());

        return lastMwDir;
    }

    public static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;
        if (!com.mapsyncer.util.DimensionPathMapping.isSafeXaeroFolder(xaeroDim)) {
            LOGGER.error("Rejected unsafe map dimension: {}", xaeroDim);
            return null;
        }

        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    private static Path findExistingMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) {
            return null;
        }
        try {

            try (var stream = Files.list(dimDir)) {
                return stream
                        .filter(p -> p.getFileName().toString().startsWith("mw$"))
                        .filter(Files::isDirectory)
                        .max((a, b) -> {

                            try {
                                return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                                        Files.getLastModifiedTime(b).toMillis());
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .orElse(null);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to search for existing mw$ directory: {}", e.getMessage());
            return null;
        }
    }

    public static void clearRegionCacheFiles(Path mwDir, RegionCoord coord) {
        if (mwDir == null) {
            return;
        }

        String baseName = coord.x() + "_" + coord.z();
        for (Path cacheDir : findCacheDirectories(mwDir)) {
            Path cacheRoot = coord.isSurfaceLayer()
                ? cacheDir
                : cacheDir.resolve("caves").resolve(String.valueOf(coord.caveLayer()));
            deleteRegionCacheFile(cacheRoot.resolve(baseName + ".xwmc"));
            deleteRegionCacheFile(cacheRoot.resolve(baseName + ".xwmc.outdated"));
        }
    }

    private static void deleteRegionCacheFile(Path cacheFile) {
        if (!Files.exists(cacheFile)) {
            return;
        }
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.debug("Cleared region cache: {}", cacheFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to clear region cache: {}", cacheFile, e);
        }
    }

    private static List<Path> findCacheDirectories(Path mwDir) {
        List<Path> cacheDirs = new ArrayList<>();

        try {
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (Files.isDirectory(cache)) {
                cacheDirs.add(cache);
            }
            if (Files.isDirectory(cache1)) {
                cacheDirs.add(cache1);
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (Files.isDirectory(dir) && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to find cache directories under {}", mwDir, e);
        }

        return cacheDirs;
    }
}
