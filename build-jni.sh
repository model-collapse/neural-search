#!/usr/bin/env bash
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# Builds libnsparse_jni.so (jni/nsparse_jni.cpp) against the neural-sparse-cpp
# SEISMIC library and copies it into the plugin resources.
#
# The neural-sparse-cpp checkout defaults to the sibling directory
# ../neural-sparse-cpp; override with NSPARSE_CPP_DIR=<path>.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNI_DIR="$SCRIPT_DIR/jni"
NSPARSE_CPP_DIR="${NSPARSE_CPP_DIR:-$(cd "$SCRIPT_DIR/../neural-sparse-cpp" 2>/dev/null && pwd || true)}"
BUILD_DIR="$SCRIPT_DIR/build/jni"

if [ -z "$NSPARSE_CPP_DIR" ] || [ ! -f "$NSPARSE_CPP_DIR/CMakeLists.txt" ]; then
    echo "ERROR: NSPARSE_CPP_DIR ('${NSPARSE_CPP_DIR:-unset}') is not a neural-sparse-cpp checkout." >&2
    echo "Set NSPARSE_CPP_DIR to your neural-sparse-cpp path, or place it at ../neural-sparse-cpp." >&2
    exit 1
fi

# Detect platform
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$ARCH" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="aarch64" ;;
esac
PLATFORM="${OS}-${ARCH}"

echo "=== Building nsparse JNI for ${PLATFORM} (nsparse-cpp: ${NSPARSE_CPP_DIR}) ==="

# Ensure JAVA_HOME is set
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"
    echo "Auto-detected JAVA_HOME=$JAVA_HOME"
fi

mkdir -p "$BUILD_DIR"

cmake -S "$JNI_DIR" -B "$BUILD_DIR" \
    -DNSPARSE_CPP_DIR="$NSPARSE_CPP_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    "${@}"

cmake --build "$BUILD_DIR" --target nsparse_jni -j"$(nproc)"

# Copy to plugin resources
DEST="$SCRIPT_DIR/src/main/resources/native/${PLATFORM}"
mkdir -p "$DEST"
cp -v "$BUILD_DIR/libnsparse_jni.so" "$DEST/"

echo "=== Done: ${DEST}/libnsparse_jni.so ==="
