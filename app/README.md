# app/ — Android 接收端

基于上游 [sz3/cfc](https://github.com/sz3/cfc) Android 端修改，移除发送端功能，保留纯接收能力。通过摄像头扫描 cimbar 彩色矩阵条码，实现无网络文件传输。

核心解码逻辑来自 `libcimbar/`（项目根目录，由 `setup.sh --libcimbar` 下载）。

## 构建

### 前置条件

- Android Studio
- Android NDK `30.0.14904198`
- [OpenCV Android SDK 4.13.0](https://github.com/opencv/opencv/releases/download/4.13.0/opencv-4.13.0-android-sdk.zip)
- 项目根目录 `libcimbar/`（运行 `bash setup.sh --libcimbar`）

### 步骤

1. 项目根目录创建 `local.properties`：
   ```properties
   sdk.dir=C\:\\Users\\...\\Android\\Sdk
   opencvsdk=C\:\\...\\OpenCV-android-sdk
   ```
   或设置环境变量 `OPENCV_SDK`。

2. 用 Android Studio 打开**项目根目录**（不是 `app/`）。

3. Build → Make Project / Run。

### 命令行

```bash
# 项目根目录执行
bash build.sh debug     # debug APK
bash build.sh release   # release APK
```

## 架构

```
app/src/
├── main/java/cc/asac/airferry/  # Java 层
│   ├── ReceiverFragment.java    #   相机帧回调 → JNI
│   ├── SettingsFragment.java    #   设置页面
│   └── ...
└── cpp/                         # C++ 层
    ├── CMakeLists.txt           #   顶层 CMake（引用 libcimbar 各模块）
    ├── cfc-cpp/
    │   ├── jni.cpp              #   JNI 入口 + decode pipeline + 角标绘制
    │   ├── MultiThreadedDecoder.h # 多线程解码器
    │   └── CMakeLists.txt
    └── concurrent/              #   线程池与监控工具
```

### JNI 数据流

```
Java (Camera2) → cv::Mat → processImageJNI()
  ├── mat.clone() → MultiThreadedDecoder::add()   # 解码线程
  ├── drawGuidance(mat, ...)                      # 绘制四角定位符
  └── return result → Java UI 更新（进度/文件名）
```

### libcimbar 链接依赖

`cfc-cpp/CMakeLists.txt` 链接以下 libcimbar 模块：

| 模块 | 作用 | 来自 libcimbar 目录 |
|---|---|---|
| `cimb_translator` | 条码翻译核心（编解码、读取/写入） | `src/lib/cimb_translator` |
| `extractor` | 图像特征提取与扫描定位 | `src/lib/extractor` |
| `correct_static` | Reed-Solomon 纠错 | `src/third_party_lib/libcorrect` |
| `wirehair` | Fountain 码纠删 | `src/third_party_lib/wirehair` |
| `zstd` | Zstandard 压缩 | `src/third_party_lib/zstd` |

## CI/CD

推送 `master` 或 Pull Request → 自动构建，APK 上传至 GitHub Actions Artifacts。

推送 `v*` tag → 自动发布 Release。签名密钥通过 Secrets 注入：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | keystore Base64 编码 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |
