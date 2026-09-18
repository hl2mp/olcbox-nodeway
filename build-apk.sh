#!/usr/bin/env bash
# Build olcbox-plus APK with custom output name.
#
# Usage:
#   ./build-apk.sh                    # all ABIs → olcbox-plus-<version>-all.apk
#   ./build-apk.sh arm64-v8a          # single ABI → olcbox-plus-<version>-arm64-v8a.apk
#   ./build-apk.sh arm64-v8a,x86_64   # multiple ABIs → olcbox-plus-<version>-arm64-v8a+x86_64.apk
#
# Version is read from gradle.properties (olcbox.version).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ABI="${1:-}"
RELEASE_DIR="androidApp/build/outputs/apk/release"

# Read version from gradle.properties
VERSION=$(grep -E "^olcbox\.version=" gradle.properties | cut -d= -f2 | tr -d '[:space:]')
if [ -z "$VERSION" ]; then
    VERSION="1.0.0"
fi

# Build APK
if [ -n "$ABI" ]; then
    echo "=== Building APK for ABI(s): $ABI ==="
    ./gradlew :androidApp:assembleRelease -Pabi="$ABI" --no-daemon
else
    echo "=== Building APK for all ABIs ==="
    ./gradlew :androidApp:assembleRelease --no-daemon
fi

# Find and rename APK
# AGP 9.x may produce multiple APKs (per-ABI) or a single APK
APK_FILES=("$RELEASE_DIR"/*.apk)

if [ ${#APK_FILES[@]} -eq 0 ]; then
    echo "ERROR: No APK found in $RELEASE_DIR"
    exit 1
fi

# Determine output name
if [ -n "$ABI" ] && [ "$(echo "$ABI" | tr ',' '\n' | wc -l)" -eq 1 ]; then
    # Single ABI
    ABI_LABEL="$ABI"
else
    # Multiple or all ABIs
    ABI_LABEL="all"
fi

OUTPUT_NAME="olcbox-plus-${VERSION}-${ABI_LABEL}.apk"

# If multiple APKs, combine them or rename the single APK
if [ ${#APK_FILES[@]} -eq 1 ]; then
    mv "${APK_FILES[0]}" "$SCRIPT_DIR/$OUTPUT_NAME"
    echo "=== APK built: $OUTPUT_NAME ==="
    ls -lh "$SCRIPT_DIR/$OUTPUT_NAME"
else
    # Multiple APKs (per-ABI split) — rename each with ABI suffix
    for APK in "${APK_FILES[@]}"; do
        BASENAME=$(basename "$APK" .apk)
        # AGP 9.x names them like androidApp-release.apk or androidApp-release-<abi>.apk
        # Extract ABI from filename if present
        if [[ "$BASENAME" == *-arm64-v8a ]]; then
            ABI_SUFFIX="arm64-v8a"
        elif [[ "$BASENAME" == *-armeabi-v7a ]]; then
            ABI_SUFFIX="armeabi-v7a"
        elif [[ "$BASENAME" == *-x86_64 ]]; then
            ABI_SUFFIX="x86_64"
        else
            ABI_SUFFIX="all"
        fi
        NEW_NAME="olcbox-plus-${VERSION}-${ABI_SUFFIX}.apk"
        mv "$APK" "$SCRIPT_DIR/$NEW_NAME"
        echo "  → $NEW_NAME"
    done
    echo "=== All APKs built in $SCRIPT_DIR ==="
    ls -lh "$SCRIPT_DIR"/olcbox-plus-*.apk
fi