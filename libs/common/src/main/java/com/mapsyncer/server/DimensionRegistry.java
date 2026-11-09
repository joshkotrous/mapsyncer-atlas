package com.mapsyncer.server;

import com.mapsyncer.config.DimensionScanConfig;
import com.mapsyncer.config.LayerPlan;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.DimensionTypeHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

public class DimensionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionRegistry.class);

    private static volatile boolean hasRegistered = false;

    public static void registerAllDimensions(MinecraftServer server) {

        if (hasRegistered) {
            LOGGER.debug("Dimensions already registered, skipping");
            return;
        }

        LOGGER.info("Starting dimension registration on first map generation...");

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);

        DimensionPathMapping mapping = DimensionPathMapping.getInstance();
        mapping.scanAndRegisterDimensions(worldRoot);

        List<? extends String> currentConfigs = PlatformManager.getPlatform().getDimensionConfigs();

        Set<String> configuredDimensions = new HashSet<>();
        for (DimensionScanConfig config : PlatformManager.getPlatform().parseDimensionConfigs()) {
            configuredDimensions.add(normalizeDimensionId(config.dimension()));
        }

        LOGGER.info("Currently configured dimensions: {}", configuredDimensions);

        Set<String> newDimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            String dimId = DimensionApiHelper.getDimId(dimKey);

            String normalizedId = normalizeDimensionId(dimId);

            if (!configuredDimensions.contains(normalizedId)) {

                newDimensions.add(dimId);
                LOGGER.debug("Found unconfigured dimension: {} (normalized: {})", dimId, normalizedId);
            }
        }

        if (newDimensions.isEmpty()) {
            LOGGER.info("All dimensions already configured, no updates needed");
            hasRegistered = true;
            return;
        }

        List<String> updatedConfigs = new ArrayList<>(currentConfigs);

        for (String dimId : newDimensions) {

            ServerLevel level = getLevelForDimension(server, dimId);
            DimensionTypeInfo dimTypeInfo;
            if (level != null) {
                dimTypeInfo = DimensionTypeHelper.fromDimensionType(level.dimensionType());
                LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
                    dimId, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
                    dimTypeInfo.minY(), dimTypeInfo.height());
            } else {

                dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimId);
            }

            DimensionScanConfig finalConfig = new DimensionScanConfig(
                    dimId,
                    LayerPlan.surfaceOnly(),
                    dimTypeInfo
            );

            String configStr = configToString(finalConfig);
            updatedConfigs.add(configStr);
            LOGGER.info("Added dimension config: {} (layerPlan={}, hasSkylight={})",
                    dimId, finalConfig.layerPlan().toConfigString(), dimTypeInfo.hasSkylight());
        }

        PlatformManager.getPlatform().setDimensionConfigs(updatedConfigs);

        PlatformManager.getPlatform().saveConfig();

        hasRegistered = true;
        LOGGER.info("Dimension registration completed: {} new dimensions added, total {} dimensions configured",
                newDimensions.size(), updatedConfigs.size());
    }

    public static void resetRegistration() {
        hasRegistered = false;
        DimensionPathMapping.getInstance().resetMappings();
        LOGGER.info("Dimension registration state reset");
    }

    private static String normalizeDimensionId(String dimId) {
        return dimId.replace("minecraft:", "").toLowerCase();
    }

    private static ServerLevel getLevelForDimension(MinecraftServer server, String dimId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (DimensionApiHelper.getDimId(level.dimension()).equals(dimId)) {
                return level;
            }
        }
        return null;
    }

    private static String configToString(DimensionScanConfig config) {
        return config.dimension() + "|" + config.layerPlan().toConfigString();
    }

    public static boolean isRegistered() {
        return hasRegistered;
    }
}
