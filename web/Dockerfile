# =============================================================================
# Dockerfile — cimbar encoder WASM builder
#
# Build the image:
#   docker build -t cimbar-wasm-builder -f web/Dockerfile .
#
# Run the build (outputs cimbar_js.js into web/):
#   docker run --rm -v "$(pwd):/usr/src/app" cimbar-wasm-builder
#
# Cache OpenCV WASM build across runs with a named volume:
#   docker run --rm \
#     -v "$(pwd):/usr/src/app" \
#     -v cimbar-opencv-cache:/usr/src/app/opencv4 \
#     cimbar-wasm-builder
#
# Override parallel job count (default: all cores):
#   docker run --rm -v "$(pwd):/usr/src/app" -e JOBS=4 cimbar-wasm-builder
#
# Build wasm+js two-file variant instead of asm.js single-file:
#   docker run --rm -v "$(pwd):/usr/src/app" -e USE_WASM=1 cimbar-wasm-builder
# =============================================================================

FROM emscripten/emsdk:3.1.69

WORKDIR /usr/src/app

# Install build dependencies in a single layer (cached in image, not at runtime)
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        python3 \
        git \
        ccache && \
    rm -rf /var/lib/apt/lists/*

# Point compiler launchers at ccache for faster rebuilds
ENV CCACHE_DIR=/ccache
ENV CMAKE_C_COMPILER_LAUNCHER=ccache
ENV CMAKE_CXX_COMPILER_LAUNCHER=ccache

# Default command: run the build script
CMD ["bash", "/usr/src/app/web/build-wasm-encode.sh"]
