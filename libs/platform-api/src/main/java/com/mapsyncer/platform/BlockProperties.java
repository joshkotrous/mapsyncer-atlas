package com.mapsyncer.platform;

public record BlockProperties(
    boolean isAir,
    boolean isWater,
    boolean isLava,
    boolean isFluid,
    boolean isTransparent,
    boolean isInvisible,
    boolean isFlower,
    boolean isPlant,
    boolean isGrassBlock,
    boolean isGlowing,
    int lightBlock,
    int lightEmission,
    boolean canBeWaterlogged,
    int mapColor
) {

    public static final BlockProperties EMPTY = new BlockProperties(
        false, false, false, false, false, false, false, false,
        false, false, 15, 0, false, 0x808080
    );

    public boolean shouldOverlay() {
        return isWater || isTransparent;
    }

    public boolean isTranslucentFluid() {
        return isWater;
    }

    public boolean isWaterloggedSurface(boolean waterlogged) {
        return canBeWaterlogged && waterlogged && !isWater && !isAir;
    }
}
