package com.mapsyncer.client;

public final class RegionLoadThrottle {

    private static int ticksUntilNextLoad = 0;

    private RegionLoadThrottle() {}

    public static void reset() {
        ticksUntilNextLoad = 0;
    }

    public static boolean isUnlimited(int intervalTicks) {
        return intervalTicks == -1;
    }

    public static boolean isViewOnly(int intervalTicks) {
        return intervalTicks == 0;
    }

    public static boolean shouldDrainOne(int intervalTicks) {
        if (intervalTicks <= 0) {
            return false;
        }
        if (ticksUntilNextLoad > 0) {
            ticksUntilNextLoad--;
            return false;
        }
        ticksUntilNextLoad = intervalTicks - 1;
        return true;
    }
}
