package com.mapsyncer.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class XaeroReflectionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroReflectionHelper.class);

    public static final byte LOAD_STATE_UNLOADED = 0;

    public static final byte LOAD_STATE_LOADING = 1;

    public static final byte LOAD_STATE_LOADED = 2;

    public static final byte LOAD_STATE_PROCESSING = 3;

    public static final byte LOAD_STATE_CLEARED = 4;

    private static volatile boolean initialized = false;

    private static Class<?> worldMapSessionClass;
    private static Class<?> mapProcessorClass;
    private static Class<?> mapSaveLoadClass;
    private static Class<?> mapRegionClass;
    private static Class<?> leveledRegionClass;
    private static Class<?> mapWorldClass;
    private static Class<?> mapDimensionClass;
    private static Class<?> layeredRegionManagerClass;
    private static Class<?> mapLayerClass;
    private static Class<?> leveledRegionManagerClass;
    private static Class<?> branchLeveledRegionClass;

    private static Method getCurrentSessionMethod;
    private static Method getMapProcessorMethod;
    private static Method getMapSaveLoadMethod;
    private static Method getLeafMapRegionMethod;
    private static Method requestLoadMethod;
    private static Method cancelRefreshMethod;
    private static Method isRefreshingMethod;
    private static Method setHasHadTerrainMethod;
    private static Method setRegionDetectionCompleteMethod;
    private static Method getMapWorldMethod;
    private static Method getCurrentDimensionMethod;
    private static Method getLayeredMapRegionsMethod;
    private static Method getLayerMethod;
    private static Method getMapRegionsMethod;

    private static Field loadStateField;
    private static Field shouldCacheField;
    private static Field worldIdField;
    private static Field dimIdField;
    private static Field mwIdField;
    private static Field regionXField;
    private static Field regionZField;
    private static Field regionTextureMapField;
    private static Field childrenField;

    private static Object cachedSession;
    private static Object cachedMapProcessor;
    private static Object cachedMapSaveLoad;

    private XaeroReflectionHelper() {}

    public static boolean initialize() {
        if (initialized) return true;

        try {
            LOGGER.info("Initializing the Xaero reflection cache...");

            LOGGER.debug("Loading Xaero core classes...");
            worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");
            mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            leveledRegionManagerClass = Class.forName("xaero.map.region.LeveledRegionManager");
            branchLeveledRegionClass = Class.forName("xaero.map.region.BranchLeveledRegion");
            LOGGER.info("Loaded {} Xaero classes successfully", 11);

            LOGGER.debug("Obtaining and caching reflection methods...");
            getCurrentSessionMethod = worldMapSessionClass.getMethod("getCurrentSession");
            getMapProcessorMethod = worldMapSessionClass.getMethod("getMapProcessor");
            getMapSaveLoadMethod = mapProcessorClass.getMethod("getMapSaveLoad");
            getLeafMapRegionMethod = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            requestLoadMethod = mapSaveLoadClass.getMethod("requestLoad", mapRegionClass, String.class, boolean.class);
            cancelRefreshMethod = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);
            try {
                isRefreshingMethod = mapRegionClass.getMethod("isRefreshing");
            } catch (NoSuchMethodException ignored) {
                isRefreshingMethod = null;
            }
            setHasHadTerrainMethod = mapRegionClass.getMethod("setHasHadTerrain");
            setRegionDetectionCompleteMethod = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            getMapWorldMethod = mapProcessorClass.getMethod("getMapWorld");
            getCurrentDimensionMethod = mapWorldClass.getMethod("getCurrentDimension");
            getLayeredMapRegionsMethod = mapDimensionClass.getMethod("getLayeredMapRegions");
            getLayerMethod = layeredRegionManagerClass.getMethod("getLayer", int.class);
            getMapRegionsMethod = mapLayerClass.getMethod("getMapRegions");
            LOGGER.info("Cached {} reflection methods successfully", 13);

            LOGGER.debug("Obtaining and caching reflection fields...");
            loadStateField = mapRegionClass.getDeclaredField("loadState");
            loadStateField.setAccessible(true);
            shouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            shouldCacheField.setAccessible(true);
            worldIdField = leveledRegionClass.getDeclaredField("worldId");
            worldIdField.setAccessible(true);
            dimIdField = leveledRegionClass.getDeclaredField("dimId");
            dimIdField.setAccessible(true);
            mwIdField = leveledRegionClass.getDeclaredField("mwId");
            mwIdField.setAccessible(true);
            regionXField = leveledRegionClass.getDeclaredField("regionX");
            regionXField.setAccessible(true);
            regionZField = leveledRegionClass.getDeclaredField("regionZ");
            regionZField.setAccessible(true);
            regionTextureMapField = leveledRegionManagerClass.getDeclaredField("regionTextureMap");
            regionTextureMapField.setAccessible(true);
            childrenField = branchLeveledRegionClass.getDeclaredField("children");
            childrenField.setAccessible(true);
            LOGGER.info("Cached {} reflection fields successfully", 9);

            initialized = true;
            LOGGER.info("Xaero reflection helper initialized successfully");
            return true;

        } catch (ClassNotFoundException e) {
            LOGGER.error("Xaero's World Map was not found or class names do not match; reflection disabled", e);
            LOGGER.error("Please ensure Xaero's World Map is installed");
            return false;
        } catch (NoSuchMethodException e) {
            LOGGER.error("Incompatible Xaero API: method signatures have changed", e);
            LOGGER.error("Possible cause: the Xaero version is too new or too old for this MapSyncer version");
            return false;
        } catch (NoSuchFieldException e) {
            LOGGER.error("Incompatible Xaero API: a field is missing", e);
            LOGGER.error("Possible cause: the Xaero version is too new or too old for this MapSyncer version");
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize the Xaero reflection helper", e);
            return false;
        }
    }

    public static Object getSession() {
        if (!initialized || getCurrentSessionMethod == null) return null;

        try {
            cachedSession = getCurrentSessionMethod.invoke(null);
            return cachedSession;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Failed to get WorldMapSession", e);
            return null;
        }
    }

    public static Object getMapProcessor() {
        if (!initialized) return null;

        try {
            if (cachedMapProcessor == null) {
                Object session = getSession();
                if (session == null) return null;
                cachedMapProcessor = getMapProcessorMethod.invoke(session);
            }
            return cachedMapProcessor;
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapProcessor", e);
            return null;
        }
    }

    public static Object getMapSaveLoad() {
        if (!initialized) return null;

        try {
            if (cachedMapSaveLoad == null) {
                Object processor = getMapProcessor();
                if (processor == null) return null;
                cachedMapSaveLoad = getMapSaveLoadMethod.invoke(processor);
            }
            return cachedMapSaveLoad;
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapSaveLoad", e);
            return null;
        }
    }

    public static Object getLeafMapRegion(int caveLayer, int regionX, int regionZ, boolean createIfMissing) {
        if (!initialized || getLeafMapRegionMethod == null) return null;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            return getLeafMapRegionMethod.invoke(processor, caveLayer, regionX, regionZ, createIfMissing);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapRegion ({}, {}) layer={}", regionX, regionZ, caveLayer, e);
            return null;
        }
    }

    public static boolean setRegionDetectionComplete(boolean value) {
        if (!initialized || setRegionDetectionCompleteMethod == null) {
            LOGGER.warn("setRegionDetectionComplete failed: reflection is uninitialized or the method cache is empty (initialized={}, method={})",
                initialized, setRegionDetectionCompleteMethod != null);
            return false;
        }

        try {
            Object saveLoad = getMapSaveLoad();
            if (saveLoad == null) {
                LOGGER.warn("setRegionDetectionComplete failed: cannot obtain the MapSaveLoad instance");
                return false;
            }
            setRegionDetectionCompleteMethod.invoke(saveLoad, value);
            LOGGER.debug("setRegionDetectionComplete set to {}", value);
            return true;
        } catch (Exception e) {
            LOGGER.error("setRegionDetectionComplete reflection call failed (value={}): {}", value, e.getMessage(), e);
            return false;
        }
    }

    public static boolean requestLoad(Object mapRegion, String reason, boolean prioritize) {
        if (!initialized || requestLoadMethod == null) {
            LOGGER.warn("requestLoad failed: reflection is uninitialized or the method cache is empty (initialized={}, method={})",
                initialized, requestLoadMethod != null);
            return false;
        }

        try {
            Object saveLoad = getMapSaveLoad();
            if (saveLoad == null) {
                LOGGER.warn("requestLoad failed: cannot obtain the MapSaveLoad instance");
                return false;
            }
            requestLoadMethod.invoke(saveLoad, mapRegion, reason, prioritize);
            LOGGER.debug("requestLoad completed successfully (reason={}, prioritize={})", reason, prioritize);
            return true;
        } catch (Exception e) {
            LOGGER.error("requestLoad reflection call failed (reason={}): {}", reason, e.getMessage(), e);
            return false;
        }
    }

    public static boolean cancelRefresh(Object mapRegion) {
        if (!initialized || cancelRefreshMethod == null) {
            LOGGER.warn("cancelRefresh failed: reflection is uninitialized or the method cache is empty (initialized={}, method={})",
                initialized, cancelRefreshMethod != null);
            return false;
        }

        try {
            Object processor = getMapProcessor();
            if (processor == null) {
                LOGGER.warn("cancelRefresh failed: cannot obtain the MapProcessor instance");
                return false;
            }
            cancelRefreshMethod.invoke(mapRegion, processor);
            LOGGER.debug("cancelRefresh completed successfully");
            return true;
        } catch (Exception e) {
            LOGGER.error("cancelRefresh reflection call failed: {}", e.getMessage(), e);
            return false;
        }
    }

    public static boolean setLoadState(Object mapRegion, byte state) {
        if (!initialized || loadStateField == null) {
            LOGGER.warn("setLoadState failed: reflection is uninitialized or the field cache is empty (initialized={}, field={})",
                initialized, loadStateField != null);
            return false;
        }

        try {
            loadStateField.setByte(mapRegion, state);
            LOGGER.debug("setLoadState set to {} successfully", state);
            return true;
        } catch (Exception e) {
            LOGGER.error("setLoadState reflection call failed (state={}): {}", state, e.getMessage(), e);
            return false;
        }
    }

    public static boolean setShouldCache(Object mapRegion, boolean value) {
        if (!initialized || shouldCacheField == null) {
            LOGGER.warn("setShouldCache failed: reflection is uninitialized or the field cache is empty (initialized={}, field={})",
                initialized, shouldCacheField != null);
            return false;
        }

        try {
            shouldCacheField.setBoolean(mapRegion, value);
            LOGGER.debug("setShouldCache set to {} successfully", value);
            return true;
        } catch (Exception e) {
            LOGGER.error("setShouldCache reflection call failed (value={}): {}", value, e.getMessage(), e);
            return false;
        }
    }

    public static boolean setHasHadTerrain(Object mapRegion) {
        if (!initialized || setHasHadTerrainMethod == null) {
            LOGGER.warn("setHasHadTerrain failed: reflection is uninitialized or the method cache is empty (initialized={}, method={})",
                initialized, setHasHadTerrainMethod != null);
            return false;
        }

        try {
            setHasHadTerrainMethod.invoke(mapRegion);
            LOGGER.debug("setHasHadTerrain completed successfully");
            return true;
        } catch (Exception e) {
            LOGGER.error("setHasHadTerrain reflection call failed: {}", e.getMessage(), e);
            return false;
        }
    }

    public static String getWorldId(Object mapRegion) {
        if (!initialized || worldIdField == null) return null;

        try {
            return (String) worldIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get worldId", e);
            return null;
        }
    }

    public static String getDimId(Object mapRegion) {
        if (!initialized || dimIdField == null) return null;

        try {
            return (String) dimIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get dimId", e);
            return null;
        }
    }

    public static String getMwId(Object mapRegion) {
        if (!initialized || mwIdField == null) return null;

        try {
            return (String) mwIdField.get(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get mwId", e);
            return null;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void clearCache() {
        initialized = false;
        cachedSession = null;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        LOGGER.info("Xaero reflection cache cleared");
    }

    public static boolean prepareRegionLoad(Object mapRegion) {
        LOGGER.debug("Preparing region for loading (region={})...", mapRegion);

        boolean step1 = cancelRefresh(mapRegion);
        boolean step2 = setShouldCache(mapRegion, true);
        boolean step3 = setHasHadTerrain(mapRegion);

        if (step1 && step2 && step3) {
            LOGGER.debug("Region load preparation complete");
            return true;
        } else {
            LOGGER.warn("Region load preparation partially failed: cancelRefresh={}, setShouldCache={}, setHasHadTerrain={}",
                step1, step2, step3);
            return false;
        }
    }

    public static Object getMapWorld() {
        if (!initialized || getMapWorldMethod == null) return null;

        try {
            Object processor = getMapProcessor();
            if (processor == null) return null;
            return getMapWorldMethod.invoke(processor);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapWorld", e);
            return null;
        }
    }

    public static Object getCurrentMapDimension() {
        if (!initialized || getCurrentDimensionMethod == null) return null;

        try {
            Object mapWorld = getMapWorld();
            if (mapWorld == null) return null;
            return getCurrentDimensionMethod.invoke(mapWorld);
        } catch (Exception e) {
            LOGGER.warn("Failed to get current dimension", e);
            return null;
        }
    }

    public static java.util.Map<String, String> getSelectedMultiworlds() {
        if (!initialize()) return java.util.Map.of();
        Object world = getMapWorld();
        if (world == null) return java.util.Map.of();
        java.util.Map<String, String> selections = new java.util.HashMap<>();
        try {
            var dimensions = (Iterable<?>) mapWorldClass.getMethod("getDimensionsList").invoke(world);
            Method folderMethod = mapDimensionClass.getMethod("getMainFolderPath");
            Method multiworldMethod = mapDimensionClass.getMethod("getCurrentMultiworld");
            for (Object dimension : dimensions) {
                var folder = (java.nio.file.Path) folderMethod.invoke(dimension);
                if (folder == null) continue;
                String selected = (String) multiworldMethod.invoke(dimension);
                selections.put(folder.getFileName().toString(), selected == null ? "" : selected);
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("Could not capture Xaero map selections", e);
            return java.util.Map.of();
        }
        return java.util.Map.copyOf(selections);
    }

    public static String getSelectedMultiworld(String dimensionFolder) {
        if (!initialize()) return null;
        Object world = getMapWorld();
        if (world == null) return null;
        try {
            var dimensions = (Iterable<?>) mapWorldClass.getMethod("getDimensionsList").invoke(world);
            Method folderMethod = mapDimensionClass.getMethod("getMainFolderPath");
            Method multiworldMethod = mapDimensionClass.getMethod("getCurrentMultiworld");
            for (Object dimension : dimensions) {
                var folder = (java.nio.file.Path) folderMethod.invoke(dimension);
                if (folder != null && folder.getFileName().toString().equals(dimensionFolder)) {
                    String selected = (String) multiworldMethod.invoke(dimension);
                    return selected == null ? "" : selected;
                }
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("Could not resolve selected Xaero multiworld for {}", dimensionFolder, e);
        }
        return null;
    }

    public static Object getLayeredMapRegions() {
        if (!initialized || getLayeredMapRegionsMethod == null) return null;

        try {
            Object dimension = getCurrentMapDimension();
            if (dimension == null) return null;
            return getLayeredMapRegionsMethod.invoke(dimension);
        } catch (Exception e) {
            LOGGER.warn("Failed to get LayeredRegionManager", e);
            return null;
        }
    }

    public static Object getMapLayer(int layer) {
        if (!initialized || getLayerMethod == null) return null;

        try {
            Object layeredManager = getLayeredMapRegions();
            if (layeredManager == null) return null;
            return getLayerMethod.invoke(layeredManager, layer);
        } catch (Exception e) {
            LOGGER.warn("Failed to get MapLayer for layer {}", layer, e);
            return null;
        }
    }

    public static Object getLeveledRegionManager(int layer) {
        if (!initialized || getMapRegionsMethod == null) return null;

        try {
            Object mapLayer = getMapLayer(layer);
            if (mapLayer == null) return null;
            return getMapRegionsMethod.invoke(mapLayer);
        } catch (Exception e) {
            LOGGER.warn("Failed to get LeveledRegionManager for layer {}", layer, e);
            return null;
        }
    }

    public static Object getRegionTextureMap(int layer) {
        if (!initialized || regionTextureMapField == null) return null;

        try {
            Object leveledManager = getLeveledRegionManager(layer);
            if (leveledManager == null) return null;
            return regionTextureMapField.get(leveledManager);
        } catch (Exception e) {
            LOGGER.warn("Failed to get regionTextureMap for layer {}", layer, e);
            return null;
        }
    }

    public static int getRegionX(Object mapRegion) {
        if (!initialized || regionXField == null) return -1;

        try {
            return regionXField.getInt(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get regionX", e);
            return -1;
        }
    }

    public static int getRegionZ(Object mapRegion) {
        if (!initialized || regionZField == null) return -1;

        try {
            return regionZField.getInt(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get regionZ", e);
            return -1;
        }
    }

    public static byte getLoadState(Object mapRegion) {
        if (!initialized || loadStateField == null) return -1;

        try {
            return loadStateField.getByte(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get loadState", e);
            return -1;
        }
    }

    public static boolean isRefreshing(Object mapRegion) {
        if (!initialized || isRefreshingMethod == null || mapRegion == null) {
            return false;
        }
        try {
            return (boolean) isRefreshingMethod.invoke(mapRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get isRefreshing", e);
            return false;
        }
    }

    public static Object getBranchChildren(Object branchRegion) {
        if (!initialized || childrenField == null) return null;

        try {
            return childrenField.get(branchRegion);
        } catch (Exception e) {
            LOGGER.warn("Failed to get children", e);
            return null;
        }
    }

    public static boolean isMapRegion(Object obj) {
        return obj != null && obj.getClass() == mapRegionClass;
    }

    public static boolean isBranchLeveledRegion(Object obj) {
        return obj != null && obj.getClass() == branchLeveledRegionClass;
    }
}
