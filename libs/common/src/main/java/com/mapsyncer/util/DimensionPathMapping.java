package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class DimensionPathMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPathMapping.class);

    private static final int NEW_FORMAT_MIN_VERSION = 26;

    private static volatile DimensionPathMapping instance;

    private int majorVersion = 21;

    private boolean useNewFormatForVanilla = false;

    private final Map<String, String> pathToFolder = new ConcurrentHashMap<>();

    private final Map<String, String> pathToXaero = new ConcurrentHashMap<>();

    private static final Map<String, String> VANILLA_LEGACY_FORMAT = new LinkedHashMap<>();

    private static final Map<String, String> VANILLA_NEW_FORMAT = new LinkedHashMap<>();

    private static final Map<String, String> VANILLA_XAERO_MAPPINGS = new LinkedHashMap<>();

    static {

        VANILLA_LEGACY_FORMAT.put("overworld", ".");
        VANILLA_LEGACY_FORMAT.put("the_nether", "DIM-1");
        VANILLA_LEGACY_FORMAT.put("the_end", "DIM1");

        VANILLA_NEW_FORMAT.put("overworld", "dimensions/minecraft/overworld");
        VANILLA_NEW_FORMAT.put("the_nether", "dimensions/minecraft/the_nether");
        VANILLA_NEW_FORMAT.put("the_end", "dimensions/minecraft/the_end");

        VANILLA_XAERO_MAPPINGS.put("overworld", "null");
        VANILLA_XAERO_MAPPINGS.put("the_nether", "DIM-1");
        VANILLA_XAERO_MAPPINGS.put("the_end", "DIM1");
    }

    private DimensionPathMapping() {

        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);

        LOGGER.info("DimensionPathMapping initialized (version: {}, new format: {})",
                majorVersion, useNewFormatForVanilla);
    }

    public void initialize(int majorVersion) {
        this.majorVersion = majorVersion;
        this.useNewFormatForVanilla = majorVersion >= NEW_FORMAT_MIN_VERSION;

        pathToFolder.clear();

        LOGGER.info("DimensionPathMapping version set to: {}, use new format for vanilla: {}",
                majorVersion, useNewFormatForVanilla);
    }

    public int getMajorVersion() {
        return majorVersion;
    }

    public boolean isUseNewFormatForVanilla() {
        return useNewFormatForVanilla;
    }

    public static DimensionPathMapping getInstance() {
        if (instance == null) {
            synchronized (DimensionPathMapping.class) {
                if (instance == null) {
                    instance = new DimensionPathMapping();
                }
            }
        }
        return instance;
    }

    public static void resetInstance() {
        synchronized (DimensionPathMapping.class) {
            instance = null;
        }
        LOGGER.info("DimensionPathMapping instance reset");
    }

    public void resetMappings() {
        pathToFolder.clear();
        LOGGER.info("DimensionPathMapping mappings reset (version: {}, new format: {})",
                majorVersion, useNewFormatForVanilla);
    }

    public Path detectRegionDir(Path worldRoot, String dimPath) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return null;
        }

        String normalized = normalizeDimPath(dimPath);

        String cachedFolder = pathToFolder.get(normalized);
        if (cachedFolder != null) {
            Path regionDir = resolveRegionDir(worldRoot, cachedFolder);
            if (Files.exists(regionDir)) {
                return regionDir;
            }
        }

        if (useNewFormatForVanilla) {
            String newFormatFolder = buildNewFormatPath(normalized);
            if (newFormatFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, newFormatFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected dimension {} (26.1+ new format): {}", normalized, newFormatFolder);
                    pathToFolder.put(normalized, newFormatFolder);
                    return regionDir;
                }
            }
        }

        if (!useNewFormatForVanilla && isVanillaDimension(normalized)) {
            String legacyFolder = VANILLA_LEGACY_FORMAT.get(normalized);
            if (legacyFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, legacyFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected vanilla dimension {} (legacy format): {}", normalized, legacyFolder);
                    pathToFolder.put(normalized, legacyFolder);
                    return regionDir;
                }
            }
        }

        if (!isVanillaDimension(normalized)) {
            String newFormatFolder = buildNewFormatPath(normalized);
            if (newFormatFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, newFormatFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected Mod dimension {} (new format): {}", normalized, newFormatFolder);
                    pathToFolder.put(normalized, newFormatFolder);
                    return regionDir;
                }
            }
        }

        LOGGER.warn("Could not detect region directory for dimension: {}", normalized);
        return null;
    }

    private String buildNewFormatPath(String normalized) {
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return "dimensions/" + parts[0] + "/" + parts[1];
            }
        }

        if (!normalized.isEmpty()) {
            return "dimensions/minecraft/" + normalized;
        }
        return null;
    }

    private boolean isVanillaDimension(String dimPath) {
        return "overworld".equals(dimPath) || "the_nether".equals(dimPath) || "the_end".equals(dimPath);
    }

    private Path resolveRegionDir(Path worldRoot, String folder) {
        if (folder == null || folder.isEmpty() || ".".equals(folder)) {
            return worldRoot.resolve("region");
        }
        return worldRoot.resolve(folder).resolve("region");
    }

    public String getFolderName(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        String cached = pathToFolder.get(normalized);
        if (cached != null) {
            return cached;
        }

        if (useNewFormatForVanilla) {
            String newFormat = buildNewFormatPath(normalized);
            if (newFormat != null) {
                return newFormat;
            }
        }

        if (isVanillaDimension(normalized)) {
            String legacyFolder = VANILLA_LEGACY_FORMAT.get(normalized);
            return legacyFolder != null ? legacyFolder : ".";
        }

        String newFormat = buildNewFormatPath(normalized);
        if (newFormat != null) {
            return newFormat;
        }

        return ".";
    }

    public String getPathFromFolder(String folderName) {

        for (Map.Entry<String, String> entry : pathToFolder.entrySet()) {
            if (entry.getValue().equals(folderName)) {
                return entry.getKey();
            }
        }

        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring("dimensions/".length());
            String[] parts = remaining.split("/", 2);
            if (parts.length == 2) {
                return parts[0] + ":" + parts[1];
            }
            return remaining;
        }

        if (".".equals(folderName) || "region".equals(folderName)) return "overworld";
        if ("DIM-1".equals(folderName)) return "the_nether";
        if ("DIM1".equals(folderName)) return "the_end";

        if (folderName.contains("$")) {
            return folderName.replace('$', ':');
        }

        return folderName;
    }

    public String getXaeroFolder(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalized);
        if (vanillaXaero != null) {
            return vanillaXaero;
        }

        String registered = pathToXaero.get(normalized);
        if (registered != null) {
            return registered;
        }

        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return parts[0] + "$" + encodeXaeroPath(parts[1]);
            }
        }

        return normalized;
    }

    public String getPathFromXaero(String xaeroFolder) {

        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            if (entry.getValue().equals(xaeroFolder)) {
                return entry.getKey();
            }
        }

        if ("null".equals(xaeroFolder)) return "overworld";
        if ("DIM-1".equals(xaeroFolder)) return "the_nether";
        if ("DIM1".equals(xaeroFolder)) return "the_end";

        if (xaeroFolder.contains("$")) {
            int divider = xaeroFolder.indexOf('$');
            return xaeroFolder.substring(0, divider) + ":" + decodeXaeroPath(xaeroFolder.substring(divider + 1));
        }

        return xaeroFolder;
    }

    public String toServerDimension(String clientDim) {
        if (clientDim == null || clientDim.isEmpty()) {
            return "overworld";
        }

        if (clientDim.contains("$")) return getPathFromXaero(clientDim);
        String normalized = normalizeDimPath(clientDim);

        if ("null".equals(normalized)) return "overworld";
        if ("DIM-1".equals(normalized)) return "the_nether";
        if ("DIM1".equals(normalized)) return "the_end";

        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            if (entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }

        return normalized;
    }

    public String toXaeroDimension(String serverDim) {
        if (serverDim == null || serverDim.isEmpty()) {
            return "null";
        }

        if (serverDim.equals("null") || serverDim.equals("DIM-1") || serverDim.equals("DIM1")) {
            return serverDim;
        }
        if (serverDim.contains("$")) {
            return serverDim;
        }
        if (serverDim.startsWith("DIM")) {
            return serverDim;
        }

        return getXaeroFolder(normalizeDimPath(serverDim));
    }

    public String getFriendlyName(String dimPath) {
        String serverDim = toServerDimension(dimPath);
        String normalized = normalizeDimPath(serverDim);
        if (normalized.contains(":")) {
            return normalized;
        }
        return "minecraft:" + normalized;
    }

    private static String encodeXaeroPath(String path) {
        String result = path.replace('/', '%');
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == '.') end--;
        return result.substring(0, end) + ",".repeat(result.length() - end);
    }

    private static String decodeXaeroPath(String path) {
        String result = path.replace('%', '/');
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == ',') end--;
        return result.substring(0, end) + ".".repeat(result.length() - end);
    }

    /** Only one safe Xaero directory component is accepted from the wire. */
    public static boolean isSafeXaeroFolder(String folder) {
        if (folder == null || folder.isEmpty()) return false;
        if (folder.equals("null") || folder.equals("DIM-1") || folder.equals("DIM1")) return true;
        return folder.matches("[a-z0-9_.-]+\\$[a-z0-9_.%,/-]+")
                && folder.indexOf('/') < 0 && !folder.endsWith(".");
    }

    private String normalizeDimPath(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return "overworld";
        }

        if (dimPath.startsWith("minecraft:") && isVanillaDimension(dimPath.substring(10))) {
            dimPath = dimPath.substring(10);
        }

        if ("null".equals(dimPath)) {
            return "overworld";
        }

        return dimPath;
    }

    public boolean isOverworld(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "overworld".equals(normalized) || ".".equals(normalized);
    }

    public boolean isNether(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_nether".equals(normalized) || "DIM-1".equals(normalized);
    }

    public boolean isEnd(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_end".equals(normalized) || "DIM1".equals(normalized);
    }

    public String getRegionRelativePath(String dimPath) {
        String folder = getFolderName(dimPath);
        if (".".equals(folder)) {
            return "region";
        }
        return folder + "/region";
    }

    public void registerMapping(String dimPath, String folderName, String xaeroFolder) {
        pathToFolder.put(dimPath, folderName);
        pathToXaero.put(dimPath, xaeroFolder);
        LOGGER.info("Registered dimension mapping: {} -> folder={}, xaero={}", dimPath, folderName, xaeroFolder);
    }

    public void registerMapping(String dimPath, String folderName) {
        String xaeroFolder = computeXaeroFolderFromFolderName(dimPath, folderName);
        registerMapping(dimPath, folderName, xaeroFolder);
    }

    private String computeXaeroFolderFromFolderName(String dimPath, String folderName) {

        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring("dimensions/".length());
            String[] parts = remaining.split("/", 2);
            if (parts.length == 2) {

                String normalized = normalizeDimPath(dimPath);
                if ("minecraft".equals(parts[0]) && VANILLA_XAERO_MAPPINGS.containsKey(normalized)) {
                    return VANILLA_XAERO_MAPPINGS.get(normalized);
                }

                return parts[0] + "$" + encodeXaeroPath(parts[1]);
            }
        }

        if (folderName.startsWith("DIM") || ".".equals(folderName)) {

            String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalizeDimPath(dimPath));
            if (vanillaXaero != null) {
                return vanillaXaero;
            }
            return folderName;
        }

        return getXaeroFolder(dimPath);
    }

    public void removeMapping(String dimPath) {
        pathToFolder.remove(dimPath);
        pathToXaero.remove(dimPath);
        LOGGER.info("Removed dimension mapping for: {}", dimPath);
    }

    public void clearDetectedMappings() {
        pathToFolder.clear();

        pathToXaero.clear();
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);
        LOGGER.info("Cleared all detected dimension mappings");
    }

    public Map<String, String> getAllFolderMappings() {
        return new HashMap<>(pathToFolder);
    }

    public Map<String, String> getAllXaeroMappings() {
        return new HashMap<>(pathToXaero);
    }

    public Path autoSearchRegionDir(Path worldRoot, String dimId) {
        return detectRegionDir(worldRoot, dimId);
    }

    public int scanAndRegisterDimensions(Path worldRoot) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return 0;
        }

        try {

            Path dimensionsDir = worldRoot.resolve("dimensions");
            if (Files.exists(dimensionsDir)) {
                try (Stream<Path> namespaceStream = Files.list(dimensionsDir)) {
                    namespaceStream.filter(Files::isDirectory)
                        .forEach(namespaceDir -> {
                            String namespace = namespaceDir.getFileName().toString();

                            try (Stream<Path> dimStream = Files.walk(namespaceDir)) {
                                dimStream.filter(Files::isDirectory)
                                    .filter(p -> p.getFileName().toString().equals("region"))
                                    .forEach(regionDir -> {
                                        String dimName = namespaceDir.relativize(regionDir.getParent())
                                                .toString().replace('\\', '/');
                                        if (dimName.isEmpty()) return;
                                        String dimPath = normalizeDimPath(namespace + ":" + dimName);
                                        String folderPath = "dimensions/" + namespace + "/" + dimName;
                                        registerMapping(dimPath, folderPath);
                                    });
                            } catch (IOException e) {
                                LOGGER.warn("Error scanning namespace directory: {}", namespace, e);
                            }
                        });
                }
            }

            if (!useNewFormatForVanilla) {
                try (Stream<Path> rootStream = Files.list(worldRoot)) {
                    rootStream.filter(Files::isDirectory)
                        .forEach(dir -> {
                            String dirName = dir.getFileName().toString();
                            if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {

                                if ("DIM-1".equals(dirName) || "DIM1".equals(dirName)) {
                                    return;
                                }
                                Path regionDir = dir.resolve("region");
                                if (Files.exists(regionDir)) {

                                    LOGGER.info("Found unknown DIM directory: {} (cannot determine dimension ID)", dirName);
                                }
                            }
                        });
                }

                Path overworldRegion = worldRoot.resolve("region");
                if (Files.exists(overworldRegion)) {
                    pathToFolder.put("overworld", ".");
                    LOGGER.info("Confirmed overworld using legacy format: region/");
                }
            }

        } catch (IOException e) {
            LOGGER.warn("Error scanning world directory: {}", e.getMessage());
        }

        return pathToFolder.size();
    }

    public Map<String, String> getDetectedMappingsForConfig() {
        return new LinkedHashMap<>(pathToFolder);
    }
}
