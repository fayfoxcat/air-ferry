### [INTRODUCTION](https://github.com/sz3/cimbar) | [ABOUT](https://github.com/sz3/cimbar/blob/master/ABOUT.md) | CFC | [LIBCIMBAR](https://github.com/sz3/libcimbar)

## CameraFileCopy（二次开发版）

本项目 fork 自 [sz3/cimbar](https://github.com/sz3/cimbar)，在上游基础上进行了功能扩展与体验优化。

这是一款 Android **纯接收端**应用，通过摄像头接收数据，实现单向文件传输。**无需任何天线**（WiFi、蓝牙、NFC……），在飞行模式下同样可以正常工作。

> 本项目已移除上游的发送端（sender）代码，仅保留接收端 Android 应用。

应用读取动态 [cimbar 码](https://github.com/sz3/libcimbar)。核心编解码逻辑来自 libcimbar（项目根目录 `libcimbar/`，由 `setup.sh` 下载），指向上游 [sz3/libcimbar](https://github.com/sz3/libcimbar)。详见 [docs/LIBCIMBAR.md](docs/LIBCIMBAR.md)。

**发送端**需使用独立工具：
- 网页版：[https://cimbar.org](https://cimbar.org)（推荐，无需安装）
- 命令行：libcimbar 的 [`cimbar_send`](https://github.com/sz3/libcimbar)

打开文件初始化 cimbar 流后，将本应用摄像头对准动态条码即可接收。

---

## 相较上游的改动

- **移除发送端**：仅保留 Android 接收端，发送端请使用 [cimbar.org](https://cimbar.org) 或 libcimbar 的 `cimbar_send`
- **包名更改**：`org.cimbar.camerafilecopy` → `cc.asac.cimbar`，可与上游版本共存安装
- **多语言支持**：新增中文界面，可在设置中切换中文 / 英文
- **文件接收方式**：新增"上传到指定 URL"模式，接收完成后自动以 HTTP POST 上传文件（`Content-Type: application/octet-stream`，附带 `X-Filename` 请求头）
- **UI 重构**：底部导航栏（接收 / 设置两个 Tab），支持 Edge-to-edge 全面屏显示
- **扫描动画**：扫描框内增加扫描线动画，提升操作反馈
- **进度显示**：实时显示解码进度百分比
- **横屏适配**：设备旋转时底部导航图标随之旋转，保持视觉直立
- **16 KB 页对齐**：符合 Android 15+ 及 Google Play 2025 年 11 月起的要求
- **CI/CD**：新增 GitHub Actions 自动构建与发布工作流，支持签名密钥通过 Secrets 注入

---

## 下载

Release APK 可在 [Releases 页面](../../releases) 下载。

目前仅官方支持 **arm64-v8a** 架构。

---

## 构建

1. 克隆仓库：
   ```
   git clone https://github.com/fayfoxcat/cfc.git
   cd cfc
   ```
2. 下载依赖（libcimbar 等）到项目根目录：
   ```
   bash setup.sh --libcimbar
   ```
   也可一次性下载所有依赖（含 WASM 构建所需的 OpenCV 源码）：
   ```
   bash setup.sh
   ```
3. 安装 [Android Studio](https://developer.android.com/studio)
4. 安装 Android NDK（版本 `30.0.14904198`）
5. 下载 [OpenCV Android SDK 4.13.0](https://github.com/opencv/opencv/releases/download/4.13.0/opencv-4.13.0-android-sdk.zip)
6. 将本仓库作为项目根目录在 Android Studio 中打开
7. 配置 OpenCV SDK 路径（二选一）：
   - 在项目根目录创建 `local.properties`，添加：
     ```
     opencvsdk=/path/to/OpenCV-android-sdk
     ```
   - 或设置环境变量 `OPENCV_SDK=/path/to/OpenCV-android-sdk`

参考项目：[native-opencv-android-template](https://github.com/VlSomers/native-opencv-android-template)

### CI 自动构建

推送到 `master` 分支或创建 Pull Request 时自动触发构建，产物（debug/release APK）上传至 GitHub Actions Artifacts。

推送 `v*` 格式的 tag 时触发发布流程，自动将 release APK 上传至 GitHub Releases。签名密钥通过以下 Secrets 配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件的 Base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

---

## 许可证与依赖

- 本项目（cfc）代码：**MIT License**
- libcimbar：**MPL 2.0**
- libcimbar 的依赖库：MIT、BSD、zlib、Boost、Apache 等
- OpenCV：**Apache 2.0**

---

## 上游项目

- 上游仓库：[sz3/cfc](https://github.com/sz3/cfc)
- cimbar 协议：[sz3/libcimbar](https://github.com/sz3/libcimbar)
- 在线发送端：[https://cimbar.org](https://cimbar.org)
