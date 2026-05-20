### [INTRODUCTION](https://github.com/sz3/cimbar) | [ABOUT](https://github.com/sz3/cimbar/blob/master/ABOUT.md) | CFC | [LIBCIMBAR](https://github.com/sz3/libcimbar)

## CameraFileCopy（二次开发版）

本项目 fork 自 [sz3/cimbar](https://github.com/sz3/cimbar)，在上游基础上进行了功能扩展与体验优化。

这是一款 Android **纯接收端**应用，通过摄像头接收数据，实现单向文件传输。**无需任何天线**（WiFi、蓝牙、NFC……），在飞行模式下同样可以正常工作。

> 本项目已移除上游的发送端（sender）代码，仅保留接收端 Android 应用。
>
> 发送端请使用网页版 [cimbar.org](https://cimbar.org)（推荐）或 libcimbar 的 `cimbar_send`。

应用读取动态 [cimbar 码](https://github.com/sz3/libcimbar)。核心编解码逻辑来自 libcimbar（项目根目录 `libcimbar/`，由 `setup.sh` 下载），指向上游 [sz3/libcimbar](https://github.com/sz3/libcimbar)。

## 目录结构

```
cfc/
├── setup.sh              # 一键初始化脚本（下载 libcimbar + OpenCV）
├── build.sh              # Android 构建脚本
├── app/                  # Android 接收端 ← app/README.md
│   ├── build.gradle
│   └── src/
│       ├── main/         #     Java 层（UI、Camera2）
│       └── cpp/          #     C++ 层（JNI + CMake）
│           ├── cfc-cpp/  #       JNI 桥接（解码器入口）
│           └── CMakeLists.txt
├── web/                  # WASM 编码端 ← web/README.md
│   ├── index.html
│   ├── main.js           #     JavaScript 前端
│   ├── CMakeLists.txt    #     WASM 构建配置
│   └── build-wasm-encode.sh
└── libcimbar/            # ← 上游源码（setup.sh 下载，不纳入版本控制）
```

## 快速开始

```bash
# 1. 克隆仓库
git clone https://github.com/fayfoxcat/cfc.git
cd cfc

# 2. 下载依赖到项目根目录
bash setup.sh

# 3. 用 Android Studio 打开项目根目录，构建 app 模块
```

各子目录详见：
- [app/README.md](app/README.md) —— Android 构建、架构、CI/CD
- [web/README.md](web/README.md) —— WASM 编码端构建与 C API

## 相较上游的改动

- **移除发送端**：仅保留 Android 接收端
- **包名更改**：`org.cimbar.camerafilecopy` → `cc.asac.cimbar`
- **多语言支持**：中文 / 英文可切换
- **文件接收方式**：本地保存 + HTTP POST 上传（`Content-Type: application/octet-stream`，附带 `X-Filename`）
- **UI 重构**：底部导航栏（接收 / 设置），Edge-to-edge 全面屏
- **扫描动画**：扫描框内扫描线动画
- **进度显示**：实时解码进度百分比
- **横屏适配**：底部导航图标跟随设备旋转
- **16 KB 页对齐**：符合 Android 15+ 及 Google Play 2025 年 11 月起要求
- **CI/CD**：GitHub Actions 自动构建与发布

## 下载

Release APK 可在 [Releases 页面](../../releases) 下载。目前仅支持 **arm64-v8a** 架构。

## 许可证

| 组件 | 许可证 |
|---|---|
| 本项目（cfc） | MIT |
| libcimbar | MPL 2.0 |
| libcimbar 依赖库 | MIT、BSD、zlib、Boost、Apache 等 |
| OpenCV | Apache 2.0 |

## 上游项目

- 上游仓库：[sz3/cfc](https://github.com/sz3/cfc)
- cimbar 协议：[sz3/libcimbar](https://github.com/sz3/libcimbar)
- 在线发送端：[https://cimbar.org](https://cimbar.org)
