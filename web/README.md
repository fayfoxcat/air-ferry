# Cimbar Web 编码器

将文件编码为 Cimbar 彩色矩阵条码动画，在浏览器中运行，无需安装任何软件。

## 文件说明

```
web/
├── index.html          # 前端页面
├── main.js             # 前端逻辑（调用 WASM C API）
├── CMakeLists.txt      # 编码端 WASM 构建配置
├── build-wasm-encode.sh# 构建脚本（在 Docker 容器内运行）
├── Dockerfile          # 构建镜像
└── cimbar_js.js        # ← 构建产物（构建后生成）
```

## 构建 WASM

### 前置条件

- Docker（推荐）或本地安装的 Emscripten 3.1.69+
- 网络连接（首次构建需要克隆 OpenCV）

### 方式一：Docker 一键构建（推荐）

```bash
# 在项目根目录执行
# 1. 克隆 OpenCV（只需一次）
git clone --depth 1 --branch 4.11.0 https://github.com/opencv/opencv.git opencv4

# 2. 启动 emscripten 容器并运行构建脚本
docker run --rm \
  --mount type=bind,source="$(pwd)",target="/usr/src/app" \
  emscripten/emsdk:3.1.69 \
  bash /usr/src/app/web/build-wasm-encode.sh
```

构建完成后，`web/cimbar_js.js` 会自动生成。

### 方式二：使用自定义镜像

```bash
# 构建镜像
docker build -t cimbar-wasm-builder -f web/Dockerfile .

# 运行构建（OpenCV 缓存到命名卷）
docker run --rm \
  -v "$(pwd):/usr/src/app" \
  -v cimbar-opencv-cache:/usr/src/app/opencv4 \
  cimbar-wasm-builder
```

### 方式三：本地 Emscripten

```bash
# 确保已安装 emsdk 并激活环境
source /path/to/emsdk/emsdk_env.sh

# 在项目根目录
mkdir build-wasm-encode && cd build-wasm-encode
emcmake cmake ../web \
    -DUSE_WASM=2 \
    -DOPENCV_DIR=/path/to/opencv4 \
    -DCMAKE_INSTALL_PREFIX=../web
make -j$(nproc) cimbar_js
make install
```

## 运行

构建完成后，直接用任意 HTTP 服务器托管 `web/` 目录：

```bash
# Python
cd web && python3 -m http.server 8080

# Node.js
cd web && npx http-server -p 8080
```

然后打开 `http://localhost:8080`。

> **注意**：由于 WASM 的 CORS 限制，必须通过 HTTP 服务器访问，不能直接打开 `file://`。

## 编码模式说明

| 模式 | 分辨率 | 说明 |
|------|--------|------|
| 68（标准） | 1024×1024 | 默认，最高数据密度 |
| 67（迷你） | 1024×720  | 宽屏格式 |
| 66（微型） | 736×637   | 小尺寸屏幕 |

## C API 接口（WASM 导出函数）

编码端导出以下 C 函数（`USE_WASM=2` 模式）：

```c
// 配置编码模式和压缩级别
int  cimbare_configure(int mode_val, int compression);

// 初始化一次编码会话（传入文件名）
int  cimbare_init_encode(const char* filename, unsigned fnsize, int encode_id);

// 查询每次 encode 调用的缓冲区大小
int  cimbare_encode_bufsize();

// 喂入文件数据（分块调用，最后一块 size < bufsize 触发完成）
int  cimbare_encode(const unsigned char* buffer, unsigned size);

// 生成下一帧（返回帧序号，<0 表示错误）
int  cimbare_next_frame(bool color_balance);

// 获取当前帧的像素缓冲区指针（RGB，3 通道）
int  cimbare_get_frame_buff(unsigned char** buff);

// 获取当前配置的宽高比
float cimbare_get_aspect_ratio();

// 旋转显示（0=正常，1=旋转90°）
int  cimbare_rotate_window(bool rotate);
```
