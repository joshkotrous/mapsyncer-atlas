package com.mapsyncer.mca.convert.scan;

import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;

public record RegionScanPass(
    int caveLayer,
    LightMode lightMode,
    CaveModeParams caveParams,
    ScanVerticalBounds verticalBounds
) {
    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }
}
