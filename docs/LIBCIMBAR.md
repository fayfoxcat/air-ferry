# libcimbar 集成说明

本项目依赖上游 [sz3/libcimbar](https://github.com/sz3/libcimbar) 库，用于 **解码**（Android 端）和 **编码**（WASM 端）cimbar 彩色矩阵条码。

## 目录结构

```
<project-root>/
├── libcimbar/                  # ← 上游源码（由 setup.sh 下载）
│   ├── src/
│   │   ├── lib/                #    核心库（encoder, extractor, cimb_translator, ...）
│   │   └── third_party_lib/    #    第三方依赖（zstd, wirehair, libcorrect, ...）
│   └── CMakeLists.txt
├── app/                        # Android 接收端
│   └── src/cpp/
│       ├── CMakeLists.txt      #    通过 add_subdirectory 引用 libcimbar 各模块
│       └── cfc-cpp/
│           ├── jni.cpp         #    JNI 桥接层，调用 libcimbar API
│           └── CMakeLists.txt  #    link cimb_translator, extractor 等
└── web/                        # WASM 编码端
    ├── CMakeLists.txt          #    引用 libcimbar 各模块 + cimbar_js target
    ├── main.js                 #    JavaScript 前端，调用 WASM 导出的 C 函数
    └── build-wasm-encode.sh   #    emscripten 构建脚本
```

## 获取 libcimbar

```bash
# 方式一：setup.sh 一键下载（推荐）
./setup.sh --libcimbar

# 方式二：手动克隆
git clone --depth 1 --branch master \
  https://github.com/sz3/libcimbar.git libcimbar
```

`libcimbar/` 目录不纳入版本控制（已加入 `.gitignore`）。

---

## Android 端（app）使用方式

### 构建集成

`app/src/cpp/CMakeLists.txt` 通过 `add_subdirectory` 将 libcimbar 的各个子模块作为独立 CMake target 引入：

```cmake
set(LIBCIMBAR_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../libcimbar")

set(LIBCIMBAR_LIBS
   libcimbar/src/lib/bit_file
   libcimbar/src/lib/cimb_translator
   libcimbar/src/lib/encoder
   libcimbar/src/lib/extractor
   libcimbar/src/lib/fountain
   ...
)

foreach(proj ${LIBCIMBAR_LIBS} ${LIBCIMBAR_THIRD_PARTY})
    add_subdirectory("${LIBCIMBAR_ROOT}/${proj}" build/${proj})
endforeach()
```

### JNI 层调用

`app/src/cpp/cfc-cpp/jni.cpp` 是 JNI 桥接层，从 Java 层接收相机帧，调用 libcimbar 进行解码：

| JNI 函数 | 作用 |
|---|---|
| `Java_cc_asac_cimbar_ReceiverFragment_processImageJNI` | 传入相机帧 `cv::Mat`，调用 `MultiThreadedDecoder` 进行解码，返回结果字符串 |
| `Java_cc_asac_cimbar_ReceiverFragment_shutdownJNI` | 停止解码器，释放资源 |

解码器使用的 libcimbar 头文件：

```cpp
#include "cimb_translator/CimbDecoder.h"   // Cimbar 码解码器
#include "cimb_translator/CimbReader.h"    // Cimbar 码读取器
#include "encoder/Decoder.h"               // Fountain 码纠删解码
#include "extractor/Scanner.h"             // 图像扫描/定位
#include "serialize/format.h"              // 数据序列化
```

### 链接依赖

`app/src/cpp/cfc-cpp/CMakeLists.txt` 声明了运行时链接的 libcimbar 模块：

```cmake
target_link_libraries(cfc-cpp
    cimb_translator    # 条码翻译核心
    extractor          # 图像特征提取
    correct_static     # Reed-Solomon 纠错
    wirehair           # Fountain 码
    zstd               # Zstandard 压缩
    ${OPENCV_LIBS}
    ${log-lib}
)
```

---

## WASM 端（web）使用方式

### 构建集成

`web/CMakeLists.txt` 引用项目根目录的 `libcimbar/`，并构建 `cimbar_js` target（来自 libcimbar 的 `src/lib/cimbar_js`）：

```cmake
set(LIBCIMBAR_DIR "${_PROJ_ROOT}/libcimbar")

# 引入 libcimbar 全部子模块
foreach(proj ${LIBCIMBAR_PROJECTS})
    add_subdirectory("${LIBCIMBAR_DIR}/${proj}" ...)
endforeach()

# 构建 WASM target
add_subdirectory("${LIBCIMBAR_DIR}/src/lib/cimbar_js" ...)
```

### 导出的 C API（编码端）

WASM 构建导出以下函数供 JavaScript 调用：

| C 函数 | JS 调用 | 说明 |
|---|---|---|
| `cimbare_configure(mode, compression)` | `Module._cimbare_configure(m, c)` | 配置编码模式和压缩级别 |
| `cimbare_init_encode(filename, len, id)` | `Module._cimbare_init_encode(ptr, len, -1)` | 初始化编码会话 |
| `cimbare_encode_bufsize()` | `Module._cimbare_encode_bufsize()` | 查询每次 encode 调用的缓冲区大小 |
| `cimbare_encode(buf, size)` | `Module._cimbare_encode(ptr, size)` | 喂入文件数据（分块调用） |
| `cimbare_next_frame(colorBalance)` | `Module._cimbare_next_frame(false)` | 生成下一帧，返回帧序号 |
| `cimbare_get_frame_buff(ptrToPtr)` | `Module._cimbare_get_frame_buff(ptrSlot)` | 获取当前帧 RGB 像素缓冲区 |
| `cimbare_get_aspect_ratio()` | `Module._cimbare_get_aspect_ratio()` | 获取当前配置的宽高比 |
| `cimbare_blocks_required()` | `Module._cimbare_blocks_required()` | 查询解码端所需的最少块数 |

### JavaScript 编码流程

`web/main.js` 中的完整编码流程：

```
1. cimbare_configure(mode, compression)     → 配置参数
2. 读取文件为 ArrayBuffer
3. cimbare_init_encode(filename, len, -1)   → 初始化
4. cimbare_encode_bufsize()                 → 查询块大小
5. 循环调用 cimbare_encode(chunk, size)     → 分块喂入数据（最后一块 size=0 触发完成）
6. 定时器循环：
   a. cimbare_next_frame(false)             → 生成下一帧
   b. cimbare_get_frame_buff(ptrSlot)       → 读取像素数据
   c. 将 RGB 转为 RGBA，绘制到 Canvas
```

### 编码模式

| 模式值 | 名称 | 分辨率 | 数据速率 |
|---|---|---|---|
| 68 | B（标准） | 1024×1024 | ~106 KB/s |
| 67 | BM（迷你） | 1024×720 | ~74 KB/s |
| 66 | BU（微型） | 736×637 | ~52 KB/s |

---

## 依赖版本

| 组件 | 版本 | 来源 |
|---|---|---|
| libcimbar | master 分支 | `https://github.com/sz3/libcimbar.git` |
| OpenCV（Android） | 4.13.0 SDK | `opencv-4.13.0-android-sdk.zip` |
| OpenCV（WASM） | 4.13.0 源码 | `https://github.com/opencv/opencv.git` |

## 许可证

libcimbar 采用 **MPL 2.0** 许可证。其内部第三方依赖（zstd, wirehair, libcorrect 等）各自采用 MIT、BSD、Boost、Apache 等许可证。
