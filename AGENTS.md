# MapSyncer project conventions

## Test environment

All in-game testing uses the fixed local environments below. Do not create temporary game servers or clients.

- Server root: `E:\Game\` followed by the folder name with Unicode code points U+670D U+52A1 U+5668 U+7AEF. Use its existing loader/Minecraft-version subdirectories, such as `fabric 1.21.1 server` and `neoforge 26.1 server`.
- Client root: `E:\Game\pcl\.minecraft\versions`.
- Start servers, replace test mods and inspect server logs only inside the appropriate server directory. Read `<server>/logs/latest.log`.
- Read client logs from the selected instance's `logs/latest.log`: `versions/<version>/logs/latest.log` with PCL version isolation, or `.minecraft/logs/latest.log` otherwise.
- These two external test roots are authorized by the repository convention. Only mods, configurations and logs needed for the test may be written there. Temporary scripts, downloads and fixtures still belong in `cache/tmp/`.

## Temporary files and test artifacts

All temporary test files, scripts, logs, decompiler output, downloads and fixtures must remain under this repository's `cache/` directory.

- Use `cache/tmp/<task>/` for task-specific temporary files.
- Set `TMPDIR`, `TEMP`, `TMP` and `java.io.tmpdir` there when required.
- Use `--project-cache-dir cache/gradle-project-cache` for temporary Gradle project caches. Reuse the existing Gradle user cache to avoid unnecessary downloads.
- Do not write temporary files in the system temp directory, home directory, Desktop, Documents, Downloads or other external paths.
- Explain why and obtain user approval before an exception outside the repository.
- Cleanup may delete only the relevant task directory inside `cache/`, never `cache/` itself.

Normal `build/`, `.gradle/` and `output/` build artifacts keep their existing conventions and are not temporary test files.

## Builds

Prefer the focused scripts under `scripts/fastbuild/`. On Windows, invoke `.bat` files through cmd, for example `cmd /c scripts\fastbuild\build-target.bat fabric-1.21.1`. On Linux/WSL, invoke the matching `.sh` scripts. Do not add PowerShell build scripts or bypass the focused scripts with a full `gradlew build`.

- If a target lacks a script, add matching cmd-style `.bat` and bash `.sh` entrypoints using the existing settings, JDK and artifact collection logic.
- Fix build failures in the relevant script or its Gradle/settings configuration, then rerun that entrypoint. Do not bypass a failing script for delivery.
- Deliver a self-contained single JAR: shade or inline internal dependencies. Do not distribute slim JARs, separate dependency bundles or multiple required Atlas files.
- Collect artifacts into `output/` with `copy-release-jars.bat` and inspect the final distribution.
