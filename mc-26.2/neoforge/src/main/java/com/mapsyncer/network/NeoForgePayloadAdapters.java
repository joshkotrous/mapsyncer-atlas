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
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NeoForgePayloadAdapters {

    public record NeoForgeSyncRequestPayload(SyncRequestPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeSyncRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_REQUEST_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeSyncRequestPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeSyncRequestPayload::encode, NeoForgeSyncRequestPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeSyncRequestPayload payload) {
            SyncRequestWireCodec.write(buf, payload.data());
        }

        public static NeoForgeSyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeSyncRequestPayload(SyncRequestWireCodec.read(buf));
        }
    }

    public record NeoForgeSyncResponsePayload(SyncResponsePayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeSyncResponsePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_RESPONSE_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeSyncResponsePayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeSyncResponsePayload::encode, NeoForgeSyncResponsePayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeSyncResponsePayload payload) {
            buf.writeInt(payload.data.worldId());
            buf.writeInt(payload.data.chunks().size());
            for (ChunkMapData chunk : payload.data.chunks()) {
                encodeChunkMapData(buf, chunk);
            }
            buf.writeBoolean(payload.data.isComplete());
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeSyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(decodeChunkMapData(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new NeoForgeSyncResponsePayload(new SyncResponsePayload(chunks, isComplete, worldId, status));
        }
    }

    public record NeoForgeSyncProgressPayload(SyncProgressPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeSyncProgressPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SYNC_PROGRESS_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeSyncProgressPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeSyncProgressPayload::encode, NeoForgeSyncProgressPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeSyncProgressPayload payload) {
            buf.writeInt(payload.data.processed());
            buf.writeInt(payload.data.total());
            buf.writeUtf(payload.data.status());
        }

        public static NeoForgeSyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeSyncProgressPayload(new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf()));
        }
    }

    public record NeoForgeServerInstalledPayload(ServerInstalledPayload data) implements CustomPacketPayload {
        public static final Type<NeoForgeServerInstalledPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MapSyncer.MOD_ID, NetworkHandler.SERVER_INSTALLED_ID));

        public static final StreamCodec<RegistryFriendlyByteBuf, NeoForgeServerInstalledPayload> STREAM_CODEC =
            StreamCodec.of(NeoForgeServerInstalledPayload::encode, NeoForgeServerInstalledPayload::decode);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void encode(RegistryFriendlyByteBuf buf, NeoForgeServerInstalledPayload payload) {
            ServerInstalledWireCodec.write(buf, payload.data);
        }

        public static NeoForgeServerInstalledPayload decode(RegistryFriendlyByteBuf buf) {
            return new NeoForgeServerInstalledPayload(ServerInstalledWireCodec.read(buf));
        }
    }

    private static void encodeChunkMapData(RegistryFriendlyByteBuf buf, ChunkMapData data) {
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

    private static ChunkMapData decodeChunkMapData(RegistryFriendlyByteBuf buf) {
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
