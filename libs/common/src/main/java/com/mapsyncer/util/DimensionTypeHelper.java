package com.mapsyncer.util;

import com.mapsyncer.mca.DimensionTypeInfo;
import net.minecraft.world.level.dimension.DimensionType;

public final class DimensionTypeHelper {

    private DimensionTypeHelper() {

    }

    public static DimensionTypeInfo fromDimensionType(DimensionType dimensionType) {
        return new DimensionTypeInfo(
            dimensionType.hasSkyLight(),
            dimensionType.hasCeiling(),
            dimensionType.minY(),
            dimensionType.height(),
            dimensionType.logicalHeight()
        );
    }
}
