package com.mapsyncer.mca;

import com.mapsyncer.nbt.NbtReader;
import com.mapsyncer.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import com.mapsyncer.net.jpountz.lz4.LZ4BlockInputStream;

public class McaReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaReader.class);

    private static final int SECTOR_SIZE = 4096;

    private static final int CHUNKS_PER_REGION = 32;

    private static final int COMPRESS_GZIP = 1;

    private static final int COMPRESS_ZLIB = 2;

    private static final int COMPRESS_NONE = 3;

    private static final int COMPRESS_LZ4 = 4;

    public record ChunkLocation(int offsetSectors, int sectorCount, int timestamp) {

        public boolean exists() {
            return offsetSectors > 0 && sectorCount > 0;
        }

        public long dataOffset() {
            return (long) offsetSectors * SECTOR_SIZE;
        }
    }

    public record ChunkData(int chunkX, int chunkZ, Tag.Compound nbt) {}

    private final RandomAccessFile raf;

    private McaReader(RandomAccessFile raf) {
        this.raf = raf;
    }

    public static McaReader open(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        try {
            if (raf.length() < SECTOR_SIZE * 2) {
                throw new IOException("MCA file is too small: " + raf.length() + " bytes");
            }
            return new McaReader(raf);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
    }

    public ChunkLocation getChunkLocation(int localX, int localZ) throws IOException {
        int index = (localX + localZ * CHUNKS_PER_REGION) * 4;
        raf.seek(index);

        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int offsetSectors = (b0 << 16) | (b1 << 8) | b2;
        int sectorCount = raf.readUnsignedByte();

        raf.seek(SECTOR_SIZE + index);
        int timestamp = raf.readInt();

        return new ChunkLocation(offsetSectors, sectorCount, timestamp);
    }

    public Tag.Compound readChunkNbt(int localX, int localZ) throws IOException {
        ChunkLocation loc = getChunkLocation(localX, localZ);
        if (!loc.exists()) {
            return null;
        }

        long dataOffset = loc.dataOffset();
        if (dataOffset + 5 > raf.length()) {
            return null;
        }

        raf.seek(dataOffset);

        int totalLength = raf.readInt();
        if (totalLength <= 1) {
            return null;
        }

        int compressionType = raf.readUnsignedByte();

        int dataLength = totalLength - 1;
        byte[] compressedData = new byte[dataLength];
        int read = 0;
        while (read < dataLength) {
            int r = raf.read(compressedData, read, dataLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read != dataLength) {
            return null;
        }

        byte[] nbtData = decompress(compressedData, compressionType);
        if (nbtData == null) {
            return null;
        }

        try (NbtReader reader = new NbtReader(new ByteArrayInputStream(nbtData))) {
            return reader.readDocument();
        }
    }

    public void forEachChunk(java.util.function.Consumer<ChunkData> consumer) throws IOException {
        for (int localX = 0; localX < CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < CHUNKS_PER_REGION; localZ++) {
                try {
                    Tag.Compound nbt = readChunkNbt(localX, localZ);
                    if (nbt != null) {
                        consumer.accept(new ChunkData(localX, localZ, nbt));
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to read chunk ({}, {}): {}", localX, localZ, e.getMessage());
                }
            }
        }
    }

    @Deprecated
    public Iterable<ChunkData> readAllChunks() throws IOException {
        java.util.List<ChunkData> chunks = new java.util.ArrayList<>();
        forEachChunk(chunks::add);
        return chunks;
    }

    private byte[] decompress(byte[] data, int compressionType) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        switch (compressionType) {
            case COMPRESS_GZIP:
                try (GZIPInputStream gis = new GZIPInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = gis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            case COMPRESS_ZLIB:
                try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = iis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            case COMPRESS_NONE:
                return data;

            case COMPRESS_LZ4:
                try (LZ4BlockInputStream lis = new LZ4BlockInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = lis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                    }
                }
                return baos.toByteArray();

            default:
                throw new IOException("Unknown compression type: " + compressionType);
        }
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }
}
