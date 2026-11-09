# MapSyncer Atlas 1.0.4-atlas.2 — Fabric 26.3 Alpha

Independent GPL-3.0 community fork of Ruo_Chen's MapSyncer, upstream baseline 85384f8. Original-author credits, source history, GPL text and bundled dependency notices are retained.

## Changes

- Translate all 196 distinct Chinese implementation strings, including logging, parse errors and generated configuration help, across the retained platform source.
- Remove Chinese implementation comments and use English language-resource values. Update top-level guidance in English; historical upstream design notes are reference material and are not packaged.
- Add source and compiled-JAR language checks, ZIP integrity checks and duplicate-entry checks. The built artifact contains no Chinese ideographs in class constants or decoded text resources.
- Fix map-fragment crashes caused by excessive total-part counts and inconsistent counts within a region. Validate before allocation, cap fragment/group counts and buffered bytes, reject changed timestamps and conflicting duplicates, and expire incomplete groups.
- Add 61 fragment regressions and version-aware Windows/Linux build and test entrypoints.

The mapsyncer mod ID, packet format, cache format and configuration keys remain unchanged. Do not install alongside upstream MapSyncer.

## Requirements and validation

Minecraft Java 26.3, Java 25, Fabric and Fabric API. Install Atlas on the server and participating clients. Clients also need Xaero's World Map; target setup 1.47.0. Cloth Config and Mod Menu remain optional client integrations. Internal support and relocated LZ4 Java 1.14.0 are bundled in this single JAR.

Fabric 26.3 release build passed. Two Python tests and 19,382 Java regression assertions/checks passed, including the new fragment tests with a 64 MiB heap. English checks passed for 406 implementation/build files and 276 compiled classes, plus packaged text resources. Metadata, required licenses/support, ZIP integrity and absence of bundled Mod Menu API stubs were verified. Windows Defender custom scan with remediation disabled found no threats.

These are automated checks, not a guarantee against every vulnerability. Atlas-specific live gameplay, multi-client synchronization and LAN tests have not been performed for this artifact. It remains an Alpha. No new release was deployed to the existing production Minecraft server.

Source: https://github.com/joshkotrous/mapsyncer-atlas

Original: https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap

SHA-256: e9078c34973134ebfac780b31b5707769d1f4e70aa9ed6c8ed7c2baa4e2f6f85
