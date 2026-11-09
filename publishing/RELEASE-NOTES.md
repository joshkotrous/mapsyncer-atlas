# MapSyncer Atlas 1.0.4-atlas.1 — Fabric 26.3 (Alpha)

Independent GPL-3.0 community fork of Ruo_Chen's MapSyncer, upstream baseline 85384f8.

Adds /mapsyncer guide, English generated configuration comments, English-first install/troubleshooting documentation, distinct icon, bundled license and credits, and a focused Windows build with a Gradle 9-compatible toolchain resolver. Existing mapsyncer ID, packets, commands, config keys and terrain cache format are retained.

Requires Minecraft Java 26.3, Java 25, Fabric and Fabric API. Clients also need Xaero's World Map (target setup 1.47.0). Do not install alongside upstream MapSyncer. Optional Mod Menu and Cloth Config remain separate client integrations.

Validation: Fabric 26.3 release build passed; 2 Python tests and inherited Java suites passed 15,187 + 31 + 72 + 4,031 assertions. JAR inspection verified metadata, bundled protocol/core support and relocated LZ4, English language resources, guide, license/credits and absence of Mod Menu API stubs. These are automated checks; Atlas-specific live gameplay, multi-client synchronization and LAN tests have not been performed. Distributed as Alpha while those checks remain outstanding. No changes were deployed to the existing production Minecraft server.

SHA-256: 6513ae4c1c5cc516bff9414b4a128e3050d5b8fe41c067a2b1a90b0a8034d7c8

Bundled LZ4 updated from the discontinued org.lz4 1.8.0 dependency to the maintained at.yawk.lz4 1.14.0, retaining the relocated package and including its Apache license.
