package com.mapsyncer.mca;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

public final class McaContentProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaContentProbe.class);

    private static final int SECTOR_SIZE = 4096;
    private static final int CHUNKS_PER_REGION = 32;

    private static final long HEADER_ONLY_SIZE = (long) SECTOR_SIZE * 2;

    private McaContentProbe() {}

    public static boolean hasAnyChunk(Path mcaPath) {
        if (mcaPath == null || !Files.exists(mcaPath)) {
            return false;
        }
        try {
            long size = Files.size(mcaPath);
            if (size <= HEADER_ONLY_SIZE) {
                return false;
            }
        } catch (IOException e) {
            LOGGER.debug("Cannot stat MCA {}: {}", mcaPath, e.getMessage());
            return false;
        }

        try (RandomAccessFile raf = new RandomAccessFile(mcaPath.toFile(), "r")) {
            if (raf.length() < HEADER_ONLY_SIZE) {
                return false;
            }

            byte[] locationTable = new byte[SECTOR_SIZE];
            raf.seek(0);
            raf.readFully(locationTable);
            for (int i = 0; i < CHUNKS_PER_REGION * CHUNKS_PER_REGION; i++) {
                int index = i * 4;
                int offsetSectors = ((locationTable[index] & 0xFF) << 16)
                                  | ((locationTable[index + 1] & 0xFF) << 8)
                                  | (locationTable[index + 2] & 0xFF);
                int sectorCount = locationTable[index + 3] & 0xFF;
                if (offsetSectors > 0 && sectorCount > 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            LOGGER.debug("MCA chunk probe failed for {}: {}", mcaPath, e.getMessage());
            return false;
        }
    }
}
