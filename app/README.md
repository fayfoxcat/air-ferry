# App — Android 接收端

纯接收端 Android 应用，通过摄像头扫描 cimbar 彩色矩阵条码，实现无网络文件传输。

## 构建

### 前置条件

- Android Studio
- Android NDK `30.0.14904198`
- [OpenCV Android SDK 4.13.0](https://github.com/opencv/opencv/releases/download/4.13.0/opencv-4.13.0-android-sdk.zip)
- 项目根目录 `libcimbar/`（运行根目录 `setup.sh --libcimbar`）

### 步骤

1. 在项目根目录创建 `local.properties`：
   ```properties
   sdk.dir=C\:\\Users\\...\\Android\\Sdk
   opencvsdk=C\:\\...\\OpenCV-android-sdk
   ```
   或设置环境变量 `OPENCV_SDK`。

2. 用 Android Studio 打开**项目根目录**（不是 `app/`）。

3. Build → Make Project。

### 命令行构建

```bash
# 在项目根目录执行
bash build.sh debug
bash build.sh release
```

## 架构

```
app/src/
├── main/java/cc/asac/cimbar/   # Java 层
│   ├── ReceiverFragment.java   #   相机帧回调 → JNI
│   ├── SettingsFragment.java   #   设置页面
│   └── ...
└── cpp/                        # C++ 层
    ├── CMakeLists.txt           #   顶层 CMake（引用 libcimbar 各模块）
    ├── cfc-cpp/
    │   ├── jni.cpp              #   JNI 入口 + 角标绘制
    │   ├── MultiThreadedDecoder.h # 多线程解码器
    │   └── CMakeLists.txt
    └── concurrent/              #   线程池 / 监控工具
```

### JNI 数据流

```
Java (Camera2) → cv::Mat → processImageJNI()
  ├── mat.clone() → MultiThreadedDecoder::add()   # 解码
  ├── drawGuidance(mat, ...)                      # 绘制角标
  └── return result → Java UI 更新
```

### libcimbar 链接依赖

`cfc-cpp/CMakeLists.txt` 链接以下 libcimbar 模块：

| 模块 | 作用 |
|---|---|
| `cimb_translator` | 条码翻译核心（编解码、读取/写入） |
| `extractor` | 图像特征提取与定位 |
| `correct_static` | Reed-Solomon 纠错 |
| `wirehair` | Fountain 码纠删 |
| `zstd` | Zstandard 压缩 |

## CI/CD

推送 `master` 或 Pull Request 时触发构建，产物上传至 GitHub Actions Artifacts。

推送 `v*` 格式 tag 时触发发布，签名密钥通过 Secrets 配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件 Base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |
