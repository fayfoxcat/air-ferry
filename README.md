### [INTRODUCTION](https://github.com/sz3/cimbar) | [ABOUT](https://github.com/sz3/cimbar/blob/master/ABOUT.md) | CFC | [LIBCIMBAR](https://github.com/sz3/libcimbar)

## CameraFileCopy（二次开发版）

本项目 fork 自 [sz3/cfc](https://github.com/sz3/cfc)，在上游基础上进行了功能扩展与体验优化。

本项目包含两个子模块，均围绕 libcimbar 核心库构建：

| 子模块 | 来源 | 作用 |
|---|---|---|
| `app/` | sz3/cfc Android 端（修改） | 摄像头接收端，从 cimbar 码解码文件 |
| `web/` | libcimbar cimbar_js | WASM 编码端，将文件编码为 cimbar 码动画 |
| `libcimbar/` | sz3/libcimbar | 核心编解码库（由 setup.sh 下载，不纳入版本控制） |

这是一款 Android **纯接收端**应用，通过摄像头接收数据，实现单向文件传输。**无需任何天线**（WiFi、蓝牙、NFC……），在飞行模式下同样可以正常工作。

> 本项目已移除上游的发送端（sender）代码，仅保留接收端 Android 应用。
>
> 发送端请使用网页版 [cimbar.org](https://cimbar.org)（推荐）或 libcimbar 的 `cimbar_send`。

## 目录结构

```
cfc/
├── setup.sh              # 一键初始化（下载 libcimbar + OpenCV）
├── build.sh              # Android 构建脚本
├── app/                  # Android 接收端 ← app/README.md
│   ├── build.gradle
│   └── src/
│       ├── main/         #   Java 层（UI、Camera2）
│       └── cpp/          #   C++ 层（JNI + CMake）
│           ├── cfc-cpp/  #   解码器入口（JNI 桥接）
│           └── CMakeLists.txt
├── web/                  # WASM 编码端 ← web/README.md
│   ├── index.html
│   ├── main.js           #   JS 前端（调用 C API）
│   ├── CMakeLists.txt    #   WASM 构建配置
│   └── build-wasm-encode.sh
└── libcimbar/            # ← 上游源码（app + web 共享）
```

## 快速开始

```bash
# 1. 克隆仓库
git clone https://github.com/fayfoxcat/air-ferry.git
cd cfc

# 2. 下载依赖
bash setup.sh

# 3. Android 构建 → 用 Android Studio 打开项目根目录
#     WASM 构建 → 见 web/README.md
```

## 相较上游的改动

### 针对 app（Android 接收端）

- **移除发送端**：仅保留接收端
- **包名更改**：`org.cimbar.camerafilecopy` → `cc.asac.cimbar`
- **多语言支持**：中文 / 英文可切换
- **文件接收方式**：本地保存 + HTTP POST 上传
- **UI 重构**：底部导航栏（接收/设置），Edge-to-edge 全面屏
- **扫描动画**：扫描框内扫描线动画
- **进度显示**：实时解码进度百分比
- **横屏适配**：导航图标跟随设备旋转
- **16 KB 页对齐**：符合 Android 15+ 要求
- **CI/CD**：GitHub Actions 自动构建与发布

### 针对 web（WASM 编码端）

- **编码帧实时预览**：通过 `cimbare_get_frame_buff()` 直接读取原始像素，替代 GLFW 窗口渲染
- **无 WebGL 依赖**：可在 headless 环境运行
- **导出函数增强**：添加 `_cimbare_get_frame_buff`、`_cimbare_rotate_window` 等导出
- **编码模式选择**：支持 68/B（标准）、67/BM（迷你）、66/BU（微型）

## 下载

Release APK 在 [Releases 页面](../../releases) 下载。仅支持 **arm64-v8a**。

## 许可证

| 组件 | 许可证 |
|---|---|
| 本项目（cfc） | MIT |
| libcimbar | MPL 2.0 |
| libcimbar 依赖库 | MIT、BSD、zlib、Boost、Apache |
| OpenCV | Apache 2.0 |

## 上游项目

- 上游仓库：[sz3/cfc](https://github.com/sz3/cfc)
- libcimbar：[sz3/libcimbar](https://github.com/sz3/libcimbar)
- 在线发送端：[https://cimbar.org](https://cimbar.org)
