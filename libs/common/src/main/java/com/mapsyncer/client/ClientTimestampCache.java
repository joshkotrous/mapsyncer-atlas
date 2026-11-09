package com.mapsyncer.client;

import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimestampCache.class);

    private static final String CACHE_FILE_NAME = "sync_timestamps.cache";

    private static final String KEY_STATE = "_state";
    private static final String KEY_DIMENSIONS = "_dimensions";
    private static final String KEY_COMMAND = "_command";

    public static final String SYNC_STATE_IN_PROGRESS = "in_progress";

    public static final String SYNC_STATE_COMPLETED = "completed";

    private static volatile ClientTimestampCache instance;

    private static volatile Path lastBaseDir = null;

    public static Path getLastBaseDir() {
        return lastBaseDir;
    }

    private final Path cacheFile;

    private final Map<String, TimestampHashEntry> cache = new ConcurrentHashMap<>();

    private volatile String syncState = null;

    private volatile Set<String> syncDimensions = new HashSet<>();

    private volatile String syncCommand = "";

    private ClientTimestampCache(Path baseDir) {
        this.cacheFile = baseDir.resolve(CACHE_FILE_NAME);
        load();
    }

    public static ClientTimestampCache getInstance(Path baseDir) {
        if (baseDir == null) {
            return instance;
        }

        if (instance == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
            synchronized (ClientTimestampCache.class) {
                if (instance == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
                    instance = new ClientTimestampCache(baseDir);
                    lastBaseDir = baseDir;
                    LOGGER.info("ClientTimestampCache initialized for baseDir: {}", baseDir);
                }
            }
        }
        return instance;
    }

    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            lastBaseDir = null;
            LOGGER.info("ClientTimestampCache instance reset");
        }
    }

    private void load() {
        if (!Files.exists(cacheFile)) {
            syncState = null;
            LOGGER.info("Cache file not found, never synced before");
            return;
        }

        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(cacheFile)) {
                props.load(in);
            }

            syncState = props.getProperty(KEY_STATE, null);
            String dimsStr = props.getProperty(KEY_DIMENSIONS, "");
            syncDimensions = new HashSet<>();
            if (!dimsStr.isEmpty()) {
                for (String dim : dimsStr.split(",")) {
                    syncDimensions.add(dim.trim());
                }
            }
            syncCommand = props.getProperty(KEY_COMMAND, "");

            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("_")) {
                    TimestampHashEntry entry = PropertiesCacheIO.parseTimestampHash(props.getProperty(key));
                    if (entry != null) {
                        cache.put(key, entry);
                    }
                }
            }

            LOGGER.info("Loaded cache: state={}, regions={}, file={}", syncState, cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Failed to load cache file: {}", e.getMessage());
            syncState = null;
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            StringBuilder content = new StringBuilder();
            content.append("# Sync timestamps cache\n");
            content.append("# ==================== STATE ====================\n");
            if (syncState != null) {
                content.append(KEY_STATE).append("=").append(syncState).append("\n");
            }
            content.append(KEY_DIMENSIONS).append("=").append(String.join(",", syncDimensions)).append("\n");
            content.append(KEY_COMMAND).append("=").append(syncCommand).append("\n");
            content.append("\n");
            content.append("# ==================== TIMESTAMP CACHE ====================\n");
            content.append("# Format: dimension/region_x_z = timestamp_seconds:hash\n");

            for (Map.Entry<String, TimestampHashEntry> entry : cache.entrySet()) {
                content.append(entry.getKey()).append("=").append(entry.getValue().format()).append("\n");
            }

            PropertiesCacheIO.writeAtomically(cacheFile,
                    content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

            LOGGER.debug("Saved cache: state={}, regions={}", syncState, cache.size());
        } catch (IOException e) {
            LOGGER.warn("Failed to save cache file: {}", e.getMessage());
        }
    }

    public void markSyncStart(Set<String> dimensions, String command) {
        syncState = SYNC_STATE_IN_PROGRESS;
        syncDimensions = new HashSet<>(dimensions);
        syncCommand = command;
        save();
        LOGGER.info("Marked sync start: dimensions={}, command={}", dimensions, command);
    }

    public void markSyncComplete() {
        syncState = SYNC_STATE_COMPLETED;
        save();
        LOGGER.info("Marked sync complete");
    }

    public void clearSyncState() {
        syncState = SYNC_STATE_COMPLETED;
        syncDimensions.clear();
        syncCommand = "";
        save();
        LOGGER.info("Cleared sync state (marked as completed)");
    }

    public String getSyncState() {
        return syncState;
    }

    public String getSyncCommand() {
        return syncCommand;
    }

    public boolean needsResume() {
        return SYNC_STATE_IN_PROGRESS.equals(syncState);
    }

    public Set<String> getSyncDimensions() {
        return new HashSet<>(syncDimensions);
    }

    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new TimestampHashEntry(timestampSeconds, hash));
    }

    public void remove(String relativePath) {
        if (relativePath != null) {
            cache.remove(relativePath);
        }
    }

    public TimestampHashEntry get(String relativePath) {
        return cache.get(relativePath);
    }

    public Map<String, TimestampHashEntry> getAll() {
        return Collections.unmodifiableMap(cache);
    }

    public void clear() {
        cache.clear();
        syncState = null;
        syncDimensions.clear();
        syncCommand = "";
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.info("Cleared cache");
        } catch (IOException e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    public boolean hasDimensionSynced(String xaeroDim) {
        String prefix = xaeroDim + "/";
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean cacheFileExists() {
        return Files.exists(cacheFile);
    }

    public Path getCacheFile() {
        return cacheFile;
    }
}
