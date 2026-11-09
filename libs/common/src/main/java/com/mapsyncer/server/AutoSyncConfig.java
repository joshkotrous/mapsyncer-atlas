package com.mapsyncer.server;

import com.mapsyncer.platform.UpdateMode;

public final class AutoSyncConfig {

    public static final int DEFAULT_TICK_INTERVAL = 6000;

    public static final int MIN_TICK_INTERVAL = 2400;

    public static final int MAX_TICK_INTERVAL = 72000;

    private AutoSyncConfig() {}

    public static int computeInterval(UpdateMode mode, int intervalTicks) {
        switch (mode) {
            case DISABLED:  return 0;
            case TICK:      return ticksToMinutes(intervalTicks);
            case SCHEDULED: return 1440;
            default:        return 0;
        }
    }

    public static int ticksToMinutes(int intervalTicks) {
        return Math.max(1, intervalTicks / 20 / 60);
    }

    public static long ticksToPeriodMs(int intervalTicks) {
        return Math.max(1L, intervalTicks) * 50L;
    }
}
