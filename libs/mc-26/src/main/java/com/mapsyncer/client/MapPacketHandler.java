package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.MapFragmentAssembler;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.client.ClientSyncSession;
import com.mapsyncer.sync.SyncOutcome;
import com.mapsyncer.sync.SyncPhase;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMessageHelper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class MapPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketHandler.class);

    private static final ClientSyncSession session = ClientSyncSession.get();

    public static boolean isSyncInProgress() {
        return session.phase() == SyncPhase.RECEIVING
                || ClientSyncWriteQueue.hasPendingWrites()
                || pendingWriteApplyCallbacks.get() > 0
                || SyncProgressTracker.isTracking();
    }

    public static boolean isBackgroundReloadPending() {
        return session.phase() == SyncPhase.DRAINING_RELOAD || !pendingRegionLoads.isEmpty();
    }

    private static volatile boolean serverInstalled = false;

    private static volatile String serverVersion = "";

    private static volatile Path lastMwDir = null;

    private static final long SYNC_COMPLETE_DEBOUNCE_MS = 500;

    private static volatile long lastSyncCompleteTs = 0;

    private static volatile int lastProgressProcessed = -1;

    private static volatile int lastProgressTotal = -1;

    private static volatile long lastProgressTime = 0;

    private static final long PROGRESS_DEDUP_MS = 100;

    private static final Set<XaeroMapDataHandler.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();
    private static final Set<String> writtenRegionKeys = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger failedWrites = new AtomicInteger();
    private static String lastReloadDimension = "";

    private static boolean isCurrentDimension(String dimension) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && DimensionPathMapping.getInstance().toXaeroDimension(
                com.mapsyncer.util.DimensionApiHelper.getDimId(mc.level.dimension())).equals(dimension);
    }

    private static final Set<XaeroMapDataHandler.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    private static final ConcurrentLinkedQueue<PendingRegionLoad> pendingRegionLoads = new ConcurrentLinkedQueue<>();

    private record PendingRegionLoad(int regionX, int regionZ, int caveLayer, String dimension) {}

    private static final MapFragmentAssembler partAssembler = new MapFragmentAssembler();

    private static volatile boolean syncFinishRequested = false;
    private static volatile int syncFinishGeneration = -1;
    private static volatile SyncOutcome syncFinishOutcome = SyncOutcome.NONE;
    private static volatile ClientTimestampCache syncFinishTsCache = null;

    private static volatile AtomicInteger pendingWriteApplyCallbacks = new AtomicInteger(0);

    private static String partKey(ChunkMapData chunk) {
        return chunk.regionX + "," + chunk.regionZ + "," + chunk.dimension + "," + chunk.caveLayer;
    }

    private static ChunkMapData assemblePart(ChunkMapData chunk) {
        return partAssembler.accept(chunk);
    }

    private static void cleanStaleParts() {
        partAssembler.cleanStale();
    }

    public static boolean isSyncStale() {
        return session.isStale();
    }

    public static void clearSyncData() {
        session.invalidate();
        SyncProgressTracker.cancelTracking();
        clearReceivedChunks();
        loadedRegions.clear();
        partAssembler.clear();
        pendingRegionLoads.clear();
        lastMwDir = null;
        syncFinishRequested = false;
        syncFinishGeneration = -1;
        syncFinishOutcome = SyncOutcome.NONE;
        syncFinishTsCache = null;
        pendingWriteApplyCallbacks = new AtomicInteger(0);
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    public static void clearReceivedChunks() {
        if (updatedRegionCoords != null) {
            updatedRegionCoords.clear();
        writtenRegionKeys.clear();
        failedWrites.set(0);
        }
    }

    public static void onDisconnect() {
        MapSyncerCommandLogic.resetSyncRetry();
        ClientLifecycleBridge.onClientDisconnect();
    }

    public static void registerHandlers() {
        var handler = NetworkManager.getHandler();

        handler.registerSyncResponseHandler(MapPacketHandler::handleSyncResponse);

        handler.registerSyncProgressHandler(MapPacketHandler::handleProgressUpdate);

        SyncProgressTracker.setTimeoutCallback(MapSyncerCommandLogic::retryLastSyncRequest);

        handler.registerServerInstalledHandler((payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                serverInstalled = true;
                serverVersion = payload.version();
                if (XaeroMapIntegrator.isIntegratedServerHost()) {
                    AutoSyncManager.cancel();
                    AutoSyncManager.configureFromServer(com.mapsyncer.platform.UpdateMode.DISABLED, 0, 0);
                    LOGGER.info("Integrated-server host can generate maps and configure MapSyncer; self-sync is disabled");
                    return;
                }

                if (payload.serverName() != null && !payload.serverName().isEmpty()) {
                    ClientSyncSession.get().setServerName(payload.serverName());
                    LOGGER.info("Server identity name: {}", payload.serverName());
                } else {
                    LOGGER.info("Server did not configure serverName, will use automatic directory matching");
                }
                int intervalMinutes = payload.autoSyncIntervalMinutes();
                AutoSyncManager.configureFromServer(
                        payload.updateMode(), intervalMinutes, payload.incrementalUpdateIntervalTicks());
                LOGGER.info("Server has MapSyncer installed, version: {}, mode={}, intervalMinutes={}, joinAutoSync={}",
                        serverVersion, payload.updateMode(), intervalMinutes, intervalMinutes > 0);

                Object[] statusKey = AutoSyncManager.getStatusKey(intervalMinutes);
                String key = (String) statusKey[0];
                if (statusKey.length > 1) {
                    Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.prefix().append(ChatUtils.desc(key, statusKey[1])));
                } else {
                    Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.prefix().append(ChatUtils.desc(key)));
                }

                boolean shouldJoinSync = AutoSyncManager.shouldAutoSyncOnJoin(
                        payload.lastGenerationTimestamp(), intervalMinutes);
                LOGGER.info("shouldAutoSyncOnJoin result: {} (serverGenTime={}, intervalMinutes={})",
                        shouldJoinSync, payload.lastGenerationTimestamp(), intervalMinutes);

                handleMultiEntryCacheReuseAsync(
                        payload.serverName() != null ? payload.serverName() : "",
                        () -> {
                            Minecraft.getInstance().execute(() -> {
                                if (shouldJoinSync) {
                                    if (Minecraft.getInstance().player != null
                                            && !MapPacketHandler.isSyncInProgress()) {
                                        Minecraft.getInstance().player.sendSystemMessage(
                                            ChatUtils.prefix().append(ChatUtils.desc("mapsyncer.autosync.start")));
                                        AutoSyncManager.markStarted();
                                        MapSyncerCommandLogic.executeSyncAll(true);
                                    }
                                }
                            });
                        });

                AutoSyncManager.startTickPeriodicSync(() ->
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null
                                    && !MapPacketHandler.isSyncInProgress()) {
                                LOGGER.debug("TICK periodic auto-sync: requesting sync");
                                AutoSyncManager.markPeriodicSync();
                                MapSyncerCommandLogic.executeSyncAll(true);
                            }
                        }));
                } catch (Exception e) {
                    LOGGER.error("Error processing ServerInstalledPayload", e);
                }
            });
        });

    }

    private static void handleMultiEntryCacheReuseAsync(String serverName, Runnable onComplete) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Path gameDir = mc.gameDirectory.toPath();
            Path worldMapDir = XaeroMapIntegrator.getWorldMapDir(gameDir);
            ClientPacketListener connection = mc.getConnection();
            if (worldMapDir == null || !worldMapDir.toFile().exists() || connection == null) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            ServerData serverData = connection.getServerData();
            if (serverData == null) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            String currentIP = XaeroMapIntegrator.cleanServerIP(serverData.ip);
            if (currentIP == null || currentIP.isEmpty()) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }

            final String serverNameFinal = serverName;
            final Path worldMapDirFinal = worldMapDir;
            final String currentIPFinal = currentIP;

            Thread worker = new Thread(() -> {
                try {
                    handleMultiEntryCacheReuse(worldMapDirFinal, currentIPFinal, serverNameFinal);
                } catch (Exception e) {
                    LOGGER.warn("Background cache reuse failed: {}", e.getMessage());
                } finally {
                    if (onComplete != null) {
                        try {
                            Minecraft.getInstance().execute(onComplete);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to schedule onComplete: {}", e.getMessage());
                        }
                    }
                }
            }, "mapsyncer-cache-reuse");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            LOGGER.warn("Failed to start background cache reuse: {}", e.getMessage());
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    private static void handleMultiEntryCacheReuse(Path worldMapDir, String currentIP, String serverName) {
        try {
            Path currentIPDir = worldMapDir.resolve("Multiplayer_" + currentIP);

            boolean hasServerName = serverName != null && !serverName.isEmpty();

            Path sourceDir = null;
            if (hasServerName) {
                sourceDir = findSourceByServerName(worldMapDir, currentIP, serverName);
            }

            if (sourceDir == null) {
                sourceDir = findBestMapSource(worldMapDir, currentIP);
                if (sourceDir != null) {
                    LOGGER.info("Auto-matched previous map directory: {} (no serverName configured)",
                            sourceDir);
                }
            }

            if (sourceDir == null) {
                LOGGER.info("No previous IP directory found for serverName={}, skip cache reuse", serverName);

                writeServerNameIdentifier(currentIPDir, serverName);
                return;
            }

            long currentCount = currentIPDir.toFile().exists() ? countMapFiles(currentIPDir) : 0L;
            long sourceCount = countMapFiles(sourceDir);

            if (currentCount >= sourceCount && currentCount > 0) {

                LOGGER.debug("Current IP directory already has {} map files (source has {}), skip cache reuse",
                        currentCount, sourceCount);
                writeServerNameIdentifier(currentIPDir, serverName);
                return;
            }

            LOGGER.info("Renaming map directory: {} ({} files) → {} (current has {} files)",
                    sourceDir, sourceCount, currentIPDir, currentCount);

            if (currentIPDir.toFile().exists()) {

                try {
                    deleteDirectory(currentIPDir);
                    LOGGER.debug("Removed empty shell directory: {}", currentIPDir);
                } catch (Exception e) {
                    LOGGER.warn("Failed to remove empty shell directory {}: {}", currentIPDir, e.getMessage());
                    return;
                }
            }

            try {
                Files.move(sourceDir, currentIPDir);
                LOGGER.info("Directory renamed successfully: {} → {}", sourceDir, currentIPDir);
            } catch (Exception e) {
                LOGGER.error("Failed to rename directory {} → {}: {}", sourceDir, currentIPDir, e.getMessage());
                return;
            }

            writeServerNameIdentifier(currentIPDir, serverName);

        } catch (Exception e) {
            LOGGER.warn("Failed to reuse cache for serverName={}: {}", serverName, e.getMessage());
        }
    }

    private static Path findSourceByServerName(Path worldMapDir, String currentIP, String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return null;
        }
        try (var stream = Files.list(worldMapDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                    .filter(p -> !p.getFileName().toString().equals("Multiplayer_" + currentIP))
                    .filter(Files::isDirectory)
                    .filter(p -> {

                        Path nameFile = p.resolve("mapsyncer_server_name.txt");
                        if (nameFile.toFile().exists()) {
                            try {
                                String name = new String(Files.readAllBytes(nameFile), StandardCharsets.UTF_8).trim();
                                return serverName.equals(name);
                            } catch (Exception e) {
                                return false;
                            }
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.debug("findSourceByServerName failed: {}", e.getMessage());
            return null;
        }
    }

    private static Path findBestMapSource(Path worldMapDir, String currentIP) {
        Path bestDir = null;
        long bestScore = -1;
        try (var stream = Files.list(worldMapDir)) {
            var dirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                    .filter(p -> !p.getFileName().toString().equals("Multiplayer_" + currentIP))
                    .filter(Files::isDirectory)
                    .toList();
            for (Path dir : dirs) {

                Path nullDir = dir.resolve("null");
                if (!nullDir.toFile().exists()) {
                    continue;
                }
                long score = countMapFiles(dir);
                if (score > bestScore) {
                    bestScore = score;
                    bestDir = dir;
                }
            }
        } catch (IOException e) {
            LOGGER.debug("findBestMapSource failed: {}", e.getMessage());
        }
        if (bestDir != null && bestScore > 0) {
            LOGGER.debug("Best map source: {} with {} map files", bestDir, bestScore);
            return bestDir;
        }
        return null;
    }

    private static long countMapFiles(Path dir) {
        long[] count = {0};
        try {

            Path nullDir = dir.resolve("null");
            if (!nullDir.toFile().exists()) {
                return 0;
            }
            Files.walkFileTree(nullDir, new SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".zip") && !name.endsWith(".zip.temp")) {
                        count[0]++;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.debug("countMapFiles failed for {}: {}", dir, e.getMessage());
        }
        return count[0];
    }

    private static void writeServerNameIdentifier(Path serverDir, String serverName) {
        try {
            Files.createDirectories(serverDir);
            Path nameFile = serverDir.resolve("mapsyncer_server_name.txt");
            Files.write(nameFile, serverName.getBytes(StandardCharsets.UTF_8));
            LOGGER.debug("Wrote serverName identifier to {}", nameFile);
        } catch (Exception e) {
            LOGGER.warn("Failed to write serverName identifier: {}", e.getMessage());
        }
    }

    private static long copyDirectoryWithCrc32Check(Path source, Path target) throws IOException {
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        final long[] copied = {0};

        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path targetDir = target.resolve(relative);
                Files.createDirectories(targetDir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path targetFile = target.resolve(relative);

                if (file.getFileName().toString().equals("mapsyncer_server_name.txt")) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                if (targetFile.toFile().exists()) {
                    String srcHash = computeCrc32(file, crc32);
                    String tgtHash = computeCrc32(targetFile, crc32);
                    if (srcHash.equals(tgtHash)) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                }

                Files.copy(file, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied[0]++;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });

        return copied[0];
    }

    private static String computeCrc32(Path file, java.util.zip.CRC32 crc32) throws IOException {
        crc32.reset();
        byte[] data = Files.readAllBytes(file);
        crc32.update(data, 0, data.length);
        return Long.toHexString(crc32.getValue());
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!dir.toFile().exists()) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    public static boolean isServerInstalled() {
        return serverInstalled;
    }

    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
        AutoSyncManager.resetServerPolicy();
    }

    private static void handleSyncResponse(SyncResponsePayload payload, PayloadContext context) {
        final int generationAtEnqueue = session.generation();
        context.enqueueWork(() -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync response after disconnect/clear");
                return;
            }

            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();
            SyncOutcome serverOutcome = SyncOutcome.fromServerStatus(status);

            LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            boolean receiving = session.phase() == SyncPhase.RECEIVING;

            if (("ok".equals(status) || "partial".equals(status)) && !payload.isComplete() && !receiving) {
                long elapsed = System.currentTimeMillis() - lastSyncCompleteTs;
                if (elapsed < SYNC_COMPLETE_DEBOUNCE_MS) {
                    LOGGER.debug("Debouncing duplicate sync packet ({}ms after complete)", elapsed);
                    return;
                }
            }

            if (payload.isComplete() && session.phase() == SyncPhase.IDLE) {
                long elapsed = System.currentTimeMillis() - lastSyncCompleteTs;
                if (elapsed < SYNC_COMPLETE_DEBOUNCE_MS) {
                    LOGGER.debug("Debouncing duplicate completion packet ({}ms after complete)", elapsed);
                    return;
                }
            }

            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                    ? ClientTimestampCache.getInstance(serverDir) : null;

            if (!serverInstalled) {
                serverInstalled = true;
                LOGGER.info("Server confirmed (SyncResponse received), MapSyncer detected");
            }
            SyncProgressTracker.onServerResponded();

            if (serverOutcome == SyncOutcome.HARD_FAIL) {
                LOGGER.info("Server returned error status: {}, aborting sync", status);
                session.setOutcome(SyncOutcome.HARD_FAIL);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.cancelTracking();
                if (tsCache != null) {
                    tsCache.clearSyncState();
                }
                return;
            }

            if (serverOutcome == SyncOutcome.SILENT_SKIP) {
                LOGGER.info("Map is up-to-date, no sync needed");
                session.setOutcome(SyncOutcome.SILENT_SKIP);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.finishUptodate();
                MapSyncerCommandLogic.resetSyncRetry();
                finishJoinAutoSyncIfActive();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                return;
            }

            if (isSyncStale()) {
                session.setOutcome(SyncOutcome.HARD_FAIL);
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
                }
                return;
            }

            if (session.phase() == SyncPhase.IDLE || session.phase() == SyncPhase.DRAINING_RELOAD) {
                session.beginReceiving();
                RegionPipelineTracker.beginSession();
                LOGGER.info("Starting sync (streaming mode)");
                if (!initializeReflectionCache()) {
                    session.markReflectionFailed();
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.sendSystemMessage(
                                ChatUtils.error("mapsyncer.sync.reflection_failed"));
                    }
                }
            }

            Minecraft mc = Minecraft.getInstance();
            cleanStaleParts();
            AtomicInteger batchPending = new AtomicInteger();
            AtomicInteger submittedCount = new AtomicInteger();

            for (ChunkMapData chunk : chunks) {
                ChunkMapData assembled = assemblePart(chunk);
                if (assembled == null) {
                    continue;
                }

                if (serverDir == null) {
                    LOGGER.error("Cannot obtain the server directory; skipping region ({}, {})",
                            assembled.regionX, assembled.regionZ);
                    continue;
                }

                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    assembled.regionX, assembled.regionZ, assembled.caveLayer);
                boolean shouldProcess = isCurrentDimension(assembled.dimension);
                Set<XaeroMapDataHandler.RegionCoord> viewRegionsForLayer =
                    XaeroMapIntegrator.getViewDistanceRegions(assembled.caveLayer);
                boolean inViewDistance = viewRegionsForLayer.contains(coord);

                submittedCount.incrementAndGet();
                batchPending.incrementAndGet();
                final int gen = generationAtEnqueue;
                final ClientTimestampCache batchTsCache = tsCache;

                RegionPipelineTracker.onPacketReceived(
                        assembled.regionX, assembled.regionZ, assembled.caveLayer, assembled.data.length);
                RegionPipelineTracker.onWriteSubmitted(
                        assembled.regionX, assembled.regionZ, assembled.caveLayer);

                final AtomicInteger applyCounter = pendingWriteApplyCallbacks;
                applyCounter.incrementAndGet();
                ClientSyncWriteQueue.submit(assembled, serverDir, serverWorldId, tsCache, mc.hasSingleplayerServer(), com.mapsyncer.platform.XaeroReflectionHelper.getSelectedMultiworld(assembled.dimension), writeResult -> {
                    mc.execute(() -> {
                        try {
                            if (!session.isCurrent(gen)) {
                                return;
                            }

                            if (writeResult == null) {
                                failedWrites.incrementAndGet();
                                LOGGER.error("Failed to write region ({}, {}); skipping load ({} bytes)",
                                        assembled.regionX, assembled.regionZ, assembled.data.length);
                                RegionPipelineTracker.onWriteComplete(
                                        assembled.regionX, assembled.regionZ, assembled.caveLayer, false);
                                if (batchTsCache != null) {
                                    batchTsCache.remove(
                                            XaeroMapDataHandler.buildRelativePathForCache(assembled));
                                }
                            } else {
                                lastMwDir = writeResult.mwDir();
                                writtenRegionKeys.add(partKey(assembled));
                                if (isCurrentDimension(assembled.dimension)) updatedRegionCoords.add(coord);

                                if (shouldProcess && isCurrentDimension(assembled.dimension) && !session.reflectionFailed()) {
                                    if (inViewDistance) {
                                        triggerSingleRegionLoad(coord, assembled.caveLayer, true);
                                    } else if (XaeroReflectionHelper.getLeafMapRegion(
                                            assembled.caveLayer, coord.x(), coord.z(), false) != null) {

                                        RegionPipelineTracker.onDeferredLoadQueued(
                                                coord.x(), coord.z(), assembled.caveLayer);
                                        pendingRegionLoads.add(new PendingRegionLoad(
                                                coord.x(), coord.z(), assembled.caveLayer, assembled.dimension));
                                    }
                                    LOGGER.debug("Region ({}, {}) layer={} inView={} was written and queued for loading",
                                            coord.x(), coord.z(), assembled.caveLayer, inViewDistance);
                                } else if (shouldProcess) {
                                    RegionPipelineTracker.onWriteOnlyComplete(
                                            assembled.regionX, assembled.regionZ, assembled.caveLayer);
                                    LOGGER.debug("Region ({}, {}) was written to disk; skipping runtime reload because reflection is unavailable",
                                            coord.x(), coord.z());
                                } else {
                                    RegionPipelineTracker.onWriteOnlyComplete(
                                            assembled.regionX, assembled.regionZ, assembled.caveLayer);
                                }
                                RegionPipelineTracker.onWriteComplete(
                                        assembled.regionX, assembled.regionZ, assembled.caveLayer, true);
                            }

                            if (batchPending.decrementAndGet() == 0 && batchTsCache != null
                                    && submittedCount.get() > 0) {
                                ClientSyncWriteQueue.saveTimestampCacheAsync(batchTsCache);
                            }
                        } finally {
                            applyCounter.decrementAndGet();
                            tryCompleteSync(gen);
                        }
                    });
                });
            }

            if (payload.isComplete()) {
                requestSyncFinish(generationAtEnqueue, serverOutcome, tsCache);
            }
            if (submittedCount.get() == 0) {
                tryCompleteSync(generationAtEnqueue);
            }
        });
    }

    private static void requestSyncFinish(int generation, SyncOutcome serverOutcome, ClientTimestampCache tsCache) {
        syncFinishRequested = true;
        syncFinishGeneration = generation;
        syncFinishOutcome = serverOutcome;
        syncFinishTsCache = tsCache;
        tryCompleteSync(generation);
    }

    private static void tryCompleteSync(int generation) {
        if (!syncFinishRequested || ClientSyncWriteQueue.hasPendingWrites()
                || pendingWriteApplyCallbacks.get() > 0) {
            return;
        }
        if (!session.isCurrent(generation)) {
            return;
        }

        syncFinishRequested = false;
        SyncOutcome serverOutcome = syncFinishOutcome;
        ClientTimestampCache tsCache = syncFinishTsCache;

        int totalReceived = writtenRegionKeys.size();
        LOGGER.info("Sync complete: {} regions processed in total", totalReceived);

        lastSyncCompleteTs = System.currentTimeMillis();

        SyncOutcome finalOutcome = serverOutcome == SyncOutcome.PARTIAL_SUCCESS || session.reflectionFailed() || failedWrites.get() > 0
                ? SyncOutcome.PARTIAL_SUCCESS
                : SyncOutcome.SUCCESS;
        session.setOutcome(finalOutcome);
        MapSyncerCommandLogic.resetSyncRetry();

        if (!writtenRegionKeys.isEmpty() || failedWrites.get() > 0) {
            XaeroMapDataHandler.recordUpdatedRegionCoords(updatedRegionCoords);
            SyncProgressTracker.completeWithCount(totalReceived);

            if (AutoSyncManager.isActive()) {
                AutoSyncManager.markComplete();
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(
                            ChatUtils.success("mapsyncer.autosync.complete"));
                }
            }

            if (tsCache != null && failedWrites.get() == 0 && serverOutcome != SyncOutcome.PARTIAL_SUCCESS) {
                tsCache.markSyncComplete();
            }
            notifySyncOutcome(finalOutcome);
        } else {
            LOGGER.info("Sync complete with no data received");
            SyncProgressTracker.finishUptodate();
            finishJoinAutoSyncIfActive();
            if (tsCache != null && failedWrites.get() == 0 && serverOutcome != SyncOutcome.PARTIAL_SUCCESS) {
                tsCache.markSyncComplete();
            }
        }

        RegionPipelineTracker.markSyncPipelineComplete();
        clearSyncStateAfterComplete();
        scheduleDeferredReloadCleanup();
    }

    private static void notifySyncOutcome(SyncOutcome outcome) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (outcome == SyncOutcome.PARTIAL_SUCCESS) {
            if (session.reflectionFailed()) {
                Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.error("mapsyncer.sync.reflection_failed"));
            } else {
                Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.error("mapsyncer.sync.partial"));
            }
        }
    }

    private static void handleProgressUpdate(SyncProgressPayload payload, PayloadContext context) {
        context.enqueueWork(() -> {
            String status = payload.status();
            if (status != null && status.startsWith("request_")) {

                SyncProgressTracker.cancelTracking();
                MapSyncerCommandLogic.retryLastSyncRequest();
                return;
            }
            if (status != null && (status.startsWith("aborted") || status.equals("request_partial_timeout"))) {
                SyncProgressTracker.cancelTracking();
                MapPacketHandler.clearSyncData();
                MapSyncerCommandLogic.resetSyncRetry();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    if (status.contains("timeout")) {
                        ClientMessageHelper.sendChatMessage(
                                ChatUtils.error("mapsyncer.sync.server_timeout"));
                    } else {
                        ClientMessageHelper.sendChatMessage(
                                ChatUtils.error("mapsyncer.sync.cancelled"));
                    }
                }
                return;
            }

            SyncProgressTracker.onServerResponded();
            if ("waiting_for_generation".equals(status)) {
                SyncProgressTracker.update(0, 0,
                        ChatUtils.desc("mapsyncer.sync.waiting_generation").getString());
                return;
            }

            int processed = payload.processed();
            int total = payload.total();
            long now = System.currentTimeMillis();
            if (processed == lastProgressProcessed && total == lastProgressTotal
                    && now - lastProgressTime < PROGRESS_DEDUP_MS) {
                return;
            }
            lastProgressProcessed = processed;
            lastProgressTotal = total;
            lastProgressTime = now;
            SyncProgressTracker.update(processed, total, payload.status());
        });
    }

    private static void resumeChunkUpdatesIfIdle() {
        if (!pendingRegionLoads.isEmpty()) {
            return;
        }
        if (session.phase() == SyncPhase.DRAINING_RELOAD) {
            session.completeSession();
            LOGGER.info("Deferred reload queue drained, sync session idle");
        }
    }

    private static void clearSyncStateAfterComplete() {
        updatedRegionCoords.clear();
        writtenRegionKeys.clear();
        failedWrites.set(0);
        loadedRegions.clear();
        partAssembler.clear();
        lastMwDir = null;
    }

    private static void scheduleDeferredReloadCleanup() {
        int intervalTicks;
        try {
            intervalTicks = PlatformManager.getPlatform().getMapRegionLoadIntervalTicks();
        } catch (IllegalStateException e) {
            intervalTicks = 1;
        }
        if (RegionLoadThrottle.isViewOnly(intervalTicks) || pendingRegionLoads.isEmpty()) {
            pendingRegionLoads.clear();
            clearReflectionCache();
            if (session.phase() != SyncPhase.IDLE) {
                session.completeSession();
            }
            return;
        }
        session.beginDrainingReload();
        drainPendingLoadQueue();
        finishDeferredReloadCleanupIfDone();
    }

    private static void finishDeferredReloadCleanupIfDone() {
        if (session.phase() != SyncPhase.DRAINING_RELOAD || !pendingRegionLoads.isEmpty()) {
            return;
        }
        clearReflectionCache();
        resumeChunkUpdatesIfIdle();
        LOGGER.debug("Out-of-view region reload queue drained; reflection cache released");
    }

    private static void clearSyncState() {
        updatedRegionCoords.clear();
        writtenRegionKeys.clear();
        failedWrites.set(0);
        loadedRegions.clear();
        partAssembler.clear();
        pendingRegionLoads.clear();
        lastMwDir = null;
        RegionLoadThrottle.reset();
    }

    private static void clearReflectionCache() {
        XaeroReflectionHelper.clearCache();
    }

    private static boolean initializeReflectionCache() {
        if (XaeroReflectionHelper.isInitialized()) {
            LOGGER.debug("Reflection cache is already initialized; skipping duplicate initialization");
            return true;
        }

        LOGGER.info("Initializing the reflection API cache...");
        boolean initSuccess = XaeroReflectionHelper.initialize();

        if (initSuccess) {
            LOGGER.info("XaeroReflectionHelper initialized successfully");
            boolean regionDetectSuccess = XaeroReflectionHelper.setRegionDetectionComplete(true);
            if (regionDetectSuccess) {
                LOGGER.info("regionDetectionComplete set to true; reflection is ready");
            } else {
                LOGGER.warn("Failed to set regionDetectionComplete; getLeafMapRegion may return null");
            }
            return true;
        }

        LOGGER.error("XaeroReflectionHelper initialization failed; reflection is unavailable");
        LOGGER.error("Possible causes:");
        LOGGER.error("  1. Xaero's World Map is not installed");
        LOGGER.error("  2. The Xaero version is incompatible with MapSyncer");
        LOGGER.error("  3. A class loader problem");
        LOGGER.error("Map sync cannot work normally: data will be written to disk but will not trigger reloading");
        return false;
    }

    private static void triggerSingleRegionLoad(XaeroMapDataHandler.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        RegionPipelineTracker.onReflectionLoadStart(coord.x(), coord.z(), caveLayer);
        boolean success = false;
        try {
            if (!XaeroReflectionHelper.isInitialized()) {
                LOGGER.warn("Reflection cache is not initialized; cannot load region ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (loadedRegions.contains(coord)) {
                LOGGER.debug("Region ({}, {}) layer={} is already loaded; skipping", coord.x(), coord.z(), caveLayer);
                success = true;
                return;
            }

            Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("Cannot create MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            String regionWorldId = XaeroReflectionHelper.getWorldId(mapRegion);
            String regionDimId = XaeroReflectionHelper.getDimId(mapRegion);
            String regionMwId = XaeroReflectionHelper.getMwId(mapRegion);
            LOGGER.info("Region ({}, {}) properties: worldId={}, dimId={}, mwId={}, lastMwDir={}",
                coord.x(), coord.z(), regionWorldId, regionDimId, regionMwId, lastMwDir);

            if (!XaeroReflectionHelper.prepareRegionLoad(mapRegion)) {
                LOGGER.warn("Failed to prepare region ({}, {}) layer={} for loading; skipping", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (!XaeroReflectionHelper.setLoadState(mapRegion, XaeroReflectionHelper.LOAD_STATE_CLEARED)) {
                LOGGER.warn("Failed to set loadState for region ({}, {}) layer={}; skipping", coord.x(), coord.z(), caveLayer);
                return;
            }

            String reason = inViewDistance ? "sync view" : "sync outside";
            if (!XaeroReflectionHelper.requestLoad(mapRegion, reason, true)) {
                LOGGER.warn("Failed to request loading for region ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (inViewDistance) {
                LOGGER.debug("Region ({}, {}) layer={} is in view; prioritizing at the front of the load queue", coord.x(), coord.z(), caveLayer);
            } else {
                LOGGER.debug("Region ({}, {}) layer={} is out of view; adding to the load queue", coord.x(), coord.z(), caveLayer);
            }

            loadedRegions.add(coord);
            success = true;
        } catch (Exception e) {
            LOGGER.error("Failed to load region ({}, {}) layer={} immediately: {}", coord.x(), coord.z(), caveLayer, e.getMessage(), e);
        } finally {
            RegionPipelineTracker.onReflectionLoadDone(coord.x(), coord.z(), caveLayer, success);
        }
    }

    public static void drainPendingLoadQueue() {
        XaeroMapIntegrator.captureIntegratedServerCache();
        IntegratedHostMapUpdates.onClientTick();
        Minecraft currentClient = Minecraft.getInstance();
        String currentDimension = currentClient.level == null ? ""
                : com.mapsyncer.util.DimensionApiHelper.getDimId(currentClient.level.dimension());
        if (!currentDimension.equals(lastReloadDimension)) {
            loadedRegions.clear();
            pendingRegionLoads.clear();
            lastReloadDimension = currentDimension;
        }
        SyncProgressTracker.onClientTick();
        RegionPipelineTracker.onClientTick();
        int intervalTicks;
        try {
            intervalTicks = PlatformManager.getPlatform().getMapRegionLoadIntervalTicks();
        } catch (IllegalStateException e) {
            return;
        }
        if (RegionLoadThrottle.isViewOnly(intervalTicks)) {
            return;
        }
        if (pendingRegionLoads.isEmpty()) {
            finishDeferredReloadCleanupIfDone();
            return;
        }

        if (RegionLoadThrottle.isUnlimited(intervalTicks)) {
            PendingRegionLoad pending;
            while ((pending = pendingRegionLoads.poll()) != null) {
                if (!isCurrentDimension(pending.dimension())) continue;
                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    pending.regionX(), pending.regionZ(), pending.caveLayer());
                triggerSingleRegionLoad(coord, pending.caveLayer(), false);
            }
            RegionLoadThrottle.reset();
            finishDeferredReloadCleanupIfDone();
            return;
        }

        if (!RegionLoadThrottle.shouldDrainOne(intervalTicks)) {
            return;
        }

        PendingRegionLoad pending = pendingRegionLoads.poll();
        if (pending != null && isCurrentDimension(pending.dimension())) {
            XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                pending.regionX(), pending.regionZ(), pending.caveLayer());
            triggerSingleRegionLoad(coord, pending.caveLayer(), false);
        }
        finishDeferredReloadCleanupIfDone();
    }

    public static boolean hasPendingLoads() {
        return !pendingRegionLoads.isEmpty();
    }

    public static void prepareSyncForDimension(String targetDimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        String currentXaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(
                mc.level.dimension().identifier().toString());

        if (targetDimension.equals(currentXaeroDim)) {
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.sendSystemMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded));
            }
        }
    }

    private static void finishJoinAutoSyncIfActive() {
        if (!AutoSyncManager.isActive()) {
            return;
        }
        AutoSyncManager.markComplete();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ClientMessageHelper.sendChatMessage(
                    ChatUtils.success("mapsyncer.autosync.complete"));
        }
    }
}
