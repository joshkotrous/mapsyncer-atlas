package com.mapsyncer.mca.convert.scan;

public record ScanVerticalBounds(int floorY, int ceilingY) {

    public static ScanVerticalBounds unbounded() {
        return new ScanVerticalBounds(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static ScanVerticalBounds fullColumn(int minBuildHeight, int worldTopY) {
        return new ScanVerticalBounds(minBuildHeight, worldTopY - 1);
    }

    public static ScanVerticalBounds aboveY(int floorY, int worldTopY) {
        return new ScanVerticalBounds(floorY, worldTopY - 1);
    }

    public int clampStartY(int startY) {
        return Math.min(startY, ceilingY);
    }

    public int clampBottomY(int minBuildHeight, int scanBottomY) {
        return Math.max(scanBottomY, Math.max(minBuildHeight, floorY));
    }

    public int resolveSurfaceStartY(int heightmapStartY) {
        if (floorY > Integer.MIN_VALUE) {
            return ceilingY;
        }
        return clampStartY(heightmapStartY);
    }

    public boolean ignoresHeightmap() {
        return floorY > Integer.MIN_VALUE;
    }
}
