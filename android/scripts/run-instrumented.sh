#!/usr/bin/env bash
# Runs the instrumented tests on an already booted emulator (used by CI): the debug build first, then the
# R8-minified qa build. Logcat is captured to app/build/logcat.txt, and the qa run has a time limit so a
# hang fails with diagnostics instead of running into the job timeout.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/build
adb logcat -c || true
adb logcat -v threadtime > app/build/logcat.txt 2>&1 &
logcat_pid=$!
trap 'kill "$logcat_pid" 2>/dev/null || true' EXIT

./gradlew connectedDebugAndroidTest --stacktrace
timeout --signal=INT 20m ./gradlew connectedQaAndroidTest -PtestBuildType=qa --stacktrace
