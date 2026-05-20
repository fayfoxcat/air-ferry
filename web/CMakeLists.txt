# =============================================================================
# web/CMakeLists.txt  —  encoder-only WASM build entry point
#
# Usage (from project root, inside emscripten/emsdk Docker container):
#
#   mkdir build-wasm-encode && cd build-wasm-encode
#   emcmake cmake ../web \
#       -DUSE_WASM=2 \
#       -DOPENCV_DIR=/usr/src/app/opencv4 \
#       -DCMAKE_BUILD_TYPE=Release
#   make -j$(nproc) cimbar_js
#   make install
#
# USE_WASM=2  → asm.js single-file (cimbar_js.js only, no .wasm sidecar)
# USE_WASM=1  → wasm+js two-file   (cimbar_js.js + cimbar_js.wasm)
# =============================================================================

cmake_minimum_required(VERSION 3.10)
project(cimbar_web_encode)

set(CMAKE_CXX_STANDARD 17)

# ---- Locate libcimbar source -------------------------------------------------
get_filename_component(_WEB_DIR   "${CMAKE_CURRENT_SOURCE_DIR}" ABSOLUTE)
get_filename_component(_PROJ_ROOT "${_WEB_DIR}/.."              ABSOLUTE)
set(LIBCIMBAR_DIR "${_PROJ_ROOT}/app/src/cpp/libcimbar")

if(NOT EXISTS "${LIBCIMBAR_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "libcimbar not found at ${LIBCIMBAR_DIR}.\n"
        "Make sure the project structure is intact.")
endif()

# ---- OpenCV -----------------------------------------------------------------
if(NOT DEFINED OPENCV_DIR)
    message(FATAL_ERROR
        "OPENCV_DIR is not set. Pass -DOPENCV_DIR=/path/to/opencv4 to cmake.")
endif()

# The WASM OpenCV build produces static libs and headers under:
#   <OPENCV_DIR>/opencv-build-wasm/build_wasm/
# We set up include dirs and link dirs manually — more reliable than
# find_package() under the Emscripten cross-compilation toolchain.
set(_OPENCV_BUILD_DIR "${OPENCV_DIR}/opencv-build-wasm/build_wasm")

if(NOT EXISTS "${_OPENCV_BUILD_DIR}")
    message(FATAL_ERROR
        "OpenCV WASM build not found at ${_OPENCV_BUILD_DIR}.\n"
        "Run build-wasm-encode.sh first, or build OpenCV manually:\n"
        "  cd ${OPENCV_DIR} && mkdir opencv-build-wasm && cd opencv-build-wasm\n"
        "  python3 ../platforms/js/build_js.py build_wasm --build_wasm --emscripten_dir=/emsdk/upstream/emscripten")
endif()

# Include dirs: source tree headers + build-generated headers (cvconfig.h etc.)
include_directories("${OPENCV_DIR}/include")
include_directories("${_OPENCV_BUILD_DIR}")

# Per-module include dirs (opencv2/<module>/<module>.hpp lives here)
file(GLOB _OPENCV_MODULE_INCLUDES "${OPENCV_DIR}/modules/*/include")
foreach(_inc ${_OPENCV_MODULE_INCLUDES})
    include_directories("${_inc}")
endforeach()

# Link dirs and library list
# Note: opencv_imgcodecs is NOT built in the WASM target (browsers handle
# image decoding natively). The encoder only needs imgproc, photo, core.
link_directories("${_OPENCV_BUILD_DIR}/lib")
set(OPENCV_LIBS opencv_imgproc opencv_photo opencv_core)

# ---- Disable tests ----------------------------------------------------------
set(DISABLE_TESTS true)

# ---- wirehair: disable SIMD for WASM ----------------------------------------
# gf256.h includes SSE/SSSE3 headers unless GF256_TARGET_MOBILE is defined.
# WASM does not support x86 SIMD intrinsics (without -msimd128 + WASM SIMD),
# so we force the scalar/NEON-free path.
add_compile_definitions(GF256_TARGET_MOBILE)

# ---- Include paths ----------------------------------------------------------
include_directories(
    "${LIBCIMBAR_DIR}/src/lib"
    "${LIBCIMBAR_DIR}/src/third_party_lib"
)

# ---- libcimbar_SOURCE_DIR (needed by cimbar_js install rule) ----------------
# Must be set before add_subdirectory(cimbar_js).
set(libcimbar_SOURCE_DIR "${LIBCIMBAR_DIR}" CACHE PATH "" FORCE)

# ---- libcimbar library modules ----------------------------------------------
set(LIBCIMBAR_PROJECTS
    src/lib/bit_file
    src/lib/chromatic_adaptation
    src/lib/cimb_translator
    src/lib/compression
    src/lib/encoder
    src/lib/extractor
    src/lib/fountain
    src/lib/gui
    src/lib/image_hash
    src/lib/serialize
    src/lib/util

    src/third_party_lib/base91
    src/third_party_lib/cxxopts
    src/third_party_lib/intx
    src/third_party_lib/libcorrect
    src/third_party_lib/libpopcnt
    src/third_party_lib/wirehair
    src/third_party_lib/zstd
)

foreach(proj ${LIBCIMBAR_PROJECTS})
    add_subdirectory(
        "${LIBCIMBAR_DIR}/${proj}"
        "${CMAKE_CURRENT_BINARY_DIR}/libcimbar/${proj}"
    )
endforeach()

# ---- cimbar_js (encoder target) ---------------------------------------------
add_subdirectory(
    "${LIBCIMBAR_DIR}/src/lib/cimbar_js"
    "${CMAKE_CURRENT_BINARY_DIR}/libcimbar/src/lib/cimbar_js"
)

# ---- Patch exported functions to include cimbare_get_frame_buff -------------
# The upstream CMakeLists does not export this function; we need it to read
# raw pixel data from the generated cv::Mat frame in JavaScript.
if(DEFINED USE_WASM)
    set(_EXTRA_EXPORTS
        "_malloc"
        , "_free"
        , "_cimbare_render"
        , "_cimbare_next_frame"
        , "_cimbare_init_encode"
        , "_cimbare_encode"
        , "_cimbare_encode_bufsize"
        , "_cimbare_configure"
        , "_cimbare_rotate_window"
        , "_cimbare_get_aspect_ratio"
        , "_cimbare_get_frame_buff"
        , "_cimbare_blocks_required"
    )

    if(USE_WASM EQUAL "2")
        set(_WASM_OPT "-s SINGLE_FILE=1")
    else()
        set(_WASM_OPT "-s WASM=1")
    endif()

    set(_LINK_FLAGS_LIST
        --bind -Os
        ${_WASM_OPT}
        -s USE_WEBGL2=1
        -s MIN_WEBGL_VERSION=2
        -s MAX_WEBGL_VERSION=2
        -s USE_GLFW=3
        -s WASM_BIGINT=1
        -s FILESYSTEM=0
        -s TOTAL_MEMORY=134217728
        "-s EXPORTED_FUNCTIONS='[${_EXTRA_EXPORTS}]'"
    )
    string(REPLACE ";" " " _LINK_FLAGS "${_LINK_FLAGS_LIST}")
    set_target_properties(cimbar_js PROPERTIES LINK_FLAGS "${_LINK_FLAGS}")
endif()

# ---- Install ----------------------------------------------------------------
install(
    TARGETS cimbar_js
    DESTINATION "${_WEB_DIR}"
)

if(DEFINED USE_WASM AND NOT USE_WASM EQUAL 2)
    install(
        FILES "$<TARGET_FILE_DIR:cimbar_js>/cimbar_js.wasm"
        DESTINATION "${_WEB_DIR}"
    )
endif()
