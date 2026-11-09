package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

public final class PropertiesCacheIO {

    private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesCacheIO.class);

    private static final Object CACHE_FILE_LOCK = new Object();

    private PropertiesCacheIO() {

    }

    public static <T> Map<String, T> load(Path cacheFile, Function<String, T> parser) {
        Map<String, T> cache = new HashMap<>();

        if (cacheFile == null || !Files.exists(cacheFile)) {
            LOGGER.info("Cache file not found: {}", cacheFile);
            return cache;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                T value = parser.apply(props.getProperty(key));
                if (value != null) {
                    cache.put(key, value);
                } else {
                    LOGGER.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} entries from cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to load cache file: {}", cacheFile, e);
        }

        return cache;
    }

    public static <T> void save(Path cacheFile, Map<String, T> cache, Function<T, String> formatter, String header) {
        if (cacheFile == null) {
            LOGGER.warn("Cache file path is null, skip saving");
            return;
        }

        synchronized (PropertiesCacheIO.class) {
            try {
                Files.createDirectories(cacheFile.getParent());

                Properties props = new Properties();
                for (Map.Entry<String, T> entry : cache.entrySet()) {
                    props.setProperty(entry.getKey(), formatter.apply(entry.getValue()));
                }

                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                props.store(buffer, header != null ? header : "Cache file");
                writeAtomically(cacheFile, buffer.toByteArray());

                LOGGER.info("Saved {} entries to cache file: {}", cache.size(), cacheFile.getFileName());
            } catch (IOException e) {
                LOGGER.error("Failed to save cache file: {}", cacheFile, e);
            }
        }
    }

    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Cache file has no parent directory: " + target);
        }
        Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temp, data);
            synchronized (CACHE_FILE_LOCK) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {

            }
        }
    }

    public static TimestampHashEntry parseTimestampHash(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        String[] parts = value.split(":");
        if (parts.length == 2) {
            try {
                long ts = Long.parseLong(parts[0]);
                return new TimestampHashEntry(ts, parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public record TimestampHashEntry(long timestampSeconds, String hash) {

        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }
}
