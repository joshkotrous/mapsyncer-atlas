package com.mapsyncer.client;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class ClientHashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHashManager.class);

    private static volatile ForkJoinPool sharedPool;

    private static volatile int currentPoolThreads;

    private static final AtomicInteger poolUsers = new AtomicInteger(0);

    private static final int DEFAULT_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    private static int getConfiguredThreads() {
        try {
            return PlatformManager.getPlatform().getClientHashThreads();
        } catch (Exception e) {

            LOGGER.debug("ClientConfig not initialized, using default threads: {}", DEFAULT_THREADS);
            return DEFAULT_THREADS;
        }
    }

    private static ForkJoinPool getSharedPool() {
        int configuredThreads = getConfiguredThreads();

        if (sharedPool == null || sharedPool.isShutdown() || currentPoolThreads != configuredThreads) {
            synchronized (ClientHashManager.class) {

                if (sharedPool == null || sharedPool.isShutdown() || currentPoolThreads != configuredThreads) {

                    if (sharedPool != null && !sharedPool.isShutdown()) {
                        sharedPool.shutdown();
                        try {
                            if (!sharedPool.awaitTermination(5, TimeUnit.SECONDS)) {
                                sharedPool.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            sharedPool.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                        LOGGER.info("Shutting down old ForkJoinPool (threads={})", currentPoolThreads);
                    }

                    sharedPool = new ForkJoinPool(configuredThreads);
                    currentPoolThreads = configuredThreads;
                    LOGGER.info("Created new ForkJoinPool with {} threads (configured via client settings)", configuredThreads);
                }
            }
        }

        return sharedPool;
    }

    public static boolean isComputingMeta() {
        return poolUsers.get() > 0;
    }

    public static Map<String, ClientMeta> computeMetaForSync(Path mapDir) {
        poolUsers.incrementAndGet();
        try {
            Path root = findServerDir(mapDir);
            ClientTimestampCache cache = root == null ? null : ClientTimestampCache.getInstance(root);
            MetaScanResult result = computeMetaForSyncWorker(mapDir, false, root, cache);
            if (!result.isSuccess()) {
                throw new IllegalStateException("Hash scan failed: " + result.failureReason());
            }
            return result.meta();
        } finally {
            poolUsers.decrementAndGet();
        }
    }

    public static void computeMetaForSyncAsync(Path mapDir, Consumer<MetaScanResult> onComplete) {
        final Path root = findServerDir(mapDir);
        final ClientTimestampCache cache = root == null ? null : ClientTimestampCache.getInstance(root);
        final int generation = ClientSyncSession.get().generation();
        poolUsers.incrementAndGet();
        getSharedPool().submit(() -> {
            try {
                onComplete.accept(computeMetaForSyncWorker(mapDir, true, root, cache));
            } catch (Exception e) {
                LOGGER.error("Failed to compute hashes asynchronously", e);
                onComplete.accept(MetaScanResult.failure("async_error", 0));
            } finally {
                if (ClientSyncSession.get().isCurrent(generation)) SyncProgressTracker.completeHashScan();
                poolUsers.decrementAndGet();
            }
        });
    }

    private static MetaScanResult computeMetaForSyncWorker(Path mapDir, boolean reportProgress,
            Path serverDir, ClientTimestampCache tsCache) {
        Map<String, ClientMeta> metaMap = new ConcurrentHashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return MetaScanResult.ok(metaMap);
        }

        if (serverDir == null) {
            LOGGER.error("Could not resolve Multiplayer server directory from {}", mapDir);
            return MetaScanResult.failure("server_dir", 0);
        }

        // Load cached timestamps from previous sync

        Map<String, TimestampHashEntry> cachedTimestamps = tsCache.getAll();
        LOGGER.info("Loaded {} cached timestamps from previous sync", cachedTimestamps.size());

        // Collect all zip files from the specified directory (not entire server)
        java.util.List<Path> zipFiles;
        try (Stream<Path> walk = Files.walk(mapDir)) {
            zipFiles = walk.filter(p -> p.toString().endsWith(".zip"))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk map directory {}", mapDir, e);
            return MetaScanResult.failure("walk_error", 0);
        }

        LOGGER.info("Computing hashes for {} region files in {} (parallel threads={})", zipFiles.size(), mapDir, currentPoolThreads);

        if (reportProgress && !zipFiles.isEmpty()) {
            SyncProgressTracker.startHashScan(zipFiles.size());
        }

        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failedFiles = new AtomicInteger();
        AtomicInteger lastReportedPercent = new AtomicInteger(-1);
        int totalFiles = zipFiles.size();

        ForkJoinPool pool = getSharedPool();
        try {
            pool.submit(() ->
                    zipFiles.parallelStream()
                            .forEach(zipPath -> {
                                try {
                                    String fileName = zipPath.getFileName().toString();
                                    if (!fileName.endsWith(".zip")) return;

                                    String relativePath = buildRelativePath(zipPath, serverDir);
                                    TimestampHashEntry cached = cachedTimestamps.get(relativePath);
                                    if (cached == null) {

                                        return;
                                    }
                                    String hash = resolveSyncHash(zipPath, cached);

                                    long timestampSeconds = resolveSyncTimestamp(zipPath, cached);
                                    LOGGER.debug("Region {}: ts={}s, hash={} (cache-first)",
                                            relativePath, timestampSeconds, hash);

                                    metaMap.put(relativePath, new ClientMeta(timestampSeconds, hash));

                                } catch (Exception e) {
                                    failedFiles.incrementAndGet();
                                    LOGGER.warn("Failed to hash region file: {}", zipPath, e);
                                } finally {
                                    if (reportProgress && totalFiles > 0) {
                                        int done = processed.incrementAndGet();
                                        int percent = (done * 100) / totalFiles;
                                        int prev = lastReportedPercent.get();
                                        if (done == totalFiles || percent >= prev + 10) {
                                            lastReportedPercent.set(percent);
                                            SyncProgressTracker.updateHashScan(done, totalFiles);
                                        }
                                    }
                                }
                            })
            ).join();
        } catch (Exception e) {
            LOGGER.error("Failed to compute hashes in parallel", e);
            return MetaScanResult.failure("parallel_error", failedFiles.get());
        }

        if (failedFiles.get() > 0) {
            LOGGER.error("Hash scan completed with {} failed file(s) out of {}", failedFiles.get(), totalFiles);
            return MetaScanResult.failure("partial_error", failedFiles.get());
        }

        addMissingCacheEntries(metaMap, cachedTimestamps, collectDimPrefixes(mapDir, serverDir));

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return MetaScanResult.ok(metaMap);
    }

    private static String resolveSyncHash(Path zipPath, TimestampHashEntry cached) {
        if (zipPath == null || !Files.exists(zipPath) || !HashUtils.isValidRegionZip(zipPath)) {
            if (cached != null) {
                LOGGER.warn("Region {} missing or invalid on disk, will request re-sync",
                        zipPath != null ? zipPath.getFileName() : "unknown");
            }
            return HashUtils.DEFAULT_HASH;
        }
        if (cached != null && HashUtils.isValidHash(cached.hash())) {
            return cached.hash();
        }
        return HashUtils.computeFileHash(zipPath);
    }

    private static long resolveSyncTimestamp(Path zipPath, TimestampHashEntry cached) {
        long fileTs = getFileModificationTime(zipPath) / 1000;
        if (cached != null) {
            return Math.max(cached.timestampSeconds(), fileTs);
        }
        return fileTs;
    }

    private static void addMissingCacheEntries(Map<String, ClientMeta> metaMap,
            Map<String, TimestampHashEntry> cachedTimestamps, Set<String> dimPrefixes) {
        if (dimPrefixes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, TimestampHashEntry> entry : cachedTimestamps.entrySet()) {
            String key = entry.getKey();
            if (metaMap.containsKey(key)) {
                continue;
            }
            for (String prefix : dimPrefixes) {
                if (key.startsWith(prefix)) {
                    metaMap.put(key, new ClientMeta(entry.getValue().timestampSeconds(), HashUtils.DEFAULT_HASH));
                    LOGGER.warn("Region {} in cache but file missing, will request re-sync", key);
                    break;
                }
            }
        }
    }

    private static Set<String> collectDimPrefixes(Path mapDir, Path serverDir) {
        Set<String> prefixes = new java.util.HashSet<>();
        Path root = serverDir.toAbsolutePath().normalize();
        Path scan = mapDir.toAbsolutePath().normalize();
        if (scan.startsWith(root) && !scan.equals(root)) {
            prefixes.add(root.relativize(scan).getName(0).toString() + "/");
        }
        Path current = mapDir;
        while (current != null && !current.equals(serverDir)) {
            String name = current.getFileName() != null ? current.getFileName().toString() : "";
            if (name.startsWith("mw$")) {
                Path dimDir = current.getParent();
                if (dimDir != null) {
                    prefixes.add(dimDir.getFileName().toString() + "/");
                }
                break;
            }
            current = current.getParent();
        }
        if (prefixes.isEmpty() && mapDir.equals(serverDir)) {
            try (Stream<Path> dirs = Files.list(serverDir)) {
                dirs.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> !n.startsWith("_"))
                        .forEach(n -> prefixes.add(n + "/"));
            } catch (IOException e) {
                LOGGER.warn("Failed to list dimension dirs under {}", serverDir, e);
            }
        }
        return prefixes;
    }

    private static Path findServerDir(Path mapDir) {
        Path activeRoot = com.mapsyncer.platform.PlatformManager.getPlatform().getClientXaeroWorldMapDir();
        if (activeRoot != null && mapDir.toAbsolutePath().normalize().startsWith(activeRoot.toAbsolutePath().normalize())) {
            return activeRoot;
        }
        Path current = mapDir;

        // Walk up the directory tree to find Multiplayer_<server>
        while (current != null) {
            String name = current.getFileName() != null ? current.getFileName().toString() : "";
            if (name.startsWith("Multiplayer_")) {
                return current;
            }
            current = current.getParent();
        }

        return null;
    }

    private static long getFileModificationTime(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime time = attrs.lastModifiedTime();
            return time.toMillis();
        } catch (IOException e) {
            LOGGER.error("Failed to get modification time for {}", path, e);
            return 0;
        }
    }

    private static String buildRelativePath(Path zipPath, Path serverDir) {
        // Get relative path from server directory
        String relative = serverDir.relativize(zipPath).toString();
        relative = relative.replace("\\", "/");

        // Remove .zip extension
        if (relative.endsWith(".zip")) {
            relative = relative.substring(0, relative.length() - 4);
        }

        // Parse path components

        String[] parts = relative.split("/");
        if (parts.length < 3) {
            LOGGER.warn("Unexpected path format: {}", relative);
            return relative;
        }

        String dirName = parts[0];
        String regionCoords = parts[parts.length - 1];  // Last part is regionX_regionZ

        int caveLayer = Integer.MAX_VALUE;
        boolean hasCaves = false;
        for (int i = 1; i < parts.length - 2; i++) {
            if (parts[i].equals("caves") && i + 1 < parts.length - 1) {
                hasCaves = true;
                try {
                    caveLayer = Integer.parseInt(parts[i + 1]);
                    LOGGER.debug("Found caves layer {} at index {} in path: {}", caveLayer, i, relative);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid cave layer at index {} in path: {}", i + 1, relative);
                }
                break;
            }
        }

        if (hasCaves) {
            LOGGER.debug("Path has caves layer: {}", relative);
        }

        String xaeroDim = ensureCorrectXaeroFormat(dirName, serverDir);

        // Build path in server format (matches GenerationCache key format)
        String serverPath;
        if (caveLayer == Integer.MAX_VALUE) {

            serverPath = xaeroDim + "/" + regionCoords;
        } else {

            serverPath = xaeroDim + "/caves/" + caveLayer + "/" + regionCoords;
        }

        LOGGER.debug("buildRelativePath: {} -> {} (dirName={}, xaeroDim={})", relative, serverPath, dirName, xaeroDim);
        return serverPath;
    }

    private static String ensureCorrectXaeroFormat(String dirName, Path serverDir) {

        if (dirName.equals("null") || dirName.equals("DIM-1") || dirName.equals("DIM1")) {
            return dirName;
        }

        if (dirName.contains("$")) {
            return dirName;
        }

        if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
            return dirName;
        }

        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        for (String cacheKey : tsCache.getAll().keySet()) {
            int slashIndex = cacheKey.indexOf('/');
            if (slashIndex > 0) {
                String cachedDim = cacheKey.substring(0, slashIndex);

                if (cachedDim.contains("$")) {
                    String pathPart = cachedDim.substring(cachedDim.indexOf('$') + 1);
                    if (pathPart.equals(dirName)) {
                        LOGGER.info("Found correct xaeroDim from cache: {} -> {}", dirName, cachedDim);
                        return cachedDim;
                    }
                }
            }
        }

        String converted = DimensionPathMapping.getInstance().toXaeroDimension(dirName);
        if (!converted.equals(dirName)) {
            LOGGER.info("Converted xaeroDim via mapping: {} -> {}", dirName, converted);
            return converted;
        }

        LOGGER.warn("Could not convert dirName '{}' to correct Xaero format, sync may fail", dirName);
        return dirName;
    }

    public static void shutdown() {
        synchronized (ClientHashManager.class) {
            if (poolUsers.get() > 0) {
                LOGGER.debug("Deferring ForkJoinPool shutdown, {} active hash computations", poolUsers.get());
                return;
            }
            ForkJoinPool pool = sharedPool;
            if (pool != null && !pool.isShutdown()) {
                pool.shutdown();
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                sharedPool = null;
                LOGGER.debug("ClientHashManager shared ForkJoinPool shutdown (threads={})", currentPoolThreads);
            }
        }
    }
}
