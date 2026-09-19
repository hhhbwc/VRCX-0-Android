#!/usr/bin/env bash
# Build the Android client.
#
# Why this script exists: the build needs JAVA_HOME and ANDROID_HOME set to the
# right absolute paths, and Maven Central answers 403 on this machine -- the
# repositories are therefore mirrored in settings.gradle.kts. Neither is
# something to rediscover by trial and error.
#
#   ./gradlew lives in android/, so this can also be run directly from there.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/android"

export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Eclipse Adoptium/jdk-17.0.20.8-hotspot}"
export ANDROID_HOME="${ANDROID_HOME:-C:/Android/Sdk}"

TASK="${1:-assembleDebug}"

echo "==> JAVA_HOME=$JAVA_HOME"
echo "==> ANDROID_HOME=$ANDROID_HOME"
echo "==> gradle $TASK"

./gradlew --no-daemon "$TASK"
status=$?

if [ $status -eq 0 ]; then
    APK="$(ls -1 app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)"
    [ -n "$APK" ] && echo "==> APK: $ROOT/android/$APK"
else
    echo "==> 构建失败，查看输出中的 ^e: 行"
fi
exit $status
