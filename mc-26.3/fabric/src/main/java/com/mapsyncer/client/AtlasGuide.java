package com.mapsyncer.client;

/** Local setup help: never sends commands or changes the server. */
public final class AtlasGuide {
    private AtlasGuide() {}

    public static String[] lines() {
        return new String[] {
            "[MapSyncer Atlas] Getting started",
            "1. Install Atlas on the server and each client; clients also need Xaero's World Map.",
            "2. Server operators: /mapsyncerserver generate converts saved chunks into map tiles.",
            "3. Players: /mapsyncer sync all downloads available tiles; /mapsyncer autosync on enables updates.",
            "4. Operators: /mapsyncerserver incremental tick 12000 refreshes changed tiles about every 10 minutes at 20 TPS.",
            "5. More terrain needs explored or pregenerated chunks (for example with Chunky), then conversion and sync.",
            "Map tiles do not include player tracking or personal waypoints. Location sharing needs a separate add-on.",
            "Troubleshooting: allow conversion to finish, keep the same world-map instance selected, then retry sync.",
            "Do not install Atlas alongside upstream MapSyncer: both use mod ID mapsyncer."
        };
    }
}
