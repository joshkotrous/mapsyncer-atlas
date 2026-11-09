package com.mapsyncer.network.payload;

import com.mapsyncer.platform.UpdateMode;
import net.minecraft.network.FriendlyByteBuf;

public final class ServerInstalledWireCodec {

    private ServerInstalledWireCodec() {}

    public static void write(FriendlyByteBuf buf, ServerInstalledPayload payload) {
        buf.writeUtf(payload.version());
        buf.writeLong(payload.lastGenerationTimestamp());
        buf.writeInt(payload.autoSyncIntervalMinutes());
        buf.writeByte(payload.updateMode().ordinal());
        buf.writeInt(payload.incrementalUpdateIntervalTicks());

        String serverName = payload.serverName();
        buf.writeUtf(serverName != null ? serverName : "");
    }

    public static ServerInstalledPayload read(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        long lastGen = buf.readLong();
        int intervalMinutes = buf.readInt();
        UpdateMode mode = readUpdateMode(buf.readByte());
        int intervalTicks = buf.readInt();

        String serverName = "";
        try {
            if (buf.readableBytes() > 0) {
                serverName = buf.readUtf();
            }
        } catch (Exception e) {

        }
        return new ServerInstalledPayload(version, lastGen, intervalMinutes, mode, intervalTicks, serverName);
    }

    private static UpdateMode readUpdateMode(int ordinal) {
        UpdateMode[] values = UpdateMode.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return UpdateMode.DISABLED;
    }
}
