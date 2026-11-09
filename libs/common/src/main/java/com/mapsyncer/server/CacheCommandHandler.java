package com.mapsyncer.server;

import com.mapsyncer.config.DimensionConfigParser;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.UpdateMode;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.ModLogConfig;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

public class CacheCommandHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheCommandHandler.class);

    public static String serverCommandPrefix() {
        return PlatformManager.getPlatform().getServerCommandPrefix();
    }

    public static void showIntegratedServerHint(MinecraftServer server,
            Consumer<net.minecraft.network.chat.Component> sender) {
        if (server != null && !server.isDedicatedServer()) {
            sender.accept(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.generate.integrated_hint")
                    .withStyle(style -> style.withBold(true))));
        }
    }

    public static boolean isIntegratedHost(MinecraftServer server,
            net.minecraft.server.level.ServerPlayer player) {
        return server != null && !server.isDedicatedServer() && player != null
                && com.mapsyncer.util.PlayerLevelApiHelper.isSingleplayerOwner(server, player);
    }

    public static boolean isGenerationAllowed(MinecraftServer server) {
        return server != null;
    }

    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender) {
        showHelp(sender, serverCommandPrefix());
    }

    public static void showHelp(Consumer<net.minecraft.network.chat.Component> sender, String prefix) {
        sender.accept(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_dim", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_region", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.generate_force", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.status", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.radius", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_off", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_tick", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.incremental_scheduled", prefix));
        sender.accept(ChatUtils.desc("mapsyncer.help.server.reloadconfig", prefix));
    }

    public static void showIncrementalMode(Consumer<net.minecraft.network.chat.Component> sender) {
        sender.accept(incrementalStatusMessage());
        sender.accept(ChatUtils.desc(
                "mapsyncer.command.incremental_status_hint", serverCommandPrefix()));
    }

    public static void showIncrementalMode(MinecraftServer server,
            Consumer<net.minecraft.network.chat.Component> sender) {
        if (!isGenerationAllowed(server)) {
            sender.accept(incrementalStatusMessage(server));
            showIntegratedServerHint(server, sender);
            return;
        }
        showIncrementalMode(sender);
    }

    public static MutableComponent incrementalStatusMessage(MinecraftServer server) {
        return isGenerationAllowed(server) ? incrementalStatusMessage()
                : ChatUtils.message("mapsyncer.command.incremental_status_disabled");
    }

    public static MutableComponent generationStatusMessage() {
        if (ConversionOrchestrator.isRunning()) {
            return ChatUtils.message("mapsyncer.generate.in_progress",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        }
        return ChatUtils.message("mapsyncer.generate.no_progress");
    }

    public static MutableComponent incrementalStatusMessage() {
        var platform = PlatformManager.getPlatform();
        UpdateMode mode = platform.getIncrementalUpdateMode();
        IncrementalUpdateHandlerLogic handler = IncrementalUpdateHandlerLogic.getInstance();

        if (mode == UpdateMode.TICK) {
            int interval = platform.getIncrementalUpdateIntervalTicks();
            int remainingTicks = handler.isRunning()
                    ? Math.max(0, interval - handler.getTickCounter())
                    : interval;
            int remainingSeconds = remainingTicks / 20;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            return ChatUtils.message(
                    "mapsyncer.command.incremental_status_tick",
                    interval, interval / 20.0f, minutes, seconds);
        }
        if (mode == UpdateMode.SCHEDULED) {
            int hour = platform.getScheduledUpdateHour();
            int minute = platform.getScheduledUpdateMinute();
            return ChatUtils.message(
                    "mapsyncer.command.incremental_status_scheduled", hour, minute);
        }
        return ChatUtils.message("mapsyncer.command.incremental_status_disabled");
    }

    public static boolean generateAll(MinecraftServer server, Runnable onSuccess) {
        if (!isGenerationAllowed(server)) return false;
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateAll command");
            return false;
        }
        // Save chunks on server thread before dispatching heavy I/O to background
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateAll(server) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static boolean generateDimension(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        if (!isGenerationAllowed(server)) return false;
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimension command");
            return false;
        }
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateDimension(server, dimensionId) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static boolean generateDimensionForce(MinecraftServer server, String dimensionId, Runnable onSuccess) {
        if (!isGenerationAllowed(server)) return false;
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateDimensionForce command");
            return false;
        }
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            if (ConversionOrchestrator.generateDimensionForce(server, dimensionId) && onSuccess != null) {
                onSuccess.run();
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static boolean checkRegionExists(MinecraftServer server, ResourceKey<Level> dimension, int x, int z) {
        return ConversionOrchestrator.checkMcaFileExists(server, dimension, x, z) != null;
    }

    public static boolean generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int x, int z,
                                            Consumer<SingleRegionResult> resultHandler) {
        if (!isGenerationAllowed(server)) return false;
        if (ConversionOrchestrator.isRunning()) {
            LOGGER.warn("Conversion already in progress, rejecting generateSingleRegion command");
            resultHandler.accept(SingleRegionResult.ALREADY_RUNNING);
            return false;
        }
        // Save chunks on server thread before dispatching heavy I/O to background
        server.saveEverything(false, true, true);
        Thread worker = new Thread(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            if (resultHandler != null) {
                resultHandler.accept(result);
            }
        }, "xaero-map-generator");
        worker.start();
        return true;
    }

    public static List<DimensionCacheStats> getCacheStats() {
        return ConversionOrchestrator.getCacheStats();
    }

    public static List<String> getCompletedDimensions() {
        return ConversionOrchestrator.getCompletedDimensions();
    }

    public static int getProcessedCount() {
        return ConversionOrchestrator.getProcessedCount();
    }

    public static int getTotalCount() {
        return ConversionOrchestrator.getTotalCount();
    }

    public static int getUpdatedCount() {
        return ConversionOrchestrator.getUpdatedCount();
    }

    public static void disableIncremental() {
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateMode(UpdateMode.DISABLED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().stop();
    }

    public static void setIncrementalTick(MinecraftServer server) {
        if (!isGenerationAllowed(server)) {
            IncrementalUpdateHandlerLogic.getInstance().stop();
            return;
        }
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateMode(UpdateMode.TICK);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static void setIncrementalTick(MinecraftServer server, int interval) {
        if (!isGenerationAllowed(server)) {
            IncrementalUpdateHandlerLogic.getInstance().stop();
            return;
        }
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateIntervalTicks(interval);
        platform.setIncrementalUpdateMode(UpdateMode.TICK);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static void setIncrementalScheduled(MinecraftServer server) {
        if (!isGenerationAllowed(server)) {
            IncrementalUpdateHandlerLogic.getInstance().stop();
            return;
        }
        var platform = PlatformManager.getPlatform();
        platform.setIncrementalUpdateMode(UpdateMode.SCHEDULED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static void setScheduledTime(MinecraftServer server, int hour) {
        if (!isGenerationAllowed(server)) {
            IncrementalUpdateHandlerLogic.getInstance().stop();
            return;
        }
        var platform = PlatformManager.getPlatform();
        platform.setScheduledUpdateHour(hour);
        platform.setIncrementalUpdateMode(UpdateMode.SCHEDULED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static void setScheduledTime(MinecraftServer server, int hour, int minute) {
        if (!isGenerationAllowed(server)) {
            IncrementalUpdateHandlerLogic.getInstance().stop();
            return;
        }
        var platform = PlatformManager.getPlatform();
        platform.setScheduledUpdateHour(hour);
        platform.setScheduledUpdateMinute(minute);
        platform.setIncrementalUpdateMode(UpdateMode.SCHEDULED);
        platform.saveConfig();
        IncrementalUpdateHandlerLogic.getInstance().start(server);
    }

    public static UpdateMode getIncrementalUpdateMode() {
        return PlatformManager.getPlatform().getIncrementalUpdateMode();
    }

    public static int getIncrementalUpdateIntervalTicks() {
        return PlatformManager.getPlatform().getIncrementalUpdateIntervalTicks();
    }

    public static int getScheduledUpdateHour() {
        return PlatformManager.getPlatform().getScheduledUpdateHour();
    }

    public static int getScheduledUpdateMinute() {
        return PlatformManager.getPlatform().getScheduledUpdateMinute();
    }

    public static boolean reloadConfig(MinecraftServer server) {
        try {
            PlatformManager.getPlatform().reloadConfig();
            ModLogConfig.applyDebugLogging();
            DimensionRegistry.resetRegistration();
            DimensionConfigParser.invalidateCache();

            if (!ConversionOrchestrator.isRunning()) {
                ConversionOrchestrator.shutdownExecutor();
            }

            IncrementalUpdateHandlerLogic handler = IncrementalUpdateHandlerLogic.getInstance();
            handler.stop();
            UpdateMode mode = PlatformManager.getPlatform().getIncrementalUpdateMode();
            if (mode != UpdateMode.DISABLED && server != null) {
                handler.start(server);
            }

            LOGGER.info("Server configuration reloaded");
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to reload server configuration", e);
            return false;
        }
    }

    public static String getFriendlyDimensionName(ResourceKey<Level> dimension) {
        return DimensionPathMapping.getInstance().getFriendlyName(DimensionApiHelper.getDimId(dimension));
    }

    public static String getDimensionId(ResourceKey<Level> dimension) {
        return DimensionApiHelper.getDimId(dimension);
    }
}
