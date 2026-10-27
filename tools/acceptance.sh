#!/usr/bin/env bash
# Bridget device acceptance test (spec TST-03).
#
# Runs every check against the DEBUG build on the Pixel over Tailscale and ADB,
# then writes docs/acceptance/<date>-<version>-debug.md with PASS / FAIL /
# UNVERIFIED per check. Captures (camera, mic) need the phone unlocked; when it
# is locked they are marked UNVERIFIED, never passed.
#
#   tools/acceptance.sh                       # Pixel defaults, debug build on port 8735
#   tools/acceptance.sh --skip-captures
#   tools/acceptance.sh --device <serial> --host <tailscale-ip> --port 8735
#
# Needs: adb, python3, the debug build installed (./gradlew installDebug or adb install -r -g).
set -euo pipefail
cd "$(dirname "$0")/.."
exec python3 -I tools/acceptance.py "$@"
