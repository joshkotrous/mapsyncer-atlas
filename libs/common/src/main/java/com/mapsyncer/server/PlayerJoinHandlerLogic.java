package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ServerInstalledPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerJoinHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandlerLogic.class);

    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    private static int cleanupTickCounter = 0;

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null) return;

        if (CacheCommandHandler.isIntegratedHost(server, player)) {
            CacheCommandHandler.showIntegratedServerHint(server, player::sendSystemMessage);
            NetworkManager.sendToPlayer(player, new ServerInstalledPayload(
                    getModVersion(), 0, 0, UpdateMode.DISABLED, 0, PlatformManager.getServerName()));
            return;
        }

        long lastGenTime = GenerationCache.getInstance(ConversionOrchestrator.getCacheDir()).getLastGenerationTime();
        UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
        int intervalTicks = PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
        int autoInterval = AutoSyncConfig.computeInterval(mode, intervalTicks);
        String serverName = PlatformManager.getServerName();
        ServerMapCache.Snapshot mapCache = ServerMapCache.snapshot();
        if (mapCache.sharedXaero()) {

            java.util.concurrent.CompletableFuture.supplyAsync(mapCache::lastModifiedSeconds)
                    .thenAccept(timestamp -> server.execute(() -> {
                        if (server.getPlayerList().getPlayer(player.getUUID()) == player
                                && ServerMapCache.snapshot().root().equals(mapCache.root())) {
                            NetworkManager.sendToPlayer(player, new ServerInstalledPayload(
                                    getModVersion(), timestamp, autoInterval, mode, intervalTicks, serverName));
                        }
                    }));
        } else {
            NetworkManager.sendToPlayer(player,
                new ServerInstalledPayload(getModVersion(), lastGenTime, autoInterval, mode, intervalTicks, serverName));
        }

        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandlerLogic.getInstance().start(server);
        }
    }

    public static void onPlayerLeave(UUID playerId) {
        ServerSyncHandlerLogic.onPlayerDisconnect(playerId);
    }

    public static void onServerStopped() {
        ServerLifecycleBridge.onServerStopped();
    }

    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        Set<UUID> onlinePlayerIds = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        ServerSyncHandlerLogic.cleanupOfflinePlayers(onlinePlayerIds);
    }

    private static String getModVersion() {
        try {
            return com.mapsyncer.MapSyncer.VERSION;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
