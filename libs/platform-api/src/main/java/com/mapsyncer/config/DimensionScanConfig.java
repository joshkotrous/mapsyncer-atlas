package com.mapsyncer.config;

import com.mapsyncer.mca.DimensionTypeInfo;

public record DimensionScanConfig(
    String dimension,
    LayerPlan layerPlan,
    DimensionTypeInfo dimTypeInfo
) {
    public DimensionScanConfig(String dimension, LayerPlan layerPlan) {
        this(dimension, layerPlan, null);
    }

    @Deprecated
    public ScanMode scanMode() {
        if (layerPlan.includeSurface() && !layerPlan.includeAllCaves() && layerPlan.caveStarts().isEmpty()) {
            return ScanMode.SURFACE;
        }
        if (!layerPlan.includeSurface() && (layerPlan.includeAllCaves() || !layerPlan.caveStarts().isEmpty())) {
            return ScanMode.CAVE;
        }
        return layerPlan.includeSurface() ? ScanMode.SURFACE : ScanMode.CAVE;
    }

    public int caveStart() {
        return layerPlan.primaryCaveStart();
    }

    public int getCaveLayer() {
        if (layerPlan.includeSurface() && !layerPlan.includeAllCaves() && layerPlan.caveStarts().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int start = caveStart();
        if (start == Integer.MAX_VALUE || start == Integer.MIN_VALUE) {
            return start;
        }
        return start >> 4;
    }

    public int getCaveDepth(int minBuildHeight) {
        if (layerPlan.includeSurface() && !layerPlan.includeAllCaves() && layerPlan.caveStarts().isEmpty()) {
            return 0;
        }
        int start = caveStart();
        if (start == Integer.MIN_VALUE) {
            return Math.max(30, start - minBuildHeight);
        }
        return 15;
    }

    public DimensionTypeInfo getDimensionTypeInfo() {
        if (dimTypeInfo != null) {
            return dimTypeInfo;
        }
        return DimensionTypeInfo.fromDimensionId(dimension);
    }
}
