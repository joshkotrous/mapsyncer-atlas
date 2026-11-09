# MapSyncer Atlas changelog

## 1.0.4-atlas.2 — 2026-11-09

- Translate all 196 distinct Chinese implementation strings, including logs, parse errors and configuration help, across retained platform targets.
- Remove Chinese implementation comments; use English language-resource values and English top-level guidance.
- Add source/JAR checks that inspect implementation files, class constant pools and decoded resources for CJK ideographs, and validate ZIP integrity and duplicate paths.
- Fix unbounded map-fragment allocation and inconsistent fragment-count indexing. Bound fragment counts, pending groups, cumulative bytes and per-region bytes; reject changed timestamps and conflicting duplicates; clear stale groups and disconnect state.
- Add 61 deterministic fragment regressions. Keep the mod ID, packet format, cache format and configuration keys unchanged.
- Add version-aware Windows and Linux build/test entrypoints. Continue shipping a self-contained Fabric 26.3 / Java 25 Alpha.

## 1.0.4-atlas.1 — 2026-11-09

- Independent English-first fork of Ruo_Chen's MapSyncer, baseline 85384f8.
- In-game setup guide, English Fabric 26.3 generated configuration comments, distinct identity, bundled attribution and licenses, maintained LZ4 Java 1.14.0 and focused Windows build.

## Upstream history

Earlier implementation and test history are retained in Git. See the [original project](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) and [English upstream overview](README_EN.md). Historical platform tests are not Atlas runtime compatibility certification.
