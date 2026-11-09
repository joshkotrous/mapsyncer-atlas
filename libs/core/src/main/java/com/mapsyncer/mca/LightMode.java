package com.mapsyncer.mca;

public enum LightMode {

    SURFACE,

    CAVE;

    public byte calculateEffectiveLight(byte blockLight, byte skyLight,
                                         boolean hasSkyAccess, boolean hasOverlay,
                                         boolean isGlowing, boolean worldHasSkylight) {

        if (isGlowing) {
            return 15;
        }

        switch (this) {
            case SURFACE:

                return blockLight;

            case CAVE:

                if (blockLight >= 15) {
                    return blockLight;
                }

                byte effectiveSkyLight = (hasSkyAccess && worldHasSkylight) ? 15 : skyLight;

                if (!hasOverlay && effectiveSkyLight > blockLight) {
                    return effectiveSkyLight;
                }

                return blockLight;

            default:
                return blockLight;
        }
    }

    public byte getDefaultSkyLight(boolean worldHasSkylight) {
        switch (this) {
            case SURFACE:
                return (byte) 0;
            case CAVE:
                return worldHasSkylight ? (byte) 15 : (byte) 0;
            default:
                return (byte) 0;
        }
    }

    public boolean needsSkyLightData() {
        return this == CAVE;
    }
}
