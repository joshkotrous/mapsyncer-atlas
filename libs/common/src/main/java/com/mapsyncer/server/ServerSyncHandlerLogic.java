package com.mapsyncer.server;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PlayerLevelApiHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class ServerSyncHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandlerLogic.class);

    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    private static int getMaxPacketSize() {
        int configValue = PlatformManager.getPlatform().getMaxSyncPacketSize();
        return Math.min(configValue, MAX_PACKET_SIZE_LIMIT);
    }

    private static int getBatchThreshold() {
        int limitKBps = PlatformManager.getPlatform().getSyncSpeedLimitKBps();
        if (limitKBps <= 0) {

            return getMaxPacketSize();
        }

        int maxPacketSize = getMaxPacketSize();
        int limitBytesPerSec = limitKBps * 1024;

        int packetsPerSecond = limitBytesPerSec / maxPacketSize;

        if (packetsPerSecond < 1) {
            packetsPerSecond = 1;
        }

        int actualThreshold = packetsPerSecond * maxPacketSize;

        LOGGER.debug("Speed limit adjusted: {} KB/s → {} packets/s × {} KB = {} KB/s",
                limitKBps, packetsPerSecond, maxPacketSize / 1024, actualThreshold / 1024);

        return actualThreshold;
    }

    private static int sendBatchInChunks(List<ChunkMapData> batch, int batchBytes,
            MinecraftServer server, int worldId, int processed, int total, UUID playerId, int syncVersion) {
        int maxPacketSize = getMaxPacketSize();

        if (batchBytes <= maxPacketSize) {
            final List<ChunkMapData> batchToSend = new ArrayList<>(batch);
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(batchToSend, false, worldId, "ok"));
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(processed, total,
                                String.format("Sending regions %d/%d", processed, total)));
            });
            return 1;
        }

        List<ChunkMapData> currentChunk = new ArrayList<>();
        int currentSize = 0;
        int packetCount = 0;

        for (ChunkMapData chunk : batch) {
            if (currentSize + chunk.data.length > maxPacketSize && !currentChunk.isEmpty()) {
                final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
                final int sentProgress = processed + packetCount;
                enqueueIfCurrent(server, playerId, syncVersion, player -> {
                    NetworkManager.sendToPlayer(player,
                            new SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                    NetworkManager.sendToPlayer(player,
                            new SyncProgressPayload(sentProgress, total,
                                    String.format("Sending regions %d/%d", sentProgress, total)));
                });
                packetCount++;

                currentChunk.clear();
                currentSize = 0;
            }

            currentChunk.add(chunk);
            currentSize += chunk.data.length;
        }

        if (!currentChunk.isEmpty()) {
            final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
            final int sentProgress = processed + packetCount;
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(sentProgress, total,
                                String.format("Sending regions %d/%d", sentProgress, total)));
            });
            packetCount++;
        }

        return packetCount;
    }

    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();

    private static final Map<UUID, ResourceKey<Level>> playerSyncDimensions = new ConcurrentHashMap<>();

    private static final Map<UUID, Thread> syncThreads = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> speedLimitBytesSent = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> speedLimitCycleStart = new ConcurrentHashMap<>();

    private static final long MAX_SPEED_LIMIT_CYCLE_MS = 1000;

    private static final AtomicInteger globalSyncVersion = new AtomicInteger(0);

    private static final AtomicInteger activeSyncCount = new AtomicInteger(0);

    private static final long GENERATION_WAIT_HEARTBEAT_MS = 10_000;

    private static final long GENERATION_WAIT_TIMEOUT_MS = 30 * 60 * 1000L;

    private static final ConcurrentHashMap<UUID, Map<Integer, SyncRequestPayload>> requestPartBuffer = new ConcurrentHashMap<>();

    private static final Map<UUID, Integer> requestTotalParts = new ConcurrentHashMap<>();

    private static final Map<UUID, Long> requestPartLastActivity = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> requestPartFirstActivity = new ConcurrentHashMap<>();

    private static final long PART_ASSEMBLY_TIMEOUT_MS = 20_000;

    private static final long PART_ASSEMBLY_CHECK_INTERVAL_MS = 5_000;

    private static volatile MinecraftServer currentServer = null;

    private static volatile ScheduledExecutorService partAssemblyTimer = null;

    private record RegionSyncInfo(Path zipPath, String normalizedPath, long timestampSeconds,
                                   int regionX, int regionZ, String dimension, int caveLayer) {

        boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    public static void registerHandlers() {
        NetworkManager.getHandler().registerSyncRequestHandler(
            (payload, context) -> context.enqueueWork(() -> handleSyncRequest(payload, context))
        );
    }

    public static boolean isSyncActive() {
        return activeSyncCount.get() > 0;
    }

    public static void onPlayerDisconnect(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);

        clearSpeedLimitState(playerId);

        requestPartBuffer.remove(playerId);
        requestTotalParts.remove(playerId);
        requestPartLastActivity.remove(playerId);
        requestPartFirstActivity.remove(playerId);
        ServerSyncSession.finalizeSession(playerId);

        Thread syncThread = syncThreads.remove(playerId);
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
            LOGGER.info("Player {} disconnected, sync thread interrupted", playerId);
        }
    }

    private static boolean isSyncStillActive(UUID playerId, int syncVersion) {
        if (syncThreads.get(playerId) != Thread.currentThread()) {
            return false;
        }
        if (!ServerSyncSession.isCurrent(playerId, syncVersion)) {
            return false;
        }
        return syncingPlayers.contains(playerId);
    }

    private static final long PLAYER_VALIDATION_TIMEOUT_SEC = 60;
    private static final int PLAYER_VALIDATION_MAX_ATTEMPTS = 3;

    private enum PlayerCheckResult {
        VALID, INVALID, TIMEOUT
    }

    private static PlayerCheckResult checkPlayerOnMainThread(MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        for (int attempt = 1; attempt <= PLAYER_VALIDATION_MAX_ATTEMPTS; attempt++) {
            try {
                boolean valid = server.submit(() -> {
                    if (!ServerSyncSession.isCurrent(playerId, syncVersion)) {
                        return false;
                    }
                    if (!syncingPlayers.contains(playerId)) {
                        return false;
                    }
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || player.connection == null) {
                        return false;
                    }
                    if (startDimension != null && !player.level().dimension().equals(startDimension)) {
                        LOGGER.info("Player {} changed dimension from {} to {}, aborting sync",
                                playerId, DimensionApiHelper.getDimId(startDimension),
                                DimensionApiHelper.getDimId(player.level().dimension()));
                        syncingPlayers.remove(playerId);
                        playerSyncDimensions.remove(playerId);
                        return false;
                    }
                    return true;
                }).get(PLAYER_VALIDATION_TIMEOUT_SEC, TimeUnit.SECONDS);
                return valid ? PlayerCheckResult.VALID : PlayerCheckResult.INVALID;
            } catch (java.util.concurrent.TimeoutException e) {
                LOGGER.warn("Player {} validation timed out (attempt {}/{})", playerId, attempt,
                        PLAYER_VALIDATION_MAX_ATTEMPTS);
                if (attempt >= PLAYER_VALIDATION_MAX_ATTEMPTS) {
                    return PlayerCheckResult.TIMEOUT;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to validate player {} on server thread (attempt {}/{})",
                        playerId, attempt, PLAYER_VALIDATION_MAX_ATTEMPTS, e);
                if (attempt >= PLAYER_VALIDATION_MAX_ATTEMPTS) {
                    return PlayerCheckResult.TIMEOUT;
                }
            }
        }
        return PlayerCheckResult.TIMEOUT;
    }

    private static void notifySyncAborted(MinecraftServer server, UUID playerId, int syncVersion, String reason) {
        enqueueIfCurrent(server, playerId, syncVersion, player ->
                NetworkManager.sendToPlayer(player, new SyncProgressPayload(0, 0, "aborted:" + reason)));
    }

    private static void ensurePartAssemblyTimer() {
        ScheduledExecutorService timer = partAssemblyTimer;
        if (timer != null && !timer.isShutdown()) {
            return;
        }
        synchronized (ServerSyncHandlerLogic.class) {
            if (partAssemblyTimer != null && !partAssemblyTimer.isShutdown()) {
                return;
            }
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mapsyncer-part-assembly-timer");
                t.setDaemon(true);
                return t;
            });
            partAssemblyTimer = timer;
            timer.scheduleWithFixedDelay(ServerSyncHandlerLogic::checkPartAssemblyTimeouts,
                    PART_ASSEMBLY_TIMEOUT_MS, PART_ASSEMBLY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            LOGGER.info("Part assembly timeout checker started (timeout={}ms, interval={}ms)",
                    PART_ASSEMBLY_TIMEOUT_MS, PART_ASSEMBLY_CHECK_INTERVAL_MS);
        }
    }

    private static void checkPartAssemblyTimeouts() {
        if (requestPartBuffer.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        MinecraftServer server = currentServer;
        for (Map.Entry<UUID, Long> entry : requestPartLastActivity.entrySet()) {
            UUID playerId = entry.getKey();
            if (now - entry.getValue() < PART_ASSEMBLY_TIMEOUT_MS
                    && now - requestPartFirstActivity.getOrDefault(playerId, now) < 10 * 60_000L) {
                continue;
            }
            Map<Integer, SyncRequestPayload> parts = requestPartBuffer.remove(playerId);
            if (parts == null) {
                requestPartLastActivity.remove(playerId);
                requestPartFirstActivity.remove(playerId);
                continue;
            }
            Integer total = requestTotalParts.get(playerId);
            requestTotalParts.remove(playerId);
            requestPartLastActivity.remove(playerId);
            requestPartFirstActivity.remove(playerId);
            LOGGER.warn("SyncRequest parts incomplete for player {} ({} of {} parts received), notifying retry",
                    playerId, parts.size(), total == null ? -1 : total);
            notifyClientRetry(playerId, server);
        }
    }

    private static void notifyClientRetry(UUID playerId, MinecraftServer server) {
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
            if (sp != null) {
                LOGGER.info("Notifying player {} to retry sync request (partial timeout)", playerId);
                NetworkManager.sendToPlayer(sp, new SyncProgressPayload(0, 0, "aborted:request_partial_timeout"));
            }
        });
    }

    private static boolean isPlayerStillValid(MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        if (!isSyncStillActive(playerId, syncVersion)) {
            return false;
        }
        PlayerCheckResult result = checkPlayerOnMainThread(server, playerId, startDimension, syncVersion);
        if (result == PlayerCheckResult.TIMEOUT) {

            LOGGER.warn("Player {} validation timed out, skipping check (sync continues)",
                    playerId);
            return true;
        }
        return result == PlayerCheckResult.VALID;
    }

    private static int readWorldIdFromXaeroMap(ServerPlayer serverPlayer) {
        try {
            Path xaeromapPath = serverPlayer.level().getServer()
                    .getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent()
                    .resolve("xaeromap.txt");

            if (!Files.exists(xaeromapPath)) {
                LOGGER.warn("xaeromap.txt not found at {}", xaeromapPath);
                return 0;
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(xaeromapPath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2 && parts[0].equals("id")) {
                        int worldId = Integer.parseInt(parts[1]);
                        LOGGER.info("Read worldId {} from xaeromap.txt", worldId);
                        return worldId;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read xaeromap.txt", e);
        }
        return 0;
    }

    private static boolean applySpeedLimit(int bytesSent, MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        int limitKBps = PlatformManager.getPlatform().getSyncSpeedLimitKBps();
        if (limitKBps <= 0) return true; // No limit

        Long cycleStart = speedLimitCycleStart.get(playerId);
        Long totalBytes = speedLimitBytesSent.get(playerId);

        if (cycleStart == null || totalBytes == null) {

            cycleStart = System.currentTimeMillis();
            totalBytes = 0L;
            speedLimitCycleStart.put(playerId, cycleStart);
            speedLimitBytesSent.put(playerId, totalBytes);
        }

        totalBytes += bytesSent;
        speedLimitBytesSent.put(playerId, totalBytes);

        long actualTimeMs = System.currentTimeMillis() - cycleStart;

        if (actualTimeMs > MAX_SPEED_LIMIT_CYCLE_MS) {
            LOGGER.debug("Speed limit cycle too long ({} ms), resetting", actualTimeMs);
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);

            totalBytes = (long) bytesSent;
            speedLimitBytesSent.put(playerId, totalBytes);
            cycleStart = System.currentTimeMillis();
            actualTimeMs = 0;
        }

        long expectedTimeMs = (totalBytes * 1000L) / (limitKBps * 1024L);

        if (actualTimeMs >= expectedTimeMs) {
            LOGGER.debug("Bandwidth bottleneck detected: sent {} bytes in {} ms (expected {} ms at {} KBps), skipping wait",
                    totalBytes, actualTimeMs, expectedTimeMs, limitKBps);

            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            return true;
        }

        long remainingTimeMs = expectedTimeMs - actualTimeMs;

        LOGGER.debug("Applying speed limit: sent {} bytes in {} ms, need to wait {} ms more (limit: {} KBps)",
                totalBytes, actualTimeMs, remainingTimeMs, limitKBps);

        long checkIntervalMs = 100; // Check every 100ms
        long waitStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - waitStartTime < remainingTimeMs) {
            // Check if player disconnected during speed limit wait
            if (!isPlayerStillValid(server, playerId, startDimension, syncVersion)) {
                LOGGER.info("Player {} disconnected during speed limit wait, aborting sync", playerId);
                return false;
            }

            long waitRemainingMs = remainingTimeMs - (System.currentTimeMillis() - waitStartTime);
            if (waitRemainingMs <= 0) {
                break;
            }
            long sleepMs = Math.min(checkIntervalMs, waitRemainingMs);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        speedLimitCycleStart.put(playerId, System.currentTimeMillis());
        speedLimitBytesSent.put(playerId, 0L);

        return true;
    }

    private static void clearSpeedLimitState(UUID playerId) {
        speedLimitBytesSent.remove(playerId);
        speedLimitCycleStart.remove(playerId);
    }

    private static void finalizePlayerSync(MinecraftServer server, UUID playerId, int version) {
        server.execute(() -> {
            if (ServerSyncSession.isCurrent(playerId, version)) finalizePlayerSync(playerId);
        });
    }

    private static void finalizePlayerSync(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        ServerSyncSession.finalizeSession(playerId);

        requestPartBuffer.remove(playerId);
        requestTotalParts.remove(playerId);

        Thread syncThread = syncThreads.remove(playerId);
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
        }

        clearSpeedLimitState(playerId);
    }

    private static void handleSyncRequest(SyncRequestPayload payload, PayloadContext context) {
        Player player = (Player) NetworkManager.getHandler().getPlayerFromContext(context);
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        MinecraftServer requestServer = PlayerLevelApiHelper.getServer(serverPlayer);
        if (CacheCommandHandler.isIntegratedHost(requestServer, serverPlayer)) {
            CacheCommandHandler.showIntegratedServerHint(requestServer, serverPlayer::sendSystemMessage);
            NetworkManager.sendToPlayer(serverPlayer,
                    new SyncResponsePayload(List.of(), true, 0, "integrated_host"));
            return;
        }

        UUID playerId = serverPlayer.getUUID();
        if (!payload.hasValidPartBounds()) return;
        if (!payload.syncAll() && !payload.targetDimension().isEmpty()
                && !DimensionPathMapping.isSafeXaeroFolder(payload.targetDimension())) {
            NetworkManager.sendToPlayer(serverPlayer, new SyncResponsePayload(List.of(), true, 0, "dim_not_available"));
            return;
        }

        MinecraftServer server = PlayerLevelApiHelper.getServer(serverPlayer);
        if (server != null) {
            currentServer = server;
        }

        requestPartLastActivity.put(playerId, System.currentTimeMillis());
        requestPartFirstActivity.putIfAbsent(playerId, System.currentTimeMillis());
        ensurePartAssemblyTimer();

        if (payload.totalParts() > 1) {
            Map<Integer, SyncRequestPayload> previous = requestPartBuffer.get(playerId);
            SyncRequestPayload reference = previous == null || previous.isEmpty() ? null : previous.values().iterator().next();
            if (reference != null && (reference.requestId() != payload.requestId()
                    || reference.totalParts() != payload.totalParts()
                    || reference.syncAll() != payload.syncAll()
                    || !reference.targetDimension().equals(payload.targetDimension())
                    || reference.silent() != payload.silent()
                    || (payload.requestId() == 0 && payload.partIndex() == 0))) {
                requestPartBuffer.remove(playerId);
                requestPartLastActivity.put(playerId, System.currentTimeMillis());
                requestPartFirstActivity.put(playerId, System.currentTimeMillis());
            }

            final SyncRequestPayload currentPayload = payload;
            boolean[] allArrived = new boolean[1];
            Map<Integer, SyncRequestPayload> parts = requestPartBuffer.compute(playerId, (k, existing) -> {
                if (existing == null) {
                    existing = new ConcurrentHashMap<>();
                }
                existing.put(currentPayload.partIndex(), currentPayload);
                allArrived[0] = existing.size() >= currentPayload.totalParts();
                return existing;
            });
            requestTotalParts.put(playerId, payload.totalParts());
            long entries = 0;
            long chars = 0;
            for (SyncRequestPayload part : parts.values()) {
                entries += part.clientMeta().size();
                for (var entry : part.clientMeta().entrySet()) {
                    chars += entry.getKey().length() + entry.getValue().hash().length();
                }
            }
            if (entries > SyncRequestPayload.MAX_META_ENTRIES || chars > SyncRequestPayload.MAX_ASSEMBLED_CHARS) {
                requestPartBuffer.remove(playerId);
                requestTotalParts.remove(playerId);
                requestPartLastActivity.remove(playerId);
                requestPartFirstActivity.remove(playerId);
                NetworkManager.sendToPlayer(serverPlayer, new SyncProgressPayload(0, 0, "aborted:request_too_large"));
                return;
            }

            if (!allArrived[0]) {
                LOGGER.debug("SyncRequest part {}/{} from player {}", payload.partIndex() + 1, payload.totalParts(), playerId);
                return;
            }

            parts = requestPartBuffer.remove(playerId);
            if (parts == null) {
                return;
            }
            requestTotalParts.remove(playerId);

            Map<String, ClientMeta> merged = new HashMap<>();
            SyncRequestPayload refPart = null;
            for (SyncRequestPayload part : parts.values()) {
                merged.putAll(part.clientMeta());
                if (refPart == null) {
                    refPart = part;
                }
            }
            payload = new SyncRequestPayload(merged, refPart.partIndex(), refPart.totalParts(),
                    refPart.syncAll(), refPart.targetDimension(), refPart.silent(), refPart.requestId());
            LOGGER.debug("SyncRequest assembled from {} parts, {} entries total", parts.size(), merged.size());
        }

        requestPartBuffer.remove(playerId);
        requestTotalParts.remove(playerId);

        requestPartLastActivity.remove(playerId);
        requestPartFirstActivity.remove(playerId);

        int syncVersion = globalSyncVersion.incrementAndGet();

        ServerSyncSession.interruptOldSyncThread(playerId, syncThreads, () -> clearSpeedLimitState(playerId));

        ServerSyncSession.assignVersion(playerId, syncVersion);

        ResourceKey<Level> startDimension = serverPlayer.level().dimension();

        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        int startBlockX = serverPlayer.getBlockX();
        int startBlockZ = serverPlayer.getBlockZ();
        int viewDistanceChunks = PlayerLevelApiHelper.getServer(serverPlayer).getPlayerList().getViewDistance() + 2;
        int viewDistanceRegions = (viewDistanceChunks >> 5) + 1;
        int worldId = readWorldIdFromXaeroMap(serverPlayer);

        // Client metadata (timestamp + hash) - contains already received regions for resume
        Map<String, ClientMeta> clientMeta = payload.clientMeta();
        boolean syncAll = payload.syncAll();
        String targetDimension = payload.targetDimension();
        boolean silent = payload.silent();
        SyncRadiusPolicy radiusPolicy = SyncRadiusPolicy.atPlayer(
                PlatformManager.getPlatform().getSyncRadiusRegions(), startBlockX, startBlockZ,
                DimensionPathMapping.getInstance().toXaeroDimension(DimensionApiHelper.getDimId(startDimension)));

        Thread syncThread = new Thread(() -> {
            activeSyncCount.incrementAndGet();
            try {
                if (!awaitGenerationIdle(server, playerId, syncVersion)) {
                    finalizePlayerSync(server, playerId, syncVersion);
                    return;
                }
                processSyncAsync(server, playerId, clientMeta, syncAll, targetDimension,
                        startDimension, syncVersion, startBlockX, startBlockZ,
                        viewDistanceRegions, worldId, silent, radiusPolicy);
            } catch (RuntimeException e) {
                LOGGER.error("Unhandled error while processing sync for player {}", playerId, e);
                notifySyncAborted(server, playerId, syncVersion, "server_error");
                finalizePlayerSync(server, playerId, syncVersion);
            } finally {
                activeSyncCount.updateAndGet(value -> Math.max(0, value - 1));
            }
        }, "mapsyncer-sync-" + playerId);
        syncThread.setDaemon(true);
        syncThreads.put(playerId, syncThread);
        syncThread.start();
        LOGGER.debug("Started async sync thread for player {} (v{})", serverPlayer.getName().getString(), syncVersion);
    }

    private static boolean awaitGenerationIdle(MinecraftServer server, UUID playerId, int syncVersion) {
        boolean announced = false;
        long lastHeartbeat = 0L;
        long deadline = System.currentTimeMillis() + GENERATION_WAIT_TIMEOUT_MS;

        try {

            Thread.sleep(250L);
            while (true) {
                if (!ServerSyncSession.isCurrent(playerId, syncVersion)
                        || !Thread.currentThread().isAlive()) {
                    return false;
                }
                if (!ConversionOrchestrator.isRunning()) {

                    Thread.sleep(500L);
                    if (!ConversionOrchestrator.isRunning()) {
                        return true;
                    }
                    continue;
                }
                if (!announced) {
                    announced = true;
                    LOGGER.info("Sync for player {} is waiting for in-progress map generation to finish",
                            playerId);
                }

                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= GENERATION_WAIT_HEARTBEAT_MS) {
                    lastHeartbeat = now;
                    enqueueIfCurrent(server, playerId, syncVersion, player ->
                            NetworkManager.sendToPlayer(player,
                                    new SyncProgressPayload(0, 0, "waiting_for_generation")));
                }

                if (now >= deadline) {
                    LOGGER.warn("Timed out waiting for map generation to finish for player {}, "
                            + "continuing with current cache snapshot", playerId);
                    return true;
                }
                Thread.sleep(500L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Generation wait interrupted for player {}", playerId);
            return false;
        }
    }

    private static void enqueueIfCurrent(MinecraftServer server, UUID playerId, int version, Consumer<ServerPlayer> task) {
        server.execute(() -> {
            if (!ServerSyncSession.isCurrent(playerId, version)) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            task.accept(player);
        });
    }

    private static void sendSyncCompleteMessage(ServerPlayer player, int sentCount, int failedCount, int totalPlanned) {
        if (failedCount > 0) {
            player.sendSystemMessage(ChatUtils.error("mapsyncer.server.sync_partial", sentCount, failedCount, totalPlanned));
        } else {
            player.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", sentCount));
        }
    }

    private static Set<String> discoverDimensionsFromCache(Path cacheDir) {
        Set<String> dims = new HashSet<>();
        if (!Files.exists(cacheDir)) {
            return dims;
        }
        try (Stream<Path> topLevel = Files.list(cacheDir)) {
            topLevel.filter(Files::isDirectory).forEach(dimDir -> {
                String xaeroDim = dimDir.getFileName().toString();
                try (Stream<Path> stream = Files.walk(dimDir)) {
                    if (stream.anyMatch(p -> p.toString().endsWith(".zip"))) {
                        dims.add(xaeroDim);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to walk dimension {} cache", xaeroDim, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to list cache directory", e);
        }
        return dims;
    }

    private static void processSyncAsync(MinecraftServer server, UUID playerId,
            Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension,
            ResourceKey<Level> startDimension, int syncVersion,
            int startBlockX, int startBlockZ, int viewDistanceRegions, int worldId, boolean silent,
            SyncRadiusPolicy radiusPolicy) {

        LOGGER.debug("Server worldId from xaeromap.txt: {}", worldId);

        ServerMapCache.Snapshot cacheLayout = ServerMapCache.snapshot();
        Path cacheDir = cacheLayout.root();
        GenerationCache genCache = cacheLayout.sharedXaero() ? null : GenerationCache.getInstance(cacheDir);
        if (genCache != null) genCache.pruneInvalidEntries(cacheDir);

        Map<String, TimestampHashEntry> serverCache = genCache == null ? Map.of() : genCache.getAll();
        List<Path> sharedFiles = List.of();
        Set<String> sharedDimensions = new HashSet<>();
        if (cacheLayout.sharedXaero()) {
            try {
                sharedFiles = cacheLayout.regionFiles();
                for (Path file : sharedFiles) {
                    sharedDimensions.add(cacheLayout.regionKey(file).split("/", 2)[0]);
                }
            } catch (IOException e) {
                LOGGER.warn("Unable to scan host Xaero map directory", e);
            }
        }

        if (!Files.exists(cacheDir)) {
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                player.sendSystemMessage(cacheLayout.sharedXaero()
                        ? ChatUtils.message("mapsyncer.server.host_map_not_ready")
                        : ChatUtils.message("mapsyncer.server.no_cache", CacheCommandHandler.serverCommandPrefix()));
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(List.of(), true, worldId, "no_cache"));
                finalizePlayerSync(server, playerId, syncVersion);
            });
            return;
        }

        int hashMatchCount = 0;
        int timestampSkipCount = 0;

        Set<String> requestedDimensions = new java.util.HashSet<>();
        if (syncAll) {
            requestedDimensions.addAll(cacheLayout.sharedXaero() ? sharedDimensions : discoverDimensionsFromCache(cacheDir));
            LOGGER.info("Sync-all: discovered {} dimensions from cache", requestedDimensions.size());
        } else if (targetDimension != null && !targetDimension.isEmpty()) {
            requestedDimensions.add(targetDimension);
            LOGGER.debug("Single-dimension sync: {}", targetDimension);
        } else {
            for (String key : clientMeta.keySet()) {
                LOGGER.debug("Client meta key: {}", key);
                String[] keyParts = key.split("[/\\\\]");
                if (keyParts.length > 1) {
                    requestedDimensions.add(keyParts[0]);
                    if (key.contains("_placeholder_")) {
                        LOGGER.debug("Found placeholder for dimension {}, will sync all regions", keyParts[0]);
                    }
                }
            }
        }
        requestedDimensions.removeIf(dimension -> !DimensionPathMapping.isSafeXaeroFolder(dimension));
        LOGGER.debug("Requested dimensions (Xaero format): {}", requestedDimensions);

        Set<String> skippedDimensions = new HashSet<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        boolean hasValidDimension = false;
        boolean alreadyNotifiedMissingDim = false;

        for (String xaeroDim : requestedDimensions) {
            Path dimCacheDir = cacheDir.resolve(xaeroDim);
            if (cacheLayout.sharedXaero() && sharedDimensions.contains(xaeroDim)) {
                hasValidDimension = true;
            } else if (!cacheLayout.sharedXaero() && Files.exists(dimCacheDir) && dimCacheDir.toFile().isDirectory()) {
                try (Stream<Path> stream = Files.walk(dimCacheDir)) {
                    boolean hasZipFiles = stream.anyMatch(p -> p.toString().endsWith(".zip"));
                    if (hasZipFiles) {
                        hasValidDimension = true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to check dimension {} cache directory", xaeroDim, e);
                }
            } else if (!syncAll && !cacheLayout.sharedXaero()) {
                String friendlyDim = dimMapping.toServerDimension(xaeroDim);
                enqueueIfCurrent(server, playerId, syncVersion, player ->
                        player.sendSystemMessage(ChatUtils.error(
                                "mapsyncer.server.dim_not_available",
                                friendlyDim,
                                CacheCommandHandler.serverCommandPrefix(),
                                friendlyDim)));
                alreadyNotifiedMissingDim = true;
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            } else {
                LOGGER.debug("Sync-all: skipping dimension {} with no cache", xaeroDim);
            }
        }

        if (!hasValidDimension) {
            LOGGER.debug("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            if (cacheLayout.sharedXaero()) {
                LOGGER.warn("Host Xaero map has no requested region ZIPs: root={}, selections={}, requested={}",
                        cacheDir, cacheLayout.selections(), requestedDimensions);
            }
            String prefix = CacheCommandHandler.serverCommandPrefix();
            boolean skipChat = alreadyNotifiedMissingDim;
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                if (!skipChat) {
                    if (cacheLayout.sharedXaero()) {
                        player.sendSystemMessage(ChatUtils.message("mapsyncer.server.host_map_not_ready"));
                    } else if (targetDimension != null && !targetDimension.isEmpty()) {
                        String friendlyDim = dimMapping.toServerDimension(targetDimension);
                        player.sendSystemMessage(ChatUtils.error(
                                "mapsyncer.server.dim_not_available",
                                friendlyDim, prefix, friendlyDim));
                    } else {
                        player.sendSystemMessage(ChatUtils.message(
                                "mapsyncer.server.no_cache", prefix));
                    }
                }
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
                finalizePlayerSync(server, playerId, syncVersion);
            });
            return;
        }

        List<RegionSyncInfo> regionsToSync = new ArrayList<>();

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();
        List<Path> allZipPaths;
        if (cacheLayout.sharedXaero()) {
            allZipPaths = sharedFiles;
        } else {
            try (Stream<Path> stream = Files.walk(absCacheDir)) {
                allZipPaths = stream.filter(p -> p.toString().endsWith(".zip")).toList();
            } catch (IOException e) {
                LOGGER.error("Failed to walk cache directory", e);
                allZipPaths = List.of();
            }
        }

        Set<String> eligibleRegionPaths = new HashSet<>();
        allZipPaths.forEach(zipPath -> {
                        String relativePath = absCacheDir.relativize(zipPath).toString();
                        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");
                        normalizedPath = cacheLayout.sharedXaero() ? cacheLayout.regionKey(zipPath)
                                : stripMwWorldId(normalizedPath);

                        String[] parts = normalizedPath.split("[/\\\\]");
                        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

                        String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
                        if (!normalizedXaeroDim.equals(xaeroDimName)) {
                            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
                        }

                        if (!requestedDimensions.contains(normalizedXaeroDim)) {
                            if (!skippedDimensions.contains(normalizedXaeroDim)) {
                                skippedDimensions.add(normalizedXaeroDim);
                                LOGGER.debug("Skipping dimension {}: not requested", normalizedXaeroDim);
                            }
                            return;
                        }

                        RegionSyncInfo candidate = parseRegionInfo(zipPath, normalizedPath, 0);
                        if (candidate == null || !radiusPolicy.allows(candidate.dimension(), candidate.regionX(), candidate.regionZ())) {
                            return;
                        }

                        TimestampHashEntry serverMeta = serverCache.get(normalizedPath);
                        ClientMeta clientMetaEntry = clientMeta.get(normalizedPath);

                        if (!HashUtils.isValidRegionZip(zipPath)) {
                            if (serverMeta != null && genCache != null) {
                                genCache.remove(normalizedPath);
                            }
                            return;
                        }
                        eligibleRegionPaths.add(normalizedPath);

                        String serverHash;
                        long timestamp;
                        if (serverMeta == null) {
                            try {
                                if (cacheLayout.sharedXaero()) {
                                    TimestampHashEntry actual = cacheLayout.metadata(zipPath);
                                    serverHash = actual.hash();
                                    timestamp = actual.timestampSeconds();
                                } else {
                                    serverHash = HashUtils.computeFileHash(zipPath);
                                    timestamp = System.currentTimeMillis() / 1000;
                                }
                            } catch (IOException e) {
                                LOGGER.debug("Map changed during sync scan: {}", zipPath, e);
                                return;
                            }
                        } else {
                            serverHash = serverMeta.hash();
                            timestamp = serverMeta.timestampSeconds();
                        }

                        if (RegionSyncPolicy.shouldTransfer(serverHash, timestamp, clientMetaEntry)) {
                            RegionSyncInfo info = parseRegionInfo(zipPath, normalizedPath, timestamp);
                            if (info != null) {
                                regionsToSync.add(info);
                            }
                        }
                    });

        if (genCache != null) genCache.save();

        for (Map.Entry<String, TimestampHashEntry> entry : serverCache.entrySet()) {
            if (!eligibleRegionPaths.contains(entry.getKey())) continue;
            ClientMeta cm = clientMeta.get(entry.getKey());
            if (cm != null && entry.getValue().hash().equals(cm.hash())) {
                hashMatchCount++;
            } else if (cm != null && cm.timestampSeconds() >= entry.getValue().timestampSeconds()) {
                timestampSkipCount++;
            }
        }

        int total = regionsToSync.size();
        final int finalHashMatchCount = hashMatchCount;
        final int finalTimestampSkipCount = timestampSkipCount;

        LOGGER.info("Sync request from player {}: {} regions to sync, {} hash match, {} timestamp skip",
                playerId, total, finalHashMatchCount, finalTimestampSkipCount);

        if (total == 0) {
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                if (!silent) {
                    player.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", finalHashMatchCount, finalTimestampSkipCount));
                }
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(List.of(), true, worldId, "uptodate"));
                finalizePlayerSync(server, playerId, syncVersion);
            });
            return;
        }

        sortByViewDistancePriority(regionsToSync, startBlockX, startBlockZ, viewDistanceRegions);

        final int initialTotal = total;
        final int initialHashMatch = hashMatchCount;
        final int initialTimestampSkip = timestampSkipCount;
        enqueueIfCurrent(server, playerId, syncVersion, player -> {
                if (!silent) {
                    player.sendSystemMessage(ChatUtils.message("mapsyncer.server.sync_start", initialTotal, initialHashMatch, initialTimestampSkip));
                }
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(0, initialTotal, "Sync started"));
        });

        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;
        int processed = 0;
        int batchRegionCount = 0;
        int sentRegionCount = 0;
        int failedReadCount = 0;
        int batchThreshold = getBatchThreshold();

        for (RegionSyncInfo info : regionsToSync) {
            if (!isPlayerStillValid(server, playerId, startDimension, syncVersion)) {
                LOGGER.info("Player {} disconnected during sync", playerId);
                finalizePlayerSync(server, playerId, syncVersion);
                return;
            }

            ChunkMapData chunk = readRegionData(info);
            if (chunk == null) {
                failedReadCount++;
                LOGGER.warn("Failed to read region data: {}", info.normalizedPath());
                continue;
            }

            ChunkMapData[] parts = ChunkMapData.split(chunk);
            for (ChunkMapData part : parts) {
                if (batchBytes + part.data.length > batchThreshold && !batch.isEmpty()) {
                    if (!applySpeedLimit(batchBytes, server, playerId, startDimension, syncVersion)) {
                        LOGGER.info("Player {} disconnected during speed limit, aborting sync", playerId);
                        finalizePlayerSync(server, playerId, syncVersion);
                        return;
                    }

                    sendBatchInChunks(batch, batchBytes, server, worldId, processed, total, playerId, syncVersion);
                    processed += batchRegionCount;
                    sentRegionCount += batchRegionCount;

                    batch.clear();
                    batchBytes = 0;
                    batchRegionCount = 0;
                }

                batch.add(part);
                batchBytes += part.data.length;
            }
            batchRegionCount++;
        }

        if (!isPlayerStillValid(server, playerId, startDimension, syncVersion)) {
            LOGGER.info("Player {} disconnected before final batch", playerId);
            finalizePlayerSync(server, playerId, syncVersion);
            return;
        }

        final int finalSentCount = sentRegionCount + batchRegionCount;
        final int finalFailedCount = failedReadCount;
        final int finalTotal = total;
        final String completeStatus = finalFailedCount > 0 ? "partial" : "ok";

        if (!batch.isEmpty()) {
            if (!applySpeedLimit(batchBytes, server, playerId, startDimension, syncVersion)) {
                LOGGER.info("Player {} disconnected during final speed limit, aborting sync", playerId);
                finalizePlayerSync(server, playerId, syncVersion);
                return;
            }

            final int maxPacketSize = getMaxPacketSize();
            if (batchBytes <= maxPacketSize) {
                final List<ChunkMapData> finalBatch = new ArrayList<>(batch);
                enqueueIfCurrent(server, playerId, syncVersion, player -> {
                    NetworkManager.sendToPlayer(player,
                            new SyncResponsePayload(finalBatch, true, worldId, completeStatus));
                    NetworkManager.sendToPlayer(player,
                            new SyncProgressPayload(finalTotal, finalTotal, "completed"));
                    if (!silent) {
                        sendSyncCompleteMessage(player, finalSentCount, finalFailedCount, finalTotal);
                    }
                    finalizePlayerSync(server, playerId, syncVersion);
                });
            } else {
                List<ChunkMapData> currentChunk = new ArrayList<>();
                int currentSize = 0;

                for (ChunkMapData chunk : batch) {
                    if (currentSize + chunk.data.length > maxPacketSize && !currentChunk.isEmpty()) {
                        final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
                        final int sentProgress = processed;
                        enqueueIfCurrent(server, playerId, syncVersion, player -> {
                            NetworkManager.sendToPlayer(player,
                                    new SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                            NetworkManager.sendToPlayer(player,
                                    new SyncProgressPayload(sentProgress, finalTotal,
                                            String.format("Sending regions %d/%d", sentProgress, finalTotal)));
                        });

                        currentChunk.clear();
                        currentSize = 0;
                    }

                    currentChunk.add(chunk);
                    currentSize += chunk.data.length;
                }

                if (!currentChunk.isEmpty()) {
                    final List<ChunkMapData> lastChunk = new ArrayList<>(currentChunk);
                    enqueueIfCurrent(server, playerId, syncVersion, player -> {
                        NetworkManager.sendToPlayer(player,
                                new SyncResponsePayload(lastChunk, true, worldId, completeStatus));
                        NetworkManager.sendToPlayer(player,
                                new SyncProgressPayload(finalTotal, finalTotal, "completed"));
                        if (!silent) {
                            sendSyncCompleteMessage(player, finalSentCount, finalFailedCount, finalTotal);
                        }
                        finalizePlayerSync(server, playerId, syncVersion);
                    });
                }
            }
        } else {
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(finalTotal, finalTotal, "completed"));
                if (!silent) {
                    sendSyncCompleteMessage(player, finalSentCount, finalFailedCount, finalTotal);
                }
                finalizePlayerSync(server, playerId, syncVersion);
            });
        }

        LOGGER.info("Map sync complete for player {}: {} regions sent, {} failed", playerId, finalSentCount, finalFailedCount);
    }

    private static RegionSyncInfo parseRegionInfo(Path zipPath, String normalizedPath, long timestampSeconds) {
        try {
            String[] parts = normalizedPath.split("[/\\\\]");

            String dimension;
            int caveLayer = Integer.MAX_VALUE;
            String fileName;

            if (parts.length >= 4 && parts[1].equals("caves")) {
                dimension = parts[0];
                caveLayer = Integer.parseInt(parts[2]);
                fileName = parts[3];
            } else {
                dimension = parts[0];
                fileName = parts[parts.length - 1];
            }

            String[] coords = fileName.split("_");
            if (coords.length != 2) return null;
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);

            return new RegionSyncInfo(zipPath, normalizedPath, timestampSeconds, regionX, regionZ, dimension, caveLayer);
        } catch (NumberFormatException e) {
            LOGGER.error("Failed to parse path: {}", normalizedPath, e);
            return null;
        }
    }

    private static ChunkMapData readRegionData(RegionSyncInfo info) {
        try {
            byte[] data = Files.readAllBytes(info.zipPath());
            return new ChunkMapData(info.regionX(), info.regionZ(), info.dimension(),
                    data, info.timestampSeconds(), info.caveLayer());
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", info.zipPath(), e);
            return null;
        }
    }

    public static void cleanup() {
        for (Thread thread : syncThreads.values()) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
        syncingPlayers.clear();
        playerSyncDimensions.clear();
        ServerSyncSession.clearAllVersions();
        syncThreads.clear();
        speedLimitBytesSent.clear();
        speedLimitCycleStart.clear();
        requestPartBuffer.clear();
        requestTotalParts.clear();
        requestPartLastActivity.clear();
        requestPartFirstActivity.clear();
        activeSyncCount.set(0);
        ScheduledExecutorService timer = partAssemblyTimer;
        if (timer != null) {
            timer.shutdownNow();
            partAssemblyTimer = null;
        }
        LOGGER.debug("ServerSyncHandler tracking data cleared");
    }

    public static void cleanupOfflinePlayers(Set<UUID> onlinePlayerIds) {

        Set<UUID> toRemove = new HashSet<>();
        for (UUID playerId : syncingPlayers) {
            if (!onlinePlayerIds.contains(playerId)) {
                toRemove.add(playerId);
            }
        }

        for (UUID playerId : toRemove) {
            LOGGER.debug("Cleaning up stale state for offline player {}", playerId);
            finalizePlayerSync(playerId);
        }

        cleanupCompletedThreads();

        if (!toRemove.isEmpty()) {
            LOGGER.debug("Cleaned up {} stale player states", toRemove.size());
        }
    }

    private static void cleanupCompletedThreads() {
        // A terminated worker may still have final packets queued on the server thread.
        // Only the terminal callback (or disconnect) may invalidate its version.
        ServerSyncSession.removeCompletedThreads(syncThreads);
    }

    private static void sortByViewDistancePriority(List<RegionSyncInfo> regions, int startBlockX, int startBlockZ, int viewDistanceRegions) {

        int playerChunkX = startBlockX >> 4;
        int playerChunkZ = startBlockZ >> 4;
        int playerRegionX = playerChunkX >> 5;
        int playerRegionZ = playerChunkZ >> 5;

        LOGGER.debug("Player region: ({}, {}), view distance regions: ~{}",
                playerRegionX, playerRegionZ, viewDistanceRegions);

        regions.sort((a, b) -> {
            int distA = Math.max(Math.abs(a.regionX() - playerRegionX), Math.abs(a.regionZ() - playerRegionZ));
            int distB = Math.max(Math.abs(b.regionX() - playerRegionX), Math.abs(b.regionZ() - playerRegionZ));

            boolean aInView = distA <= viewDistanceRegions;
            boolean bInView = distB <= viewDistanceRegions;

            if (aInView && !bInView) return -1;
            if (!aInView && bInView) return 1;
            return Integer.compare(distA, distB);
        });

        int viewRegionCount = 0;
        for (RegionSyncInfo info : regions) {
            int dist = Math.max(Math.abs(info.regionX() - playerRegionX), Math.abs(info.regionZ() - playerRegionZ));
            if (dist <= viewDistanceRegions) {
                viewRegionCount++;
            }
        }

        LOGGER.debug("Sorted {} regions: {} in view distance ({} region radius), rest by distance",
                regions.size(), viewRegionCount, viewDistanceRegions);
    }

    private static String stripMwWorldId(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[1].startsWith("mw$")) {
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 2; i < parts.length; i++) {
                sb.append("/").append(parts[i]);
            }
            return sb.toString();
        }
        return path;
    }
}
