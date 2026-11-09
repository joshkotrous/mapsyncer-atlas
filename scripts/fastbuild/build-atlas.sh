#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p cache/tmp/atlas
export TMPDIR="$PWD/cache/tmp/atlas" TEMP="$PWD/cache/tmp/atlas" TMP="$PWD/cache/tmp/atlas"
python3 scripts/testing/check-english-release.py
if [[ -f settings.atlas-backup.gradle ]]; then
    echo 'Restore settings.atlas-backup.gradle before rebuilding after an interrupted build.' >&2
    exit 3
fi
cp settings.gradle settings.atlas-backup.gradle
trap 'mv -f settings.atlas-backup.gradle settings.gradle' EXIT
cp scripts/fastbuild/settings-atlas.gradle settings.gradle
offline=()
if [[ "${GRADLE_OFFLINE:-}" == '--offline' ]]; then offline+=(--offline); fi
bash gradlew :mc-26.3:fabric:build -PatlasBuild --project-cache-dir cache/gradle-project-cache "${offline[@]}"
version=$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')
artifact="mapsyncer-atlas-${version}-fabric-26.3.jar"
mkdir -p output
cp "mc-26.3/fabric/build/libs/$artifact" "output/$artifact"
python3 scripts/testing/check-english-release.py --jar "output/$artifact"
