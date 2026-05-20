# web/ — WASM 编码端

基于 libcimbar 的 `cimbar_js` 构建，将文件编码为 cimbar 彩色矩阵条码动画，在浏览器中运行，无需安装任何软件。

输出文件通过 `cimbare_get_frame_buff()` 直接读取原始 RGB 像素渲染到 Canvas，不依赖 GLFW/WebGL 窗口。

## 文件说明

```
web/
├── index.html              # 前端页面
├── main.js                 # 前端逻辑（调用 WASM C API）
├── CMakeLists.txt          # WASM 构建配置（引用 libcimbar）
├── build-wasm-encode.sh    # 构建脚本（在 Docker 容器内运行）
├── Dockerfile              # 构建镜像
└── cimbar_js.js            # ← 构建产物（构建后生成）
```

## 构建 WASM

### 前置条件

- Docker
- 项目根目录 `libcimbar/` + `opencv4/`（运行 `bash setup.sh`）

### Docker 构建

```bash
# 在项目根目录执行
bash setup.sh                          # 下载依赖
docker run --rm \
  --mount type=bind,source="$(pwd)",target="/usr/src/app" \
  emscripten/emsdk:3.1.69 \
  bash /usr/src/app/web/build-wasm-encode.sh
```

构建完成后 `web/cimbar_js.js` 自动生成。

### 使用自定义镜像

```bash
docker build -t cimbar-wasm-builder -f web/Dockerfile .
docker run --rm \
  -v "$(pwd):/usr/src/app" \
  -v cimbar-opencv-cache:/usr/src/app/opencv4 \
  cimbar-wasm-builder
```

### 本地 Emscripten

```bash
source /emsdk/emsdk_env.sh
mkdir build-wasm-encode && cd build-wasm-encode
emcmake cmake ../web -DUSE_WASM=2 -DOPENCV_DIR=/path/to/opencv4 -DCMAKE_INSTALL_PREFIX=../web
make -j$(nproc) cimbar_js
make install
```

## 运行

```bash
cd web && python3 -m http.server 8080
```

打开 `http://localhost:8080`。（WASM 的 CORS 限制，不能直接打开 `file://`）

## 编码模式

| 模式 | 分辨率 | 数据速率 |
|---|---|---|
| 68（B，标准） | 1024×1024 | ~106 KB/s |
| 67（BM，迷你） | 1024×720 | ~74 KB/s |
| 66（BU，微型） | 736×637 | ~52 KB/s |

## C API（WASM 导出）

```c
int  cimbare_configure(int mode_val, int compression);           // 设置编码模式
int  cimbare_init_encode(const char* filename, unsigned fnSize, int encode_id);  // 初始化
int  cimbare_encode_bufsize();                                    // 查询块大小
int  cimbare_encode(const unsigned char* buffer, unsigned size);  // 喂入文件数据
int  cimbare_next_frame(bool color_balance);                      // 生成下一帧
int  cimbare_get_frame_buff(unsigned char** buff);                // 读取像素缓冲区
float cimbare_get_aspect_ratio();                                 // 宽高比
int  cimbare_rotate_window(bool rotate);                          // 旋转显示
```

### 与本项目的变更

相比上游 libcimbar 的 `cimbar_js`，本项目新增以下导出函数：

| 函数 | 用途 |
|---|---|
| `_cimbare_get_frame_buff` | 直接读取帧像素，替代 GLFW 窗口 |
| `_cimbare_rotate_window` | 旋转显示方向 |

并移除了上游不存在的 `_cimbare_blocks_required`（前端已做兼容判断）。
