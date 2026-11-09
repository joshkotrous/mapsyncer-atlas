package com.mapsyncer.sync;

public enum SyncOutcome {

    NONE,

    SUCCESS,

    PARTIAL_SUCCESS,

    SILENT_SKIP,

    HARD_FAIL;

    public static SyncOutcome fromServerStatus(String status) {
        return switch (status) {
            case "no_cache", "dim_not_available", "integrated_host" -> HARD_FAIL;
            case "uptodate" -> SILENT_SKIP;
            case "partial" -> PARTIAL_SUCCESS;
            case "ok" -> SUCCESS;
            default -> NONE;
        };
    }
}
