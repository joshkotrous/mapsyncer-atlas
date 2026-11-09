package com.mapsyncer.client;

import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.server.CacheCommandHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.ClientMessageHelper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class MapSyncerCommandLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommandLogic.class);

    public static void showHelp(boolean hasServerPermission) {
        XaeroMapIntegrator.rejectHostSync(false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        mc.player.displayClientMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.command.help_header")), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_dim"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_sync_all"), false);
        mc.player.displayClientMessage(ChatUtils.desc("mapsyncer.command.help_autosync"), false);
        mc.player.displayClientMessage(ChatUtils.header("mapsyncer.command.help_dimension_note"), false);

        if (hasServerPermission) {
            CacheCommandHandler.showHelp(ClientMessageHelper::sendChatMessage);
        }
    }

    public static void suggestDimensions(SuggestionsBuilder builder) {
        builder.suggest("minecraft:overworld");
        builder.suggest("minecraft:the_nether");
        builder.suggest("minecraft:the_end");
        builder.suggest("all");

        Set<String> added = new HashSet<>();

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            for (ResourceKey<Level> key : mc.getConnection().levels()) {
                String suggestion = com.mapsyncer.util.DimensionApiHelper.getDimId(key);
                if (added.add(suggestion)) builder.suggest(suggestion);
            }
        }

        Path baseDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (baseDir != null) {
            try (Stream<Path> dirs = Files.list(baseDir)) {
                dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                    .forEach(p -> {
                        String dirName = p.getFileName().toString();
                        String suggestion = xaeroDirToDimensionId(dirName);
                        if (suggestion != null && !suggestion.isEmpty() && !added.contains(suggestion)) {
                            builder.suggest(suggestion);
                            added.add(suggestion);
                        }
                    });
            } catch (IOException e) {
                LOGGER.warn("Failed to scan Xaero directory", e);
            }
        }
    }

    public static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return DimensionPathMapping.getInstance().getPathFromXaero(dirName);
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    public static int executeSyncDimension(String dimInput) {
        if (XaeroMapIntegrator.rejectHostSync(false)) return 0;
        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(false);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = resolveDimensionId(dimInput, mc.level);
        sendSyncRequest(mc, dimensionId, false, false);

        return 1;
    }

    public static int executeSyncAll(boolean silent) {
        if (XaeroMapIntegrator.rejectHostSync(silent)) return 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        sendSyncRequest(mc, "all", true, silent);
        return 1;
    }

    public static int executeSyncCurrentDim() {
        if (XaeroMapIntegrator.rejectHostSync(false)) return 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        ResourceKey<Level> currentDim = mc.level.dimension();
        String dimensionId = currentDim.location().toString();
        sendSyncRequest(mc, dimensionId, false, false);

        return 1;
    }

    public static String resolveDimensionId(String input, ClientLevel level) {
        switch (input.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
        }

        if (input.contains(":")) return input;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            String match = null;
            for (ResourceKey<Level> key : mc.getConnection().levels()) {
                String id = com.mapsyncer.util.DimensionApiHelper.getDimId(key);
                if (id.substring(id.indexOf(':') + 1).equals(input)) {
                    if (match != null) return "minecraft:" + input; // ambiguous: require full ID
                    match = id;
                }
            }
            if (match != null) return match;
        }

        return "minecraft:" + input;
    }

    public static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll, boolean silent) {
        if (XaeroMapIntegrator.rejectHostSync(silent)) return;
        if (MapPacketHandler.isSyncInProgress() || ClientHashManager.isComputingMeta()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.in_progress"), false);
            }
            return;
        }

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();

        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim = syncAll ? null : dimMapping.toXaeroDimension(dimensionId);

        Path scanDir = null;
        Map<String, ClientMeta> immediateMeta = null;

        if (syncAll) {
            if (serverDir != null && tsCache != null && tsCache.cacheFileExists()) {
                scanDir = serverDir;
            } else {
                immediateMeta = new java.util.HashMap<>();
                LOGGER.debug("First sync all, sending empty request");
            }
        } else if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
            Path dimDir = serverDir.resolve(xaeroDim);
            Path mwDir = findMwDir(dimDir);
            if (mwDir != null) {
                scanDir = mwDir;
            } else {
                immediateMeta = new java.util.HashMap<>();
                immediateMeta.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.warn("Dimension {} has cache but no mw$ dir", dimensionId);
            }
        } else {
            immediateMeta = new java.util.HashMap<>();
            immediateMeta.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
            LOGGER.debug("First sync for {}", dimensionId);
        }

        if (immediateMeta != null) {
            dispatchSyncRequest(mc, dimensionId, syncAll, serverDir, tsCache, xaeroDim, immediateMeta, silent);
            return;
        }

        final int hashGeneration = ClientSyncSession.get().generation();
        final var hashConnection = mc.getConnection();
        ClientHashManager.computeMetaForSyncAsync(scanDir, result ->
                mc.execute(() -> {
                    if (mc.player == null || mc.getConnection() != hashConnection
                            || !ClientSyncSession.get().isCurrent(hashGeneration)) {
                        return;
                    }
                    if (!result.isSuccess()) {
                        if (result.failedFiles() > 0) {
                            mc.player.displayClientMessage(
                                    ChatUtils.error("mapsyncer.sync.hash_scan_partial", result.failedFiles()), false);
                        } else {
                            mc.player.displayClientMessage(
                                    ChatUtils.error("mapsyncer.sync.hash_scan_failed"), false);
                        }
                        return;
                    }
                    LOGGER.debug("Sync hash scan complete: {} entries", result.meta().size());
                    dispatchSyncRequest(mc, dimensionId, syncAll, serverDir, tsCache, xaeroDim, result.meta(), silent);
                }));
    }

    private static void dispatchSyncRequest(Minecraft mc, String dimensionId, boolean syncAll,
            Path serverDir, ClientTimestampCache tsCache, String xaeroDim,
            Map<String, ClientMeta> metaMap, boolean silent) {
        if (XaeroMapIntegrator.rejectHostSync(silent)) return;
        if (MapPacketHandler.isSyncInProgress()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.in_progress"), false);
            }
            return;
        }

        LOGGER.debug("Sending sync request with {} entries (serverDir={})", metaMap.size(), serverDir);

        if (tsCache != null) {
            Set<String> dimensions = new HashSet<>();
            if (syncAll) {
                dimensions.add("all");
            } else {
                dimensions.add(xaeroDim);
            }
            String command = syncAll ? "/mapsyncer sync all" : "/mapsyncer sync " + dimensionId;
            tsCache.markSyncStart(dimensions, command);
        }

        SyncRequestPayload[] parts = SyncRequestPayload.split(metaMap, syncAll,
                syncAll ? "" : (xaeroDim != null ? xaeroDim : ""), silent);
        for (SyncRequestPayload part : parts) {
            NetworkManager.sendToServer(part);
        }
        SyncProgressTracker.startTracking();
    }

    public static Path findMwDir(Path dimDir) {
        if (dimDir == null) return null;
        String selected = com.mapsyncer.platform.XaeroReflectionHelper.getSelectedMultiworld(dimDir.getFileName().toString());
        if (selected != null && (selected.isEmpty() || XaeroMapDataHandler.isSafeMultiworld(selected))) {
            return selected.isEmpty() ? dimDir : dimDir.resolve(selected);
        }
        if (!dimDir.toFile().exists()) return null;
        try (var dirs = Files.list(dimDir)) {
            Path mw = dirs.filter(p -> p.getFileName().toString().startsWith("mw$") && Files.isDirectory(p))
                    .findFirst().orElse(null);
            if (Minecraft.getInstance().hasSingleplayerServer()) return dimDir;
            return mw;
        } catch (IOException e) {
            return null;
        }
    }

    public static int executeAutoSyncStatus() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        boolean enabled = PlatformManager.getPlatform().isClientAutoSyncEnabled();
        mc.player.displayClientMessage(
                ChatUtils.prefix().append(ChatUtils.desc(
                        enabled ? "mapsyncer.autosync.client.enabled" : "mapsyncer.autosync.client.disabled")),
                false);
        XaeroMapIntegrator.rejectHostSync(false);
        return 1;
    }

    public static int setClientAutoSync(boolean enabled) {
        PlatformManager.getPlatform().setClientAutoSyncEnabled(enabled);
        if (!enabled || XaeroMapIntegrator.isIntegratedServerHost()) {
            AutoSyncManager.stopPeriodicSync();
        }
        return executeAutoSyncStatus();
    }
}
