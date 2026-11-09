package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.network.payload.ServerInstalledWireCodec;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncRequestWireCodec;
import com.mapsyncer.network.payload.SyncResponsePayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FabricPayloadAdapters {

    public static final ResourceLocation SYNC_REQUEST_ID = new ResourceLocation(MapSyncer.MOD_ID, "sync_request");
    public static final ResourceLocation SYNC_RESPONSE_ID = new ResourceLocation(MapSyncer.MOD_ID, "sync_response");
    public static final ResourceLocation SYNC_PROGRESS_ID = new ResourceLocation(MapSyncer.MOD_ID, "sync_progress");
    public static final ResourceLocation SERVER_INSTALLED_ID = new ResourceLocation(MapSyncer.MOD_ID, "server_installed");

    public static void writeSyncRequest(FriendlyByteBuf buf, SyncRequestPayload payload) {
        SyncRequestWireCodec.write(buf, payload);
    }

    public static SyncRequestPayload readSyncRequest(FriendlyByteBuf buf) {
        return SyncRequestWireCodec.read(buf);
    }

    public static void writeSyncResponse(FriendlyByteBuf buf, SyncResponsePayload payload) {
        buf.writeInt(payload.worldId());
        buf.writeInt(payload.chunks().size());
        for (ChunkMapData chunk : payload.chunks()) {
            writeChunkMapData(buf, chunk);
        }
        buf.writeBoolean(payload.isComplete());
        buf.writeUtf(payload.status());
    }

    public static SyncResponsePayload readSyncResponse(FriendlyByteBuf buf) {
        int worldId = buf.readInt();
        int size = buf.readInt();
        List<ChunkMapData> chunks = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            chunks.add(readChunkMapData(buf));
        }
        boolean isComplete = buf.readBoolean();
        String status = buf.readUtf();
        return new SyncResponsePayload(chunks, isComplete, worldId, status);
    }

    public static void writeSyncProgress(FriendlyByteBuf buf, SyncProgressPayload payload) {
        buf.writeInt(payload.processed());
        buf.writeInt(payload.total());
        buf.writeUtf(payload.status());
    }

    public static SyncProgressPayload readSyncProgress(FriendlyByteBuf buf) {
        return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
    }

    public static void writeServerInstalled(FriendlyByteBuf buf, ServerInstalledPayload payload) {
        ServerInstalledWireCodec.write(buf, payload);
    }

    public static ServerInstalledPayload readServerInstalled(FriendlyByteBuf buf) {
        return ServerInstalledWireCodec.read(buf);
    }

    private static void writeChunkMapData(FriendlyByteBuf buf, ChunkMapData data) {
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

    private static ChunkMapData readChunkMapData(FriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampSeconds = buf.readLong();

        int caveLayer = Integer.MAX_VALUE;
        if (buf.readableBytes() > 0) {
            boolean hasCaveLayer = buf.readBoolean();
            if (hasCaveLayer) {
                caveLayer = buf.readInt();
            }
        }

        int partIndex = 0;
        int totalParts = 0;
        if (buf.readableBytes() > 0) {
            boolean isSplit = buf.readBoolean();
            if (isSplit) {
                partIndex = buf.readInt();
                totalParts = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer, partIndex, totalParts);
    }
}
