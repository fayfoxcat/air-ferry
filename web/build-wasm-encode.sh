#!/bin/bash
# =============================================================================
# Build script: cimbar encoder-only WASM
#
# USE_WASM=2  → asm.js single-file (cimbar_js.js, no .wasm sidecar) [default]
# USE_WASM=1  → wasm+js two-file   (cimbar_js.js + cimbar_js.wasm)
#
# Run inside the emscripten Docker container:
#
#   docker run --rm \
#     --mount type=bind,source="$(pwd)",target="/usr/src/app" \
#     emscripten/emsdk:3.1.69 \
#     bash /usr/src/app/web/build-wasm-encode.sh
#
# The script expects the project root to be mounted at /usr/src/app.
# Output files are written to /usr/src/app/web/.
# =============================================================================

set -euo pipefail

CIMBAR_ROOT=${CIMBAR_ROOT:-/usr/src/app}
OPENCV_DIR="$CIMBAR_ROOT/opencv4"
WEB_DIR="$CIMBAR_ROOT/web"
USE_WASM=${USE_WASM:-2}

# Number of parallel jobs — default to all logical cores
JOBS=${JOBS:-$(nproc)}
echo ">>> Using $JOBS parallel jobs"

# ---- 1. Build OpenCV.js (wasm) if not already done -------------------------
OPENCV_JS="$OPENCV_DIR/opencv-build-wasm/build_wasm/bin/opencv.js"
if [ ! -f "$OPENCV_JS" ]; then
    echo ">>> Building OpenCV WASM (this takes a while)..."
    cd "$OPENCV_DIR"
    mkdir -p opencv-build-wasm
    cd opencv-build-wasm
    # Pass parallel job count via cmake --parallel; build_js.py forwards
    # --cmake_option flags directly to the cmake --build invocation.
    python3 ../platforms/js/build_js.py build_wasm \
        --build_wasm \
        --emscripten_dir=/emsdk/upstream/emscripten \
        --cmake_option="-DCMAKE_BUILD_PARALLEL_LEVEL=$JOBS"
else
    echo ">>> OpenCV WASM already built, skipping."
fi

# ---- 2. Activate Emscripten environment ------------------------------------
source /emsdk/emsdk_env.sh

# ---- 3. Build encoder WASM via web/CMakeLists.txt --------------------------
echo ">>> Building cimbar encoder WASM (USE_WASM=$USE_WASM)..."
cd "$CIMBAR_ROOT"
rm -rf build-wasm-encode
mkdir build-wasm-encode
cd build-wasm-encode

emcmake cmake "$WEB_DIR" \
    -DUSE_WASM="$USE_WASM" \
    -DOPENCV_DIR="$OPENCV_DIR" \
    -DDISABLE_TESTS=true \
    -DCMAKE_BUILD_TYPE=Release

make -j"$JOBS" cimbar_js
make install

# Also copy directly in case the install destination was the submodule's web/
JS_BUILT="$(find "$CIMBAR_ROOT/build-wasm-encode" -name 'cimbar_js.js' | head -1)"
if [ -n "$JS_BUILT" ] && [ ! -f "$WEB_DIR/cimbar_js.js" ]; then
    echo ">>> Copying cimbar_js.js to $WEB_DIR ..."
    cp -v "$JS_BUILT" "$WEB_DIR/"
fi

# ---- 4. Verify output -------------------------------------------------------
echo ""
echo ">>> Build complete!"
ls -lh "$WEB_DIR/cimbar_js.js" 2>/dev/null || echo "WARNING: cimbar_js.js not found in $WEB_DIR"
[ "$USE_WASM" != "2" ] && ls -lh "$WEB_DIR/cimbar_js.wasm" 2>/dev/null || true
echo ""
echo "Serve the web/ directory with any HTTP server, e.g.:"
echo "  cd $WEB_DIR && python3 -m http.server 8080"
