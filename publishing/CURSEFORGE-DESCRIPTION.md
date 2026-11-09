# Share the terrain your server already knows

MapSyncer Atlas lets players download their server's explored or pregenerated terrain into **Xaero's World Map**. Joining an established world becomes easier: see prepared terrain without walking across every old build and biome again.

Atlas is an independent community fork of **[MapSyncer for Xaero's World Map](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap)** by **Ruo_Chen**, who created its conversion and synchronization engine. Maintained by **joshkotrous** under **GPL-3.0**, this fork adds an in-game `/mapsyncer guide`, English messages and configuration output, English setup documentation, bounded map-fragment reassembly, distinct branding and focused build packaging.

## Current Alpha

**Minecraft Java 26.3, Fabric, Java 25.** Experimental Alpha; see the file changelog for actual validation. Other loaders and game versions are not included in this release.

## Install

Install Atlas and Fabric API on both the server and participating clients. Clients also need **Xaero's World Map**; the target setup uses 1.47.0. The server does not need Xaero. Cloth Config and Mod Menu are optional client integrations.

Replace upstream MapSyncer instead of installing both: they share mod ID `mapsyncer`. Back up the world and personal maps before replacing mods or migrating caches.

## Start sharing

Server operators run `/mapsyncerserver generate` to convert saved chunks, then check `/mapsyncerserver status`. Players run `/mapsyncer sync all` and `/mapsyncer autosync on`. Type `/mapsyncer guide` for the full in-game checklist.

Optional periodic conversion: `/mapsyncerserver incremental tick 12000` refreshes changed regions about every ten minutes at 20 TPS. Large conversions and downloads take time.

Atlas does not create unexplored chunks. Explore or pregenerate them with a separate tool such as Chunky, then convert and synchronize. Player positions require a separate tracking add-on, and personal waypoints remain separate from terrain tiles. Server owners should consider hidden builds before sharing their full map.

## Support and credits

[Atlas source and issue tracker](https://github.com/joshkotrous/mapsyncer-atlas). Include game version, loader and relevant errors with private information removed. Direct Atlas-specific support requests to this fork.

Original project and synchronization engine: **Ruo_Chen**, [MapSyncer upstream](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap). This independent fork is not an official release by Ruo_Chen or Xaero.
