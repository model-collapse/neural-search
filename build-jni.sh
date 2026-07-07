#!/usr/bin/env bash
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# Builds libnsparse_jni.so from neural-sparse-cpp and copies it into the plugin resources.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CPP_DIR="$(cd "$SCRIPT_DIR/../neural-sparse-cpp" && pwd)"
BUILD_DIR="$CPP_DIR/build-jni"

# Detect platform
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="aarch64" ;;
esac
PLATFORM="${OS}-${ARCH}"

echo "=== Building nsparse JNI for ${PLATFORM} ==="

# Ensure JAVA_HOME is set
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
    echo "Auto-detected JAVA_HOME=$JAVA_HOME"
fi

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

cmake "$CPP_DIR" \
    -DNSPARSE_BUILD_JNI=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    "${@}"

cmake --build . --target nsparse_jni -j"$(nproc)"

# Copy to plugin resources
DEST="$SCRIPT_DIR/src/main/resources/native/${PLATFORM}"
mkdir -p "$DEST"
cp -v "$BUILD_DIR/jni/libnsparse_jni.so" "$DEST/"

echo "=== Done: ${DEST}/libnsparse_jni.so ==="
