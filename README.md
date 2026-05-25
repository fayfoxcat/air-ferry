# AirFerry

本项目 fork 自 [sz3/cfc](https://github.com/sz3/cfc)，在上游基础上进行了功能扩展与体验优化。

AirFerry 是一款 Android **纯接收端**应用，通过摄像头扫描动态彩色条码来接收文件。**无需任何无线连接**（WiFi、蓝牙、NFC……），在飞行模式下同样可以正常工作。

发送端请使用网页版 [airferry.asac.cc](https://airferry.asac.cc)（推荐）或 libcimbar 的 `cimbar_send`。

## 下载

Release APK 在 [Releases 页面](../../releases) 下载。仅支持 **arm64-v8a**（Android 9+）。

---

## 功能

### 接收

- 摄像头实时扫描 cimbar 彩色条码，自动解码文件
- 自动识别编码模式（66 / 67 / 68），支持手动锁定模式防止中途切换
- 实时圆形进度条显示解码百分比
- 扫描框角点引导动画，辅助对准
- 设备旋转感知：UI 元素（进度环、模式切换、底部导航图标）跟随物理旋转方向动画对齐
- MD5 去重：相同文件不重复保存

### 接收后操作（可在设置中独立开关）

- **本地保存**：通过系统文件选择器保存到任意位置
- **云端上传**：HTTP POST 上传到自定义 URL，支持自定义请求头
- **社交分享**：通过系统分享菜单分享给微信等应用
- 操作失败时显示错误信息，支持一键复制错误内容

### 文件列表

- 持久化记录所有已接收文件（文件名、大小、接收时间、解码耗时）
- 按文件名实时搜索过滤
- 左滑显示单条操作：保存、分享、删除
- 长按进入多选模式：全选 / 取消全选、批量保存、批量分享、批量删除

### 设置

- **文件存储**：本地存储 / 云端上传（含 URL 和自定义 Header 配置、连通性测试）/ 社交分享
- **语言**：中文 / English，切换后立即生效
- **日志**：查看、清空、导出运行日志
- **关于**：显示当前版本号
- **开源地址**：一键跳转 GitHub 仓库
- **检查更新**：查询 GitHub Releases API，有新版本时显示版本号并提供下载链接

### 应用层

- 底部三标签导航：接收 / 文件 / 设置
- 常亮屏幕（扫码期间不息屏）
- Edge-to-edge 全面屏显示
- 右边缘左滑返回手势

---

## 子模块

| 子模块 | 来源 | 作用 |
|---|---|---|
| `app/` | sz3/cfc Android 端（修改） | 摄像头接收端，从 cimbar 码解码文件 |
| `web/` | libcimbar cimbar_js | WASM 编码端，将文件编码为 cimbar 码动画 |
| `libcimbar/` | sz3/libcimbar | 核心编解码库（由 setup.sh 下载，不纳入版本控制） |

---

## 目录结构

```
air-ferry/
├── setup.sh              # 一键初始化（下载 libcimbar + OpenCV）
├── build.sh              # Android 构建脚本
├── app/                  # Android 接收端
│   ├── build.gradle
│   └── src/
│       ├── main/         #   Java 层（UI、Camera2）
│       └── cpp/          #   C++ 层（JNI + CMake）
│           ├── cfc-cpp/  #   解码器入口（JNI 桥接）
│           └── CMakeLists.txt
├── web/                  # WASM 编码端
│   ├── index.html
│   ├── main.js
│   ├── CMakeLists.txt
│   └── build-wasm-encode.sh
└── libcimbar/            # 上游源码（app + web 共享，不纳入版本控制）
```

---

## 快速开始

```bash
# 1. 克隆仓库
git clone https://github.com/fayfoxcat/air-ferry.git
cd air-ferry

# 2. 下载依赖（libcimbar + OpenCV）
bash setup.sh

# 3. 用 Android Studio 打开项目根目录构建 APK
#    WASM 构建见 web/README.md
```

---

## 相较上游的改动

### app（Android 接收端）

- **移除发送端**：仅保留接收端
- **包名更改**：`org.cimbar.camerafilecopy` → `cc.asac.airferry`
- **多语言支持**：中文 / 英文可切换
- **文件接收方式**：本地保存 + HTTP POST 上传 + 社交分享
- **文件历史**：持久化记录、搜索、多选批量操作
- **UI 重构**：底部三标签导航，Edge-to-edge 全面屏
- **扫描动画**：角点引导 + 圆形进度条
- **横屏适配**：导航图标跟随设备旋转动画
- **日志系统**：运行日志本地持久化，支持查看 / 清空 / 导出
- **检查更新**：内置 GitHub Releases 版本检测
- **16 KB 页对齐**：符合 Android 15+ 及 Google Play 2025 年要求
- **CI/CD**：GitHub Actions 自动构建与发布

### web（WASM 编码端）

- **编码帧实时预览**：通过 `cimbare_get_frame_buff()` 直接读取原始像素，替代 GLFW 窗口渲染
- **无 WebGL 依赖**：可在 headless 环境运行
- **导出函数增强**：添加 `_cimbare_get_frame_buff`、`_cimbare_rotate_window` 等导出
- **编码模式选择**：支持 68/B（标准）、67/BM（迷你）、66/BU（微型）

---

## 许可证

| 组件 | 许可证 |
|---|---|
| 本项目（AirFerry） | MIT |
| libcimbar | MPL 2.0 |
| libcimbar 依赖库 | MIT、BSD、zlib、Boost、Apache |
| OpenCV | Apache 2.0 |

---

## 上游项目

- 上游仓库：[sz3/cfc](https://github.com/sz3/cfc)
- libcimbar：[sz3/libcimbar](https://github.com/sz3/libcimbar)
- 在线发送端：[https://airferry.asac.cc](https://airferry.asac.cc)
