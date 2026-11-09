package com.mapsyncer.server;

import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CheckedOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XaeroWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroWriter.class);

    private static final long TEMP_FILE_MAX_AGE_MS = 24 * 60 * 60 * 1000;

    public static int cleanStaleTempFiles(Path rootDir) {
        if (!Files.exists(rootDir)) return 0;
        long cutoff = System.currentTimeMillis() - TEMP_FILE_MAX_AGE_MS;
        int[] count = {0};
        try (var stream = Files.walk(rootDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".zip.temp"))
                  .forEach(p -> {
                      try {
                          if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                              Files.deleteIfExists(p);
                              count[0]++;
                              LOGGER.debug("Cleaned stale temp file: {}", p);
                          }
                      } catch (IOException ignored) {

                      }
                  });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan for stale temp files in {}", rootDir, e);
        }
        if (count[0] > 0) {
            LOGGER.info("Cleaned {} stale .temp files from {}", count[0], rootDir);
        }
        return count[0];
    }

    public record RegionWriteResult(Path path, String crc32Hash) {}

    public static RegionWriteResult writeRegionFile(Path outputDir, ConvertedRegion region) throws IOException {
        Files.createDirectories(outputDir);

        String fileName = region.regionX() + "_" + region.regionZ();
        Path tempFile = Files.createTempFile(outputDir, fileName + ".zip.", ".mapsyncer.tmp");
        Path finalFile = outputDir.resolve(fileName + ".zip");

        CRC32 crc32 = new CRC32();
        try {
            try (OutputStream fileOut = Files.newOutputStream(tempFile);
                 CheckedOutputStream checkedOut = new CheckedOutputStream(fileOut, crc32);
                 ZipOutputStream zos = new ZipOutputStream(checkedOut)) {
                ZipEntry entry = new ZipEntry("region.xaero");
                zos.putNextEntry(entry);
                zos.write(region.xaeroData());
                zos.closeEntry();
            }

            try {
                Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return new RegionWriteResult(finalFile, String.format("%08x", crc32.getValue()));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public static boolean regionFileExists(Path outputDir, int regionX, int regionZ) {
        Path zipFile = outputDir.resolve(regionX + "_" + regionZ + ".zip");
        return Files.exists(zipFile);
    }
}
