package com.mapsyncer.mca;

public record DimensionTypeInfo(
    boolean hasSkylight,
    boolean hasCeiling,
    int minY,
    int height,
    int logicalHeight
) {

    public int maxY() {
        return minY + height;
    }

    public int logicalTopY() {
        return minY + logicalHeight - 1;
    }

    public boolean hasUpperZone() {
        return hasCeiling && logicalHeight < height;
    }

    public static DimensionTypeInfo overworld() {
        return new DimensionTypeInfo(true, false, -64, 384, 384);
    }

    public static DimensionTypeInfo nether() {
        return new DimensionTypeInfo(false, true, 0, 256, 128);
    }

    public static DimensionTypeInfo theEnd() {
        return new DimensionTypeInfo(false, false, 0, 256, 256);
    }

    public static DimensionTypeInfo fromDimensionId(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return overworld();
        }

        String normalized = dimensionId
            .replace("minecraft:", "")
            .toLowerCase();

        switch (normalized) {
            case "overworld":
                return overworld();
            case "the_nether":
                return nether();
            case "the_end":
                return theEnd();
            default:

                return overworld();
        }
    }

    public byte getDefaultSkyLight() {
        return hasSkylight ? (byte) 15 : (byte) 0;
    }

    public boolean isCaveDimension() {
        return hasCeiling;
    }

    public int getRecommendedCaveStart() {
        if (hasCeiling) {

            return Math.max(minY + 32, (minY + height) / 2 - 32);
        }

        return Math.max(minY, 63);
    }

    public String toConfigString() {
        return hasSkylight + "|" + hasCeiling + "|" + minY + "|" + height + "|" + logicalHeight;
    }

    public static DimensionTypeInfo fromConfigString(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return overworld();
        }

        String[] parts = configStr.split("\\|");
        if (parts.length < 4) {
            return overworld();
        }

        try {
            boolean hasSkylight = Boolean.parseBoolean(parts[0]);
            boolean hasCeiling = Boolean.parseBoolean(parts[1]);
            int minY = Integer.parseInt(parts[2]);
            int height = Integer.parseInt(parts[3]);
            int logicalHeight = parts.length > 4 ? Integer.parseInt(parts[4]) : height;

            return new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
        } catch (NumberFormatException e) {
            return overworld();
        }
    }

    @Override
    public String toString() {
        return String.format("DimensionTypeInfo[hasSkylight=%s, hasCeiling=%s, minY=%d, height=%d, maxY=%d]",
            hasSkylight, hasCeiling, minY, height, maxY());
    }
}
