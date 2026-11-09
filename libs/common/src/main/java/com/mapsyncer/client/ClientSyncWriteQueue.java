package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class ClientSyncWriteQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientSyncWriteQueue.class);

    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);

    private static volatile ExecutorService executor = null;

    private static volatile AtomicInteger pendingWrites = new AtomicInteger(0);

    private ClientSyncWriteQueue() {}

    private static ExecutorService getExecutor() {
        ExecutorService current = executor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (ClientSyncWriteQueue.class) {
            current = executor;
            if (current == null || current.isShutdown()) {
                executor = Executors.newFixedThreadPool(IO_THREADS, r -> {
                    Thread t = new Thread(r, "mapsyncer-sync-io");
                    t.setDaemon(true);
                    return t;
                });
                LOGGER.debug("ClientSyncWriteQueue executor (re)created");
            }
            return executor;
        }
    }

    public static boolean hasPendingWrites() {
        return pendingWrites.get() > 0;
    }

    public static void submit(ChunkMapData chunk, Path serverDir, int worldId,
            ClientTimestampCache tsCache, boolean integratedHost,
            String selectedMultiworld,
            Consumer<XaeroMapDataHandler.RegionWriteResult> callback) {
        final AtomicInteger writeCounter = pendingWrites;
        writeCounter.incrementAndGet();
        Runnable task = () -> {
            XaeroMapDataHandler.RegionWriteResult result = null;
            try {
                result = XaeroMapDataHandler.writeChunkData(chunk, serverDir, worldId, integratedHost, selectedMultiworld);
                if (result != null) {
                    if (tsCache != null) {
                        tsCache.update(
                                XaeroMapDataHandler.buildRelativePathForCache(chunk),
                                chunk.timestampSeconds,
                                result.crc32Hash());
                    }
                    XaeroMapDataHandler.clearRegionCacheFiles(
                            result.mwDir(),
                            new XaeroMapDataHandler.RegionCoord(chunk.regionX, chunk.regionZ, chunk.caveLayer));
                }
            } catch (Exception e) {
                LOGGER.error("Async region write failed for ({}, {})", chunk.regionX, chunk.regionZ, e);
            } finally {
                writeCounter.decrementAndGet();
                invokeCallback(chunk, callback, result);
            }
        };

        try {
            getExecutor().execute(task);
        } catch (RejectedExecutionException e) {
            writeCounter.decrementAndGet();
            LOGGER.error("Sync write queue rejected task for ({}, {}), executor shutdown?",
                    chunk.regionX, chunk.regionZ, e);
            invokeCallback(chunk, callback, null);
        }
    }

    private static void invokeCallback(ChunkMapData chunk,
            Consumer<XaeroMapDataHandler.RegionWriteResult> callback,
            XaeroMapDataHandler.RegionWriteResult result) {
        try {
            callback.accept(result);
        } catch (Exception e) {
            LOGGER.error("Sync write callback failed for ({}, {})", chunk.regionX, chunk.regionZ, e);
        }
    }

    public static void saveTimestampCacheAsync(ClientTimestampCache tsCache) {
        if (tsCache == null) {
            return;
        }
        try {
            getExecutor().execute(() -> {
                try {
                    tsCache.save();
                } catch (Exception e) {
                    LOGGER.warn("Async timestamp cache save failed", e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.warn("Async timestamp cache save rejected", e);
        }
    }

    public static void shutdown() {
        synchronized (ClientSyncWriteQueue.class) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
        pendingWrites = new AtomicInteger(0);
        LOGGER.debug("ClientSyncWriteQueue shutdown");
    }
}
