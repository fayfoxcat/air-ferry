#!/bin/bash
#
# Build script for AirFerry client
#
# Prerequisites:
#   - JDK 17+
#   - Android SDK (set ANDROID_HOME or ANDROID_SDK_ROOT)
#   - Android NDK 30.0.14904198
#   - OpenCV Android SDK 4.5.0 (set opencvsdk in local.properties or OPENCV_SDK env)
#
# Usage:
#   ./build.sh debug      - Build debug APK
#   ./build.sh release    - Build release APK (may need signing config)
#   ./build.sh clean      - Clean build artifacts

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check for OpenCV SDK
if [ -z "${OPENCV_SDK:-}" ]; then
    if [ -f "local.properties" ]; then
        OPENCV_SDK=$(grep -oP 'opencvsdk=\K.*' local.properties 2>/dev/null || true)
    fi
fi

if [ -z "${OPENCV_SDK:-}" ] || [ ! -d "${OPENCV_SDK}/sdk" ]; then
    echo "ERROR: OpenCV SDK not found."
    echo "Set opencvsdk in local.properties or OPENCV_SDK environment variable."
    echo "Download: https://github.com/opencv/opencv/releases/download/4.5.0/opencv-4.5.0-android-sdk.zip"
    exit 1
fi

export OPENCV_SDK

case "${1:-debug}" in
    debug)
        echo ">>> Building debug APK..."
        ./gradlew assembleDebug
        echo ">>> Debug APK: app/build/outputs/apk/debug/"
        ;;
    release)
        echo ">>> Building release APK..."
        ./gradlew assembleRelease
        echo ">>> Release APK: app/build/outputs/apk/release/"
        ;;
    clean)
        echo ">>> Cleaning..."
        ./gradlew clean
        ;;
    *)
        echo "Usage: $0 {debug|release|clean}"
        exit 1
        ;;
esac
