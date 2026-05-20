cmake_minimum_required(VERSION 3.4.1)

project ( cfc )

set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "${CMAKE_CXX_FLAGS_RELWITHDEBINFO} -mllvm -inline-threshold=1500 -flto=thin")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -mllvm -inline-threshold=1500 -flto=thin")

# 16 KB 页对齐（Android 15+ 及 Google Play 2025 年 11 月起要求）
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# opencv 相关配置
add_library( lib_opencv SHARED IMPORTED )
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OpenCV_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so)

set( DISABLE_TESTS true )
set( OPENCV_LIBS "lib_opencv" )

# 项目子模块
set (PROJECTS
   cfc-cpp
   concurrent
)

# 上游 libcimbar 库子目录（来自 git submodule）
set (LIBCIMBAR_LIBS
   libcimbar/src/lib/bit_file
   libcimbar/src/lib/chromatic_adaptation
   libcimbar/src/lib/cimb_translator
   libcimbar/src/lib/compression
   libcimbar/src/lib/encoder
   libcimbar/src/lib/extractor
   libcimbar/src/lib/fountain
   libcimbar/src/lib/image_hash
   libcimbar/src/lib/serialize
   libcimbar/src/lib/util
)

set (LIBCIMBAR_THIRD_PARTY
   libcimbar/src/third_party_lib/base91
   libcimbar/src/third_party_lib/cxxopts
   libcimbar/src/third_party_lib/intx
   libcimbar/src/third_party_lib/libcorrect
   libcimbar/src/third_party_lib/libpopcnt
   libcimbar/src/third_party_lib/wirehair
   libcimbar/src/third_party_lib/zstd
)

include_directories(
        ${OpenCV_DIR}/jni/include
        .
        libcimbar/src/lib
        libcimbar/src/third_party_lib
)

foreach(proj ${PROJECTS})
	add_subdirectory(${proj} build/${proj})
endforeach()

foreach(proj ${LIBCIMBAR_LIBS} ${LIBCIMBAR_THIRD_PARTY})
	add_subdirectory(${proj} build/${proj})
endforeach()
