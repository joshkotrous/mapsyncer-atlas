package com.mapsyncer.mca;

import java.util.Map;

public interface BlockPropertyLookup {

    // flags bit definitions
    int FLAG_WATER = 1;
    int FLAG_TRANSPARENT = 2;
    int FLAG_INVISIBLE = 4;
    int FLAG_SHOULD_OVERLAY = 8;
    int FLAG_HAS_VANILLA_COLOR = 16;
    int FLAG_GLOWING = 32;
    int FLAG_TRANSLUCENT_FLUID = 64;
    int FLAG_WATER_INHERITING = 128;

    int getFlags(String blockName);

    boolean isWater(String blockName);

    boolean isTransparent(String blockName);

    boolean isInvisible(String blockName);

    boolean shouldOverlay(String blockName);

    boolean hasVanillaColor(String blockName);

    boolean isGrassBlock(String blockName);

    boolean isGlowing(String blockName);

    boolean isTranslucentFluid(String blockName);

    boolean isWaterloggedSurface(String blockName, Map<String, String> properties);

    boolean isWaterInheriting(String blockName);

    int getLightBlock(String blockName);
}
