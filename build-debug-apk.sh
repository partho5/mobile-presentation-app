#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
./gradlew assembleDebug
echo "APK directory: $(pwd)/app/build/outputs/apk/debug"
