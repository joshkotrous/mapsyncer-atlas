package com.mapsyncer.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class DimensionApiHelper {

    private DimensionApiHelper() {}

    public static String getDimId(ResourceKey<Level> key) {
        return key.location().toString();
    }
}
