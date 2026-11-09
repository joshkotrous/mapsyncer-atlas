# MapSyncer Atlas

Share your server's explored and pregenerated terrain through Xaero's World Map. Atlas converts saved world regions into map tiles and lets players download them instead of revisiting every area themselves.

This is an independent community fork of [MapSyncer for Xaero's World Map](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) by **Ruo_Chen**, who created the synchronization engine. Atlas is maintained by **joshkotrous** under GPL-3.0, retaining upstream history and attribution.

## Atlas changes

- `/mapsyncer guide`: an in-game setup and troubleshooting checklist explaining chunk generation, tile conversion and map downloads.
- English messages and generated configuration comments across the retained source targets, retaining existing configuration keys and values. Chinese source comments were removed; the shipped language resources use English.
- English-first setup documentation and distinct icon and project identity.
- License text and attribution included in the JAR.
- Bundled LZ4 updated to the maintained community version 1.14.0.
- Bounded map-fragment assembly: reject excessive counts, changing metadata, conflicting duplicates and oversized buffers before unsafe allocation.
- Automated checks for English implementation text and compiled JAR constants/resources.
- A focused Windows build with a Gradle 9-compatible toolchain resolver.

The initial artifact targets **Minecraft Java 26.3 / Fabric / Java 25**. Other platform folders are retained upstream source and do not represent tested Atlas releases. The initial build is experimental; see its release notes for actual validation.

## Installation

1. Stop Minecraft and the server. Back up the world, configuration and personal Xaero maps.
2. Install Fabric and Fabric API for Minecraft 26.3 on both sides.
3. Install Atlas in the server's and participating clients' `mods` folders.
4. Clients also need **Xaero's World Map**; the target setup uses 1.47.0. The server does not need that mod.
5. Remove upstream MapSyncer before adding Atlas. Both use mod ID `mapsyncer`; install only one.
6. Start Minecraft and use `/mapsyncer guide`.

Cloth Config and Mod Menu are optional client integrations. Core, platform support and relocated LZ4 are bundled in the distribution JAR. Minecraft, Fabric API and Xaero are external prerequisites.

## Sharing terrain

Server operators:

```text
/mapsyncerserver generate
/mapsyncerserver status
/mapsyncerserver incremental tick 12000
```

Players:

```text
/mapsyncer sync all
/mapsyncer autosync on
```

At 20 TPS, 12,000 ticks is about ten minutes. Atlas converts already saved chunks. Explore or pregenerate missing terrain with a separate tool such as Chunky, then convert and synchronize. Large conversions consume CPU and disk space.

Atlas shares terrain tiles, not player positions or personal waypoints. Location sharing requires a separate tracking add-on. Server owners should review hidden builds before sharing a full terrain map.

## Configuration and troubleshooting

Fabric configuration is in `config/mapsyncer-server.properties` and `config/mapsyncer-client.properties`. Existing packet IDs, commands, configuration keys and cache format are retained; cross-version interoperability is not newly certified.

- Wait for server conversion to finish before expecting every tile to download.
- Check `/mapsyncerserver status` and the logs when maps are empty.
- Ensure both sides have the mod, and clients have Xaero's World Map.
- Keep the appropriate Xaero map instance selected. Back up local maps before Realm cache migration.
- Set `maxConcurrentRegions=1` on small servers and `syncSpeedLimitKBps` to cap transfers.
- `syncRadiusRegions=0` allows all prepared regions. A positive number restricts downloads around the player in their current dimension.

Original detailed English documentation: [README_EN.md](README_EN.md). The legacy [upstream README path](README_ZH_UPSTREAM.md) now contains the same English documentation. Historical design notes remain as upstream reference material; they are not compiled or packaged. Historical upstream testing is not an Atlas compatibility certification.

## Build

Install Python 3 for the release checks, set `JAVA_HOME` to a Java 25 JDK, and run from Windows Command Prompt:

```bat
scripts\fastbuild\build-atlas.bat
scripts\fastbuild\test-atlas.bat
```

Linux/WSL uses the matching `build-atlas.sh` and `test-atlas.sh` entrypoints.

The script restores settings after the build and collects the distribution JAR under `output/`. The Atlas build uses Java 25 throughout, retaining the upstream libraries' Java 17 bytecode target. Gradle downloads required dependencies. After a forcibly interrupted build, restore `settings.atlas-backup.gradle` before retrying. Temporary files stay under `cache/`.

## Credits, source and license

Original MapSyncer: **Ruo_Chen**, [upstream](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap). Atlas changes: **joshkotrous**, 2026-11-09. This is not an official release by Ruo_Chen or Xaero. See [NOTICE](NOTICE) and [GPL-3.0 license](LICENSE). Bundled dependencies retain their respective licenses.

[Atlas source and issue tracker](https://github.com/joshkotrous/mapsyncer-atlas).
