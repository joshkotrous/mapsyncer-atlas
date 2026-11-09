package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

public final class HashUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashUtils.class);

    public static final String DEFAULT_HASH = "00000000";

    private HashUtils() {

    }

    public static String computeFileHash(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[8192];

        try (InputStream is = Files.newInputStream(filePath)) {
            int len;
            while ((len = is.read(buffer)) != -1) {
                crc32.update(buffer, 0, len);
            }
            return String.format("%08x", crc32.getValue());
        } catch (IOException e) {
            LOGGER.warn("Failed to compute hash for {}", filePath, e);
            return DEFAULT_HASH;
        }
    }

    public static String computeHash(byte[] data) {
        if (data == null || data.length == 0) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return String.format("%08x", crc32.getValue());
    }

    public static boolean isValidHash(String hash) {
        return hash != null && !hash.isEmpty() && !DEFAULT_HASH.equals(hash);
    }

    public static boolean isValidRegionZip(byte[] data) {
        if (data == null || data.length < 22) {
            return false;
        }
        if (data[0] != 0x50 || data[1] != 0x4B || data[2] != 0x03 || data[3] != 0x04) {
            return false;
        }
        for (int i = data.length - 22; i >= 0; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4B && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                return true;
            }
        }
        return false;
    }

    public static boolean isValidRegionZip(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            return false;
        }
        try {
            return isValidRegionZip(Files.readAllBytes(filePath));
        } catch (IOException e) {
            LOGGER.warn("Failed to read zip for validation: {}", filePath, e);
            return false;
        }
    }
}
