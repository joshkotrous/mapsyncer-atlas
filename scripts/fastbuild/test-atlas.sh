#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
version=$(sed -n 's/^mod_version=//p' gradle.properties | tr -d '\r')
export MAPSYNCER_REGRESSION_JAR="$PWD/output/mapsyncer-atlas-${version}-fabric-26.3.jar"
python3 scripts/testing/check-english-release.py --jar "$MAPSYNCER_REGRESSION_JAR"
python3 scripts/testing/run-regressions.py
