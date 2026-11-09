package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class DimensionConfigParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionConfigParser.class);

    public static final int DEFAULT_CAVE_START = LayerPlan.DEFAULT_CAVE_START;

    public static final String LIST_KEY = "dimension_configs";

    public static final String PROPERTIES_ENTRY_PREFIX = "dimensionConfig.";

    public static final String PROPERTIES_LEGACY_JOINED_KEY = "dimensionConfigs";

    private static volatile String cachedKey;
    private static volatile List<DimensionScanConfig> cachedResult;

    private DimensionConfigParser() {}

    public static String formatEntry(String dimension, LayerPlan layerPlan) {
        if (dimension == null || dimension.isBlank()) {
            return "";
        }
        String plan = layerPlan == null ? "" : layerPlan.toConfigString();
        if (plan.isEmpty()) {
            return dimension.trim();
        }
        return dimension.trim() + " = " + plan;
    }

    public static List<String> getDefaultDimensionConfigStrings() {
        List<String> defaults = new ArrayList<>(3);
        defaults.add(formatEntry("minecraft:overworld", LayerPlan.surfaceOnly()));
        defaults.add(formatEntry("minecraft:the_nether", LayerPlan.mixed(DEFAULT_CAVE_START)));
        defaults.add(formatEntry("minecraft:the_end", LayerPlan.surfaceOnly()));
        return defaults;
    }

    public static void invalidateCache() {
        cachedKey = null;
        cachedResult = null;
    }

    public static List<DimensionScanConfig> parseDimensionConfigs(List<? extends String> dimensionConfigs) {
        String key = String.join("\0", dimensionConfigs);
        synchronized (DimensionConfigParser.class) {
            if (key.equals(cachedKey)) {
                List<DimensionScanConfig> r = cachedResult;
                if (r != null) return r;
            }
            List<DimensionScanConfig> result = new ArrayList<>(dimensionConfigs.size());
            for (String configStr : dimensionConfigs) {
                DimensionScanConfig config = parseConfigString(configStr);
                if (config != null) result.add(config);
            }
            cachedKey = key;
            cachedResult = List.copyOf(result);
            return cachedResult;
        }
    }

    public static DimensionScanConfig parseConfigString(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return null;
        }

        String trimmed = configStr.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.indexOf('|') >= 0) {
            return parsePipeFormat(trimmed);
        }

        int eq = trimmed.indexOf('=');
        if (eq >= 0) {
            String dimension = trimmed.substring(0, eq).trim();
            String planStr = trimmed.substring(eq + 1).trim();
            if (dimension.isEmpty()) {
                LOGGER.warn("Invalid dimension config (empty dimension): [{}]", configStr);
                return null;
            }
            LayerPlan layerPlan = planStr.isEmpty() ? LayerPlan.empty() : LayerPlan.parse(planStr);
            return new DimensionScanConfig(dimension, layerPlan, DimensionTypeInfo.fromDimensionId(dimension));
        }

        return new DimensionScanConfig(trimmed, LayerPlan.empty(), DimensionTypeInfo.fromDimensionId(trimmed));
    }

    private static DimensionScanConfig parsePipeFormat(String configStr) {
        String[] parts = configStr.split("\\|", -1);
        if (parts.length < 1 || parts[0].trim().isEmpty()) {
            return null;
        }

        String dimension = parts[0].trim();
        LayerPlan layerPlan = LayerPlan.empty();
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimension);

        int dimTypeStartIndex;
        if (parts.length > 2 && isLegacyScanModeToken(parts[1]) && !looksLikeDimTypeField(parts[2])) {
            ScanMode legacyMode;
            try {
                legacyMode = ScanMode.valueOf(parts[1].trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid legacy scan_mode '{}' in [{}], treating as layer plan",
                    parts[1], configStr);
                layerPlan = LayerPlan.parse(parts[1]);
                dimTypeStartIndex = 2;
                return finishParse(dimension, layerPlan, dimTypeInfo, parts, dimTypeStartIndex, configStr);
            }
            layerPlan = LayerPlan.fromLegacy(legacyMode, parts.length > 2 ? parts[2] : "");
            dimTypeStartIndex = 3;
        } else {
            layerPlan = parts.length > 1 ? LayerPlan.parse(parts[1]) : LayerPlan.empty();
            dimTypeStartIndex = 2;
        }

        return finishParse(dimension, layerPlan, dimTypeInfo, parts, dimTypeStartIndex, configStr);
    }

    private static DimensionScanConfig finishParse(String dimension, LayerPlan layerPlan,
            DimensionTypeInfo dimTypeInfo, String[] parts, int dimTypeStartIndex, String configStr) {
        return new DimensionScanConfig(dimension, layerPlan, dimTypeInfo);
    }

    private static boolean isLegacyScanModeToken(String s) {
        return "SURFACE".equalsIgnoreCase(s.trim()) || "CAVE".equalsIgnoreCase(s.trim());
    }

    private static boolean looksLikeDimTypeField(String s) {
        String t = s.trim();
        return "true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t);
    }

    public static List<String> loadDimensionConfigEntries(String fileText, Properties props) {
        List<String> fromList = parseDimensionConfigsListBlock(fileText);
        if (fromList != null) {
            return fromList;
        }
        return loadEntriesFromProperties(props != null ? props : new Properties());
    }

    public static List<String> parseDimensionConfigsListBlock(String fileText) {
        if (fileText == null || fileText.isEmpty()) {
            return null;
        }
        String marker = LIST_KEY;
        int keyIdx = indexOfIgnoreCase(fileText, marker);
        while (keyIdx >= 0) {

            int lineStart = fileText.lastIndexOf('\n', keyIdx) + 1;
            String linePrefix = fileText.substring(lineStart, keyIdx).trim();
            if (linePrefix.startsWith("#") || linePrefix.startsWith("!")) {
                keyIdx = indexOfIgnoreCase(fileText, marker, keyIdx + marker.length());
                continue;
            }
            int eq = fileText.indexOf('=', keyIdx + marker.length());
            if (eq < 0) {
                return null;
            }
            int bracket = skipWhitespace(fileText, eq + 1);
            if (bracket >= fileText.length() || fileText.charAt(bracket) != '[') {
                keyIdx = indexOfIgnoreCase(fileText, marker, keyIdx + marker.length());
                continue;
            }
            int close = findMatchingListClose(fileText, bracket);
            if (close < 0) {
                LOGGER.warn("Unclosed {} list in config; falling back to legacy dimension config keys",
                        LIST_KEY);
                return null;
            }
            String body = fileText.substring(bracket + 1, close);
            return parseListBodyEntries(body);
        }
        return null;
    }

    public static String stripDimensionConfigsListBlock(String fileText) {
        if (fileText == null || fileText.isEmpty()) {
            return fileText == null ? "" : fileText;
        }
        String marker = LIST_KEY;
        int keyIdx = indexOfIgnoreCase(fileText, marker);
        while (keyIdx >= 0) {
            int lineStart = fileText.lastIndexOf('\n', keyIdx) + 1;
            String linePrefix = fileText.substring(lineStart, keyIdx).trim();
            if (linePrefix.startsWith("#") || linePrefix.startsWith("!")) {
                keyIdx = indexOfIgnoreCase(fileText, marker, keyIdx + marker.length());
                continue;
            }
            int eq = fileText.indexOf('=', keyIdx + marker.length());
            if (eq < 0) {
                break;
            }
            int bracket = skipWhitespace(fileText, eq + 1);
            if (bracket >= fileText.length() || fileText.charAt(bracket) != '[') {
                keyIdx = indexOfIgnoreCase(fileText, marker, keyIdx + marker.length());
                continue;
            }
            int close = findMatchingListClose(fileText, bracket);
            if (close < 0) {
                break;
            }
            int end = close + 1;
            if (end < fileText.length() && fileText.charAt(end) == '\r') {
                end++;
            }
            if (end < fileText.length() && fileText.charAt(end) == '\n') {
                end++;
            }
            return fileText.substring(0, lineStart) + fileText.substring(end);
        }
        return fileText;
    }

    private static List<String> parseListBodyEntries(String body) {
        List<String> entries = new ArrayList<>();
        for (String rawLine : body.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            if (line.endsWith(",")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            if (line.length() >= 2 && line.charAt(0) == '"' && line.charAt(line.length() - 1) == '"') {
                line = unescapeQuoted(line.substring(1, line.length() - 1));
            } else if (line.length() >= 2 && line.charAt(0) == '\'' && line.charAt(line.length() - 1) == '\'') {
                line = line.substring(1, line.length() - 1);
            }
            if (!line.isEmpty()) {
                entries.add(line);
            }
        }
        return entries;
    }

    private static String unescapeQuoted(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeQuoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int findMatchingListClose(String text, int openBracket) {
        for (int i = openBracket + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i++;
                while (i < text.length()) {
                    char q = text.charAt(i);
                    if (q == '\\' && i + 1 < text.length()) {
                        i += 2;
                        continue;
                    }
                    if (q == '"') {
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == ']') {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String text, int from) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return indexOfIgnoreCase(text, needle, 0);
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        final int len = needle.length();
        for (int i = from; i <= text.length() - len; i++) {
            if (text.regionMatches(true, i, needle, 0, len)) {
                return i;
            }
        }
        return -1;
    }

    public static List<String> loadEntriesFromProperties(Properties props) {
        List<String> numbered = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = props.getProperty(PROPERTIES_ENTRY_PREFIX + i);
            if (value == null) {
                break;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                numbered.add(trimmed);
            }
        }
        if (!numbered.isEmpty()) {
            return numbered;
        }

        List<String> joined = new ArrayList<>();
        String dimsStr = props.getProperty(PROPERTIES_LEGACY_JOINED_KEY, "");
        if (dimsStr != null && !dimsStr.isEmpty()) {
            for (String dim : dimsStr.split(";")) {
                if (!dim.trim().isEmpty()) {
                    joined.add(dim.trim());
                }
            }
        }
        return joined;
    }

    public static void appendEntriesToPropertiesFile(StringBuilder sb, List<String> dimensionConfigs) {
        sb.append("# Dimension scan configuration (same list format as Forge/NeoForge)\n");
        sb.append("# Format: \"dimension = layerPlan\"\n");
        sb.append("# layerPlan: SURFACE, ALL, explicit Y (such as 63), or combinations (such as SURFACE,63)\n");
        sb.append("# Example: \"minecraft:the_nether = SURFACE,63\"\n");
        sb.append("# Legacy pipe, dimensionConfig.N and semicolon-separated formats remain readable\n");
        sb.append("#\n");
        sb.append("# Per-dimension scan configuration (same list style as Forge/NeoForge)\n");
        sb.append("# Format: \"dimension = layerPlan\"\n");
        sb.append("# layerPlan: SURFACE, ALL, explicit Y (e.g. 63), or combos (e.g. SURFACE,63)\n");
        sb.append("# Example: \"minecraft:the_nether = SURFACE,63\"\n");
        sb.append("# Legacy pipe / dimensionConfig.N / dimensionConfigs=a;b still load\n");
        sb.append(LIST_KEY).append(" = [\n");
        if (dimensionConfigs != null) {
            for (String entry : dimensionConfigs) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                sb.append("    \"").append(escapeQuoted(entry.trim())).append("\",\n");
            }
        }
        sb.append("]\n");
    }

    public static DimensionScanConfig getConfigForDimension(String dimensionPath,
            List<? extends String> dimensionConfigs, ScanMode defaultMode, int defaultCave) {
        LayerPlan defaultPlan = defaultMode == ScanMode.SURFACE
            ? LayerPlan.surfaceOnly()
            : LayerPlan.caves(defaultCave);

        List<DimensionScanConfig> parsed = parseDimensionConfigs(dimensionConfigs);

        String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();
        boolean isVanilla = normalizedPath.equals("the_nether")
                         || normalizedPath.equals("overworld")
                         || normalizedPath.equals("the_end");

        for (DimensionScanConfig config : parsed) {
            String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
            if (configDim.equals(normalizedPath)) return config;
            if (configDim.equalsIgnoreCase(dimensionPath)
                || configDim.equalsIgnoreCase("minecraft:" + dimensionPath)) return config;
        }

        if (isVanilla) {
            switch (normalizedPath) {
                case "the_nether":
                    return new DimensionScanConfig("minecraft:the_nether",
                        LayerPlan.mixed(DEFAULT_CAVE_START), DimensionTypeInfo.nether());
                case "overworld":
                    return new DimensionScanConfig("minecraft:overworld",
                        LayerPlan.surfaceOnly(), DimensionTypeInfo.overworld());
                default:
                    return new DimensionScanConfig("minecraft:the_end",
                        LayerPlan.surfaceOnly(), DimensionTypeInfo.theEnd());
            }
        }

        return new DimensionScanConfig(dimensionPath, defaultPlan,
            DimensionTypeInfo.fromDimensionId(dimensionPath));
    }
}
