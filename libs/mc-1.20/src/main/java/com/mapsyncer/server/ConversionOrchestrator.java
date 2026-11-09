package com.mapsyncer.server;

import com.mapsyncer.config.ConversionOutputPaths;
import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.RegionGenerationPlanner;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.config.TimeoutConfig;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import com.mapsyncer.mca.RegionConverterStandalone.LayerConvertedRegion;
import com.mapsyncer.mca.convert.scan.RegionScanPass;
import com.mapsyncer.server.RegionScanner.DimensionRegions;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.DimensionTypeHelper;
import com.mapsyncer.util.NamedThreadFactory;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

public class ConversionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversionOrchestrator.class);

    private static volatile ExecutorService conversionExecutor = null;

    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    private static final AtomicInteger processedCountAtomic = new AtomicInteger(0);

    private static volatile int processedCount = 0;

    private static final AtomicInteger skippedCount = new AtomicInteger(0);

    private static final AtomicInteger convertedCountAtomic = new AtomicInteger(0);

    private static final AtomicInteger skippedEmptyContentCount = new AtomicInteger(0);

    private static volatile int totalCount = 0;

    private static volatile String currentStatus = "idle";

    private static volatile ResourceKey<Level> currentDimension = null;

    private static final List<String> completedDimensions = new CopyOnWriteArrayList<>();

    private static final Path DEFAULT_CACHE_DIR = Path.of("server_map_cache");
    public static Path getCacheDir() {
        return ServerMapCache.snapshot().root();
    }

    public static void setCacheDir(Path dir) {
        ServerMapCache.configure(dir, null);
        LOGGER.info("Cache directory set to: {}", dir);
    }

    public static Path getDimensionCacheDir(String xaeroDimension) {
        return ServerMapCache.snapshot().dimensionDirectory(xaeroDimension);
    }

    public static void tryInitIntegratedServerCache(MinecraftServer server, Path gameDir) {
        if (server.isDedicatedServer()) {
            setCacheDir(DEFAULT_CACHE_DIR);
            XaeroWriter.cleanStaleTempFiles(getCacheDir());
        } else {
            Path save = server.getWorldPath(LevelResource.ROOT);
            ServerMapCache.configure(com.mapsyncer.util.XaeroPathResolver.getIntegratedWorldMapDir(gameDir, save), save);
            LOGGER.info("Reusing host Xaero map directory: {}", getCacheDir());
        }
    }

    public enum SingleRegionResult {

        SUCCESS,

        REGION_NOT_FOUND,

        CONVERSION_FAILED,

        ALREADY_RUNNING
    }

    private static ExecutorService getOrCreateExecutor() {
        if (conversionExecutor == null || conversionExecutor.isShutdown()) {
            int maxConcurrent = PlatformManager.getPlatform().getMaxConcurrentRegions();
            conversionExecutor = Executors.newFixedThreadPool(maxConcurrent,
                new NamedThreadFactory("mapsyncer-converter"));
            LOGGER.info("Created conversion thread pool with {} threads (resolved maxConcurrentRegions)", maxConcurrent);
        }
        return conversionExecutor;
    }

    public static void shutdownExecutor() {
        if (conversionExecutor != null && !conversionExecutor.isShutdown()) {
            conversionExecutor.shutdown();
            try {
                if (!conversionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    conversionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                conversionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("Conversion thread pool shut down");
        }
    }

    private static void clearDimensionCache(Path dimCacheDir) {
        if (!Files.exists(dimCacheDir)) {
            LOGGER.info("No existing cache to clear for dimension: {}", dimCacheDir);
            return;
        }

        try {
            try (var files = Files.walk(dimCacheDir)) {
                files.sorted((a, b) -> -a.compareTo(b))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                                LOGGER.debug("Deleted: {}", path);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to delete: {}", path);
                            }
                        });
            }
            LOGGER.info("Cleared cache directory: {}", dimCacheDir);
        } catch (IOException e) {
            LOGGER.error("Failed to clear dimension cache: {}", dimCacheDir, e);
        }
    }

    private static void clearGenerationCacheEntries(String xaeroDimName) {
        int removed = GenerationCache.getInstance(getCacheDir()).removeByPrefix(xaeroDimName + "/");
        if (removed > 0) {
            LOGGER.debug("Cleared {} generation_cache entries for dimension: {}", removed, xaeroDimName);
        } else {
            LOGGER.debug("No generation_cache entries found for dimension: {}", xaeroDimName);
        }
    }

    private static McaTimestampCache getTimestampCache() {
        return McaTimestampCache.getInstance(getCacheDir());
    }

    public static boolean generateAll(MinecraftServer server) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll");
            return false;
        }
        processedCount = 0;
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        completedDimensions.clear();

        // Note: caller handles saveEverything on server thread before invoking this method.

        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        totalCount = countTotalWork(server, allRegions);
        int totalSkippedEmpty = allRegions.stream().mapToInt(DimensionRegions::skippedEmptyCount).sum();
        if (totalCount == 0) {
            LOGGER.info("No regions found to convert");
            isRunning.set(false);
            return true;
        }
        LOGGER.info("Starting conversion of {} regions across {} dimensions", totalCount, allRegions.size());
        try {
            for (DimensionRegions dimRegions : allRegions) {
                convertDimension(server, dimRegions, false);
            }
        } finally {
            isRunning.set(false);
            currentStatus = "completed";
            shutdownExecutor();
            LOGGER.info("Conversion completed: {}/{} regions converted, {} skipped (empty MCA at scan)",
                    convertedCountAtomic.get(), totalCount, totalSkippedEmpty);
        }
        return true;
    }

    public static boolean generateDimension(MinecraftServer server, String dimensionId) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimension");
            return false;
        }
        processedCount = 0;
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning.set(false); return true; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning.set(false); return true; }

        // Note: caller handles saveEverything on server thread before invoking this method.

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount(), scanResult.fileEntries()), false);
        } finally {
            isRunning.set(false);
            currentStatus = "completed";
            shutdownExecutor();
        }
        return true;
    }

    public static boolean generateDimensionForce(MinecraftServer server, String dimensionId) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimensionForce");
            return false;
        }
        processedCount = 0;
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); isRunning.set(false); return true; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); isRunning.set(false); return true; }

        String fullDimId = dimKey.location().toString();
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path dimCacheDir = getDimensionCacheDir(xaeroDimName);
        if (ServerMapCache.snapshot().sharedXaero()) {
            try {
                ServerMapCache.snapshot().clearDimensionRegions(xaeroDimName);
            } catch (IOException e) {
                LOGGER.error("Failed to clear selected Xaero regions: {}", dimCacheDir, e);
                isRunning.set(false);
                return false;
            }
        } else {
            clearDimensionCache(dimCacheDir);
        }
        clearGenerationCacheEntries(xaeroDimName);

        // Note: caller handles saveEverything on server thread before invoking this method.

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount(), scanResult.fileEntries()), true);
        } finally {
            isRunning.set(false);
            currentStatus = "completed";
            shutdownExecutor();
        }
        return true;
    }

    public static Path checkMcaFileExists(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return null;

        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) return null;

        Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        return Files.exists(mcaPath) ? mcaPath : null;
    }

    public static SingleRegionResult generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress");
            return SingleRegionResult.ALREADY_RUNNING;
        }

        Path mcaPath = checkMcaFileExists(server, dimension, regionX, regionZ);
        if (mcaPath == null) {
            LOGGER.warn("MCA file not found for region ({}, {}) in dimension {}", regionX, regionZ, com.mapsyncer.util.DimensionApiHelper.getDimId(dimension));
            isRunning.set(false);
            return SingleRegionResult.REGION_NOT_FOUND;
        }

        totalCount = 1;
        processedCount = 0;
        currentDimension = dimension;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimension); isRunning.set(false); return SingleRegionResult.CONVERSION_FAILED; }

        // Note: caller handles saveEverything on server thread before invoking this method.

        String fullDimId = dimension.location().toString();
        String dimPath = com.mapsyncer.util.DimensionApiHelper.getDimId(dimension);

        DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);

        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", dimension);
            isRunning.set(false);
            return SingleRegionResult.CONVERSION_FAILED;
        }

        DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);
        Path baseOutputDir = getDimensionCacheDir(xaeroDimName);

        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, logicalTop={}, passes={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.logicalTopY(), passes.size());

        SingleRegionResult result = SingleRegionResult.SUCCESS;
        try {
            for (RegionScanPass pass : passes) {
                Files.createDirectories(ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer()));
            }
            totalCount = passes.size();
            List<LayerConvertedRegion> converted = RegionConverterStandalone.convertRegionMulti(
                mcaPath, regionX, regionZ, dimTypeInfo, passes, BlockPropertyResolver.INSTANCE);
            int written = 0;
            for (int i = 0; i < passes.size(); i++) {
                RegionScanPass pass = passes.get(i);
                LayerConvertedRegion layer = i < converted.size() ? converted.get(i) : null;
                ConvertedRegion single = layer == null ? null
                    : new ConvertedRegion(layer.regionX(), layer.regionZ(), layer.xaeroData());
                if (EmptyRegionSupport.isEmptyConverted(single)) {
                    continue;
                }
                Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
                XaeroWriter.writeRegionFile(outputDir, single);
                written++;
            }
            processedCount = written;
            if (written == 0) {
                LOGGER.warn("Could not convert region ({}, {}): all passes empty", regionX, regionZ);
                result = SingleRegionResult.CONVERSION_FAILED;
            } else {
                LOGGER.info("Converted single region ({}, {}) with {} passes", regionX, regionZ, written);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write region file", e);
            result = SingleRegionResult.CONVERSION_FAILED;
        }
        finally {
            isRunning.set(false);
            currentStatus = "completed";
        }
        return result;
    }

    private static void convertDimension(MinecraftServer server, DimensionRegions dimRegions, boolean force) {
        ServerLevel level = server.getLevel(dimRegions.dimension());
        if (level == null) { LOGGER.error("Level not loaded"); return; }

        currentDimension = dimRegions.dimension();
        String fullDimId = dimRegions.dimension().location().toString();
        String dimPath = com.mapsyncer.util.DimensionApiHelper.getDimId(dimRegions.dimension());

        DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);

        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path regionDir = RegionScanner.getRegionDir(level);
        Path baseOutputDir = getDimensionCacheDir(xaeroDimName);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", xaeroDimName);
            return;
        }

        DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
        List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);

        try {
            for (RegionScanPass pass : passes) {
                Files.createDirectories(ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer()));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create output directories under: {}", baseOutputDir, e);
            return;
        }

        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, logicalTop={}, passes={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.logicalTopY(), passes.size());

        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(getCacheDir());
        List<RegionCoords> needsUpdate = force ? dimRegions.regions()
            : (!dimRegions.fileEntries().isEmpty()
                ? mcaCache.classifyUpdates(dimPath, dimRegions.fileEntries())
                : mcaCache.scanAndUpdate(dimPath, regionDir));
        List<RegionCoords> regions = dimRegions.regions();

        totalCount = regions.size() * passes.size();
        LOGGER.info("Dimension {}: {} total regions, {} need update, {} passes/region (force={})",
            dimPath, regions.size(), needsUpdate.size(), passes.size(), force);

        ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();
        processedCountAtomic.set(0);
        skippedCount.set(0);
        convertedCountAtomic.set(0);
        skippedEmptyContentCount.set(0);
        long generationTimeSeconds = System.currentTimeMillis() / 1000;

        ExecutorService executor = getOrCreateExecutor();

        List<java.util.concurrent.Future<?>> futures = submitConversionTasks(
            executor, needsUpdate, regions, regionDir, baseOutputDir, xaeroDimName, dimPath,
            dimTypeInfo, passes, mcaCache, genCache,
            generationTimeSeconds, failedRegions, true);
        waitForCompletion(futures, "Region conversion");

        if (!force) {
            futures = submitNewRegionTasks(
                executor, regions, new HashSet<>(needsUpdate), regionDir, baseOutputDir, xaeroDimName, dimPath,
                dimTypeInfo, passes, mcaCache, genCache,
                generationTimeSeconds, failedRegions);
            waitForCompletion(futures, "New region conversion");
        }

        processedCount = processedCountAtomic.get();

        if (!failedRegions.isEmpty()) {
            LOGGER.warn("Failed to convert {} regions", failedRegions.size());
            for (RegionCoords coords : failedRegions) {
                LOGGER.warn("Failed region: ({}, {})", coords.x(), coords.z());
            }
        }

        LOGGER.info("Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty MCA at scan), {} skipped (empty content), {} failed",
            dimPath, regions.size(), convertedCountAtomic.get(), skippedCount.get(),
            dimRegions.skippedEmptyCount(), skippedEmptyContentCount.get(), failedRegions.size());

        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimRegions.dimension().location().toString());
        completedDimensions.add(friendlyName);

        mcaCache.saveCache();
        genCache.save();
    }

    private static Path getOutputDir(Path baseOutputDir, int caveLayer) {
        return ConversionOutputPaths.outputDir(baseOutputDir, caveLayer);
    }

    private static int countTotalWork(MinecraftServer server, List<DimensionRegions> allRegions) {
        int total = 0;
        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) {
                continue;
            }
            String dimPath = com.mapsyncer.util.DimensionApiHelper.getDimId(dimRegions.dimension());
            DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
            DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
            int passCount = RegionGenerationPlanner.countPasses(scanConfig, dimTypeInfo);
            total += dimRegions.regions().size() * passCount;
        }
        return total;
    }

    private static List<java.util.concurrent.Future<?>> submitConversionTasks(
            ExecutorService executor, List<RegionCoords> coordsToProcess, List<RegionCoords> allRegions,
            Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress) {

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        Set<RegionCoords> validRegions = new HashSet<>(allRegions);

        for (RegionCoords coords : coordsToProcess) {
            if (!validRegions.contains(coords)) continue;

            java.util.concurrent.Future<?> future = executor.submit(() ->
                convertRegionMultiPasses(coords, regionDir, baseOutputDir, xaeroDimName, dimPath,
                    dimTypeInfo, passes, mcaCache, genCache,
                    generationTimeSeconds, failedRegions, logProgress, "Converted")
            );
            futures.add(future);
        }

        return futures;
    }

    private static List<java.util.concurrent.Future<?>> submitNewRegionTasks(
            ExecutorService executor, List<RegionCoords> allRegions, Set<RegionCoords> processedRegions,
            Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions) {

        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (RegionCoords coords : allRegions) {
            if (processedRegions.contains(coords)) continue;

            boolean allExist = true;
            for (RegionScanPass pass : passes) {
                Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
                if (!XaeroWriter.regionFileExists(outputDir, coords.x(), coords.z())) {
                    allExist = false;
                    break;
                }
            }
            if (allExist) {
                processedCountAtomic.addAndGet(passes.size());
                skippedCount.incrementAndGet();
                LOGGER.debug("Skipped region ({}, {}): all pass outputs exist", coords.x(), coords.z());
                continue;
            }

            java.util.concurrent.Future<?> future = executor.submit(() ->
                convertRegionMultiPasses(coords, regionDir, baseOutputDir, xaeroDimName, dimPath,
                    dimTypeInfo, passes, mcaCache, genCache,
                    generationTimeSeconds, failedRegions, true, "Generated new")
            );
            futures.add(future);
        }

        return futures;
    }

    private static void convertRegionMultiPasses(
            RegionCoords coords, Path regionDir, Path baseOutputDir, String xaeroDimName, String dimPath,
            DimensionTypeInfo dimTypeInfo, List<RegionScanPass> passes,
            McaTimestampCache mcaCache, GenerationCache genCache, long generationTimeSeconds,
            ConcurrentLinkedQueue<RegionCoords> failedRegions, boolean logProgress, String logPrefix) {

        Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");
        long sourceTimestamp;
        try {
            sourceTimestamp = Files.getLastModifiedTime(mcaPath).toMillis();
        } catch (IOException e) {
            failedRegions.add(coords);
            return;
        }

        if (!com.mapsyncer.mca.McaContentProbe.hasAnyChunk(mcaPath)) {
            for (RegionScanPass pass : passes) {
                Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
                String relativePath = ConversionOutputPaths.relativePath(
                    xaeroDimName, pass.caveLayer(), coords.x(), coords.z());
                EmptyRegionSupport.purgeGeneratedArtifacts(
                    outputDir, coords.x(), coords.z(), relativePath, genCache);
            }
            skippedEmptyContentCount.incrementAndGet();
            if (logProgress) {
                processedCountAtomic.addAndGet(passes.size());
            }
            return;
        }

        List<LayerConvertedRegion> converted = RegionConverterStandalone.convertRegionMulti(
            mcaPath, coords.x(), coords.z(), dimTypeInfo, passes, BlockPropertyResolver.INSTANCE);

        if (converted.isEmpty()) {
            failedRegions.add(coords);
            return;
        }

        boolean anyWritten = false;
        boolean anyFailed = false;
        for (int i = 0; i < passes.size(); i++) {
            RegionScanPass pass = passes.get(i);
            LayerConvertedRegion layer = i < converted.size() ? converted.get(i) : null;
            Path outputDir = ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer());
            String relativePath = ConversionOutputPaths.relativePath(
                xaeroDimName, pass.caveLayer(), coords.x(), coords.z());

            ConvertedRegion single = layer == null ? null
                : new ConvertedRegion(layer.regionX(), layer.regionZ(), layer.xaeroData());

            if (EmptyRegionSupport.isEmptyConverted(single)) {
                EmptyRegionSupport.purgeGeneratedArtifacts(
                    outputDir, coords.x(), coords.z(), relativePath, genCache);
                if (logProgress) {
                    processedCountAtomic.incrementAndGet();
                }
                continue;
            }

            try {
                XaeroWriter.RegionWriteResult writeResult = XaeroWriter.writeRegionFile(outputDir, single);
                genCache.update(relativePath, generationTimeSeconds, writeResult.crc32Hash());
                anyWritten = true;
                if (logProgress) {
                    int convertedSoFar = convertedCountAtomic.incrementAndGet();
                    processedCountAtomic.incrementAndGet();
                    String layerLabel = pass.isSurfaceLayer() ? "surface" : String.valueOf(pass.caveLayer());
                    LOGGER.info("{} region ({}, {}) layer={}: {}/{}",
                        logPrefix, coords.x(), coords.z(), layerLabel, convertedSoFar, totalCount);
                }
            } catch (IOException e) {
                LOGGER.error("Failed to write region file for layer {}", pass.caveLayer(), e);
                anyFailed = true;
            }
        }

        if (!anyFailed) {
            mcaCache.updateTimestampValue(dimPath, coords.x(), coords.z(), sourceTimestamp);
        }
        if (anyFailed) {
            failedRegions.add(coords);
        } else if (!anyWritten) {
            skippedEmptyContentCount.incrementAndGet();
        }
    }

    private static void waitForCompletion(List<java.util.concurrent.Future<?>> futures, String taskName) {
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get(TimeoutConfig.TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                LOGGER.warn("{} task timeout", taskName);
            } catch (ExecutionException e) {
                LOGGER.error("{} task failed", taskName, e);
            } catch (InterruptedException e) {
                LOGGER.error("{} task interrupted", taskName, e);
                Thread.currentThread().interrupt();
            }
        }
    }

    public static ResourceKey<Level> parseDimensionId(String id, MinecraftServer server) {
        String normalized = id.toLowerCase();

        switch (normalized) {
            case "overworld", "minecraft:overworld":
                return Level.OVERWORLD;
            case "the_nether", "minecraft:the_nether":
                return Level.NETHER;
            case "the_end", "minecraft:the_end":
                return Level.END;
        }

        try {
            ResourceLocation location = new ResourceLocation(id);

            for (ServerLevel level : server.getAllLevels()) {
                ResourceLocation dimLocation = level.dimension().location();
                if (dimLocation.equals(location) ||
                    dimLocation.getPath().equals(id) ||
                    dimLocation.toString().equals(id)) {
                    return level.dimension();
                }
            }
            LOGGER.warn("Dimension not found: {}", id);
        } catch (RuntimeException e) {
            LOGGER.error("Invalid dimension id format '{}'", id, e);
        }

        return null;
    }

    public record IncrementalScanSnapshot(
            String dimPath,
            String xaeroDimName,
            Path regionDir,
            Path baseOutputDir,
            DimensionTypeInfo dimTypeInfo,
            List<RegionScanPass> passes
    ) {}

    public static List<IncrementalScanSnapshot> buildIncrementalScanSnapshots(MinecraftServer server) {
        List<IncrementalScanSnapshot> snapshots = new ArrayList<>();
        Set<ResourceKey<Level>> seenDimensions = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            if (!seenDimensions.add(dimKey)) {
                continue;
            }

            String fullDimId = dimKey.location().toString();
            String dimPath = com.mapsyncer.util.DimensionApiHelper.getDimId(dimKey);

            DimensionScanConfig scanConfig = PlatformManager.getPlatform().getConfigForDimension(dimPath);
            String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

            Path regionDir = RegionScanner.getRegionDir(level);
            if (regionDir == null) {
                continue;
            }

            Path baseOutputDir = getDimensionCacheDir(xaeroDimName);
            DimensionTypeInfo dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
            List<RegionScanPass> passes = RegionGenerationPlanner.plan(scanConfig, dimTypeInfo);

            snapshots.add(new IncrementalScanSnapshot(
                    dimPath, xaeroDimName, regionDir, baseOutputDir, dimTypeInfo, passes));
        }

        return snapshots;
    }

    public static void performIncrementalScan(MinecraftServer server) {
        List<IncrementalScanSnapshot> snapshots;
        try {
            snapshots = server.submit(() -> buildIncrementalScanSnapshots(server)).get(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Incremental scan snapshot interrupted");
            return;
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.error("Timed out building incremental scan snapshot on server thread");
            return;
        } catch (ExecutionException e) {
            LOGGER.error("Failed to build incremental scan snapshot on server thread", e.getCause());
            return;
        }
        performIncrementalScan(snapshots);
    }

    public static void performIncrementalScan(List<IncrementalScanSnapshot> snapshots) {
        if (ServerSyncHandlerLogic.isSyncActive()) {
            LOGGER.debug("Map sync in progress, deferring incremental scan");
            return;
        }
        if (!isRunning.compareAndSet(false, true)) {
            LOGGER.debug("Conversion already in progress, skipping incremental scan");
            return;
        }

        try {
        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(getCacheDir());
        int totalUpdated = 0;
        totalCount = 0;
        long generationTimeSeconds = System.currentTimeMillis() / 1000;
        ConcurrentLinkedQueue<RegionCoords> failedRegions = new ConcurrentLinkedQueue<>();
        ExecutorService executor = getOrCreateExecutor();

        for (IncrementalScanSnapshot snapshot : snapshots) {
            String dimPath = snapshot.dimPath();
            Path regionDir = snapshot.regionDir();
            Path baseOutputDir = snapshot.baseOutputDir();
            String xaeroDimName = snapshot.xaeroDimName();
            List<RegionScanPass> passes = snapshot.passes();
            DimensionTypeInfo dimTypeInfo = snapshot.dimTypeInfo();

            java.util.List<RegionCoords> needsUpdate = mcaCache.scanAndUpdate(dimPath, regionDir);

            if (needsUpdate.isEmpty()) {
                LOGGER.debug("No updates needed for dimension {}", dimPath);
                continue;
            }

            LOGGER.info("Dimension {}: {} regions need incremental update (passes={})",
                dimPath, needsUpdate.size(), passes.size());

            try {
                for (RegionScanPass pass : passes) {
                    Files.createDirectories(ConversionOutputPaths.outputDir(baseOutputDir, pass.caveLayer()));
                }
            } catch (IOException e) {
                LOGGER.error("Failed to create output directories: {}", baseOutputDir, e);
                continue;
            }

            totalCount += needsUpdate.size() * passes.size();
            int failuresBefore = failedRegions.size();
            List<java.util.concurrent.Future<?>> futures = submitConversionTasks(
                executor, needsUpdate, needsUpdate, regionDir, baseOutputDir, xaeroDimName, dimPath,
                dimTypeInfo, passes, mcaCache, genCache,
                generationTimeSeconds, failedRegions, false);
            waitForCompletion(futures, "Incremental update");
            totalUpdated += needsUpdate.size() - (failedRegions.size() - failuresBefore);
        }

        if (totalUpdated > 0) {
            LOGGER.info("Incremental scan completed: {} regions updated", totalUpdated);
            mcaCache.saveCache();
            genCache.save();
        }
        } finally {
            isRunning.set(false);
        }
    }

    public static boolean isRunning() { return isRunning.get(); }

    public static int getProcessedCount() { return processedCount; }

    public static int getTotalCount() { return totalCount; }

    public static int getUpdatedCount() { return convertedCountAtomic.get(); }

    public static int getSkippedCount() { return skippedCount.get(); }

    public static String getStatus() { return currentStatus; }

    public static ResourceKey<Level> getCurrentDimension() { return currentDimension; }

    public static List<String> getCompletedDimensions() { return completedDimensions; }

    public record DimensionCacheStats(String dimension, int regionCount, long sizeBytes) {

        public double sizeMB() {
            return sizeBytes / (1024.0 * 1024.0);
        }
    }

    public static List<DimensionCacheStats> getCacheStats() {
        List<DimensionCacheStats> stats = new ArrayList<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();

        if (!Files.exists(getCacheDir())) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(getCacheDir())) {
            for (Path dimDir : dimDirs) {
                if (!dimDir.toFile().isDirectory()) continue;

                String dimName = dimDir.getFileName().toString();
                String friendlyName = dimMapping.getFriendlyName(dimName);

                int regionCount = 0;
                long totalSize = 0;

                try (Stream<Path> files = Files.walk(dimDir)) {
                    List<Path> zipFiles = files
                            .filter(p -> ServerMapCache.snapshot().regionKey(p) != null)
                            .toList();

                    regionCount = zipFiles.size();
                    totalSize = zipFiles.stream()
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }

                if (regionCount > 0) {
                    stats.add(new DimensionCacheStats(friendlyName, regionCount, totalSize));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get cache stats", e);
        }

        return stats;
    }
}
