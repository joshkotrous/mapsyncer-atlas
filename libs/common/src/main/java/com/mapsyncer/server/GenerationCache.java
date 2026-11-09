package com.mapsyncer.server;

import com.mapsyncer.config.CacheConfig;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GenerationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationCache.class);

    private static final int MAX_CACHE_REGIONS = CacheConfig.MAX_REGION_META_CACHE;

    private static volatile GenerationCache instance;

    private final Path cacheFile;

    private final Map<String, TimestampHashEntry> cache = new ConcurrentHashMap<>();

    private GenerationCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_cache.properties");

        load();
    }

    public static GenerationCache getInstance(Path cacheDir) {
        if (instance == null) {
            synchronized (GenerationCache.class) {
                if (instance == null) {
                    instance = new GenerationCache(cacheDir);
                }
            }
        }
        return instance;
    }

    private void load() {
        Map<String, TimestampHashEntry> loaded = PropertiesCacheIO.load(cacheFile, PropertiesCacheIO::parseTimestampHash);
        cache.putAll(loaded);
    }

    public synchronized void save() {
        PropertiesCacheIO.save(cacheFile, new HashMap<>(cache), TimestampHashEntry::format,
            "Generation cache for map regions\nFormat: dimension/region_x_z = timestamp_seconds:hash\nHash is CRC32 of file content");
    }

    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new TimestampHashEntry(timestampSeconds, hash));
        trimIfOverLimit();
    }

    private void trimIfOverLimit() {
        if (cache.size() <= MAX_CACHE_REGIONS) {
            return;
        }

        int toRemove = cache.size() - MAX_CACHE_REGIONS;
        LOGGER.info("Cache size {} exceeds limit {}, trimming {} oldest entries",
            cache.size(), MAX_CACHE_REGIONS, toRemove);

        cache.entrySet().stream()
            .sorted((a, b) -> Long.compare(a.getValue().timestampSeconds(), b.getValue().timestampSeconds()))
            .limit(toRemove)
            .map(Map.Entry::getKey)
            .forEach(cache::remove);

        LOGGER.info("Cache trimmed to {} entries", cache.size());
    }

    public void updateWithHash(String relativePath, Path filePath, long timestampSeconds) {
        String hash = HashUtils.computeFileHash(filePath);
        cache.put(relativePath, new TimestampHashEntry(timestampSeconds, hash));
        LOGGER.debug("Updated cache for {}: ts={}, hash={}", relativePath, timestampSeconds, hash);
    }

    public TimestampHashEntry getMeta(String relativePath) {
        return cache.get(relativePath);
    }

    public Map<String, TimestampHashEntry> getAll() {
        return Collections.unmodifiableMap(cache);
    }

    public boolean needsSync(String relativePath, TimestampHashEntry clientMeta) {
        TimestampHashEntry serverMeta = cache.get(relativePath);

        if (serverMeta == null) {
            return false;
        }

        if (clientMeta == null) {
            return true;
        }

        if (serverMeta.hash().equals(clientMeta.hash())) {
            LOGGER.debug("Skip sync {}: hash match", relativePath);
            return false;
        }

        if (clientMeta.timestampSeconds() >= serverMeta.timestampSeconds()) {
            LOGGER.debug("Skip sync {}: client ts {} >= server ts {}",
                    relativePath, clientMeta.timestampSeconds(), serverMeta.timestampSeconds());
            return false;
        }

        LOGGER.debug("Need sync {}: hash mismatch (client={}, server={})",
                relativePath, clientMeta.hash(), serverMeta.hash());
        return true;
    }

    public void remove(String relativePath) {
        if (relativePath != null) {
            cache.remove(relativePath);
        }
    }

    public int pruneInvalidEntries(Path cacheRoot) {
        if (cacheRoot == null || !Files.exists(cacheRoot)) {
            return 0;
        }
        int removed = 0;
        for (String key : List.copyOf(cache.keySet())) {
            Path zipPath = resolveZipPath(cacheRoot, key);
            if (zipPath == null || !HashUtils.isValidRegionZip(zipPath)) {
                cache.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            save();
            LOGGER.info("Pruned {} invalid generation_cache entries under {}", removed, cacheRoot);
        }
        return removed;
    }

    private static Path resolveZipPath(Path cacheRoot, String relativePath) {
        String normalized = relativePath.replace("\\", "/");
        String[] parts = normalized.split("/");
        if (parts.length < 2) {
            return null;
        }
        Path dimDir = cacheRoot.resolve(parts[0]);
        if (!Files.isDirectory(dimDir)) {
            return null;
        }
        String fileName = parts[parts.length - 1] + ".zip";

        Path flatPath;
        if (parts.length == 2) {
            flatPath = dimDir.resolve(fileName);
        } else if (parts.length == 4 && "caves".equals(parts[1])) {
            flatPath = dimDir.resolve("caves").resolve(parts[2]).resolve(fileName);
        } else {
            flatPath = null;
        }
        if (flatPath != null && Files.isRegularFile(flatPath)) {
            return flatPath;
        }

        Path mwDir;
        try (var stream = Files.list(dimDir)) {
            mwDir = stream.filter(p -> p.getFileName().toString().startsWith("mw$"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return flatPath;
        }
        if (mwDir == null) {
            return flatPath;
        }
        if (parts.length == 2) {
            return mwDir.resolve(fileName);
        }
        if (parts.length == 4 && "caves".equals(parts[1])) {
            return mwDir.resolve("caves").resolve(parts[2]).resolve(fileName);
        }
        return flatPath;
    }

    public long getLastGenerationTime() {
        return cache.values().stream()
            .mapToLong(TimestampHashEntry::timestampSeconds)
            .max().orElse(0);
    }

    public void clear() {
        cache.clear();
        save();
    }

    public int removeByPrefix(String prefix) {
        int removed = 0;
        for (String key : List.copyOf(cache.keySet())) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            save();
        }
        return removed;
    }

    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            LOGGER.info("GenerationCache instance reset");
        }
    }
}
