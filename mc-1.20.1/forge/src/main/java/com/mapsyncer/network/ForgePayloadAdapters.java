package com.mapsyncer.network;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.ServerInstalledWireCodec;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncRequestWireCodec;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ForgePayloadAdapters {

    public static class ForgeSyncRequestMessage {
        private final SyncRequestPayload data;

        public ForgeSyncRequestMessage(SyncRequestPayload data) {
            this.data = data;
        }

        public SyncRequestPayload getData() {
            return data;
        }

        public static void encode(ForgeSyncRequestMessage msg, FriendlyByteBuf buf) {
            SyncRequestWireCodec.write(buf, msg.data);
        }

        public static ForgeSyncRequestMessage decode(FriendlyByteBuf buf) {
            return new ForgeSyncRequestMessage(SyncRequestWireCodec.read(buf));
        }
    }

    public static class ForgeSyncResponseMessage {
        private final SyncResponsePayload data;

        public ForgeSyncResponseMessage(SyncResponsePayload data) {
            this.data = data;
        }

        public SyncResponsePayload getData() {
            return data;
        }

        public static void encode(ForgeSyncResponseMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.worldId());
            buf.writeInt(msg.data.chunks().size());
            for (ChunkMapData chunk : msg.data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(msg.data.isComplete());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeSyncResponseMessage decode(FriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(decodeChunkMapData(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new ForgeSyncResponseMessage(new SyncResponsePayload(chunks, isComplete, worldId, status));
        }
    }

    public static class ForgeSyncProgressMessage {
        private final SyncProgressPayload data;

        public ForgeSyncProgressMessage(SyncProgressPayload data) {
            this.data = data;
        }

        public SyncProgressPayload getData() {
            return data;
        }

        public static void encode(ForgeSyncProgressMessage msg, FriendlyByteBuf buf) {
            buf.writeInt(msg.data.processed());
            buf.writeInt(msg.data.total());
            buf.writeUtf(msg.data.status());
        }

        public static ForgeSyncProgressMessage decode(FriendlyByteBuf buf) {
            return new ForgeSyncProgressMessage(new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf()));
        }
    }

    public static class ForgeServerInstalledMessage {
        private final ServerInstalledPayload data;

        public ForgeServerInstalledMessage(ServerInstalledPayload data) {
            this.data = data;
        }

        public ServerInstalledPayload getData() {
            return data;
        }

        public static void encode(ForgeServerInstalledMessage msg, FriendlyByteBuf buf) {
            ServerInstalledWireCodec.write(buf, msg.data);
        }

        public static ForgeServerInstalledMessage decode(FriendlyByteBuf buf) {
            return new ForgeServerInstalledMessage(ServerInstalledWireCodec.read(buf));
        }
    }

    private static void encodeChunkMapData(FriendlyByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeUtf(data.dimension);
        buf.writeByteArray(data.data);
        buf.writeLong(data.timestampSeconds);

        boolean hasCaveLayer = data.caveLayer != Integer.MAX_VALUE;
        buf.writeBoolean(hasCaveLayer);
        if (hasCaveLayer) {
            buf.writeInt(data.caveLayer);
        }
        buf.writeBoolean(data.totalParts > 1);
        if (data.totalParts > 1) {
            buf.writeInt(data.partIndex);
            buf.writeInt(data.totalParts);
        }
    }

    private static ChunkMapData decodeChunkMapData(FriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampSeconds = buf.readLong();

        int caveLayer = Integer.MAX_VALUE;
        if (buf.isReadable()) {
            boolean hasCaveLayer = buf.readBoolean();
            if (hasCaveLayer) {
                caveLayer = buf.readInt();
            }
        }

        int partIndex = 0;
        int totalParts = 0;
        if (buf.isReadable()) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer, partIndex, totalParts);
    }
}
