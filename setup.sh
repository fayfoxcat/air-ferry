#!/bin/bash
# =============================================================================
# setup.sh — CFC 项目一键初始化脚本
#
# 将所有外部依赖下载到项目根目录（与 app/、web/ 同级），
# 使 app（Android 接收端）和 web（WASM 编码端）共享同一份源码。
#
# 用法：
#   ./setup.sh              # 下载所有依赖（libcimbar + OpenCV）
#   ./setup.sh --libcimbar  # 仅下载 libcimbar
#   ./setup.sh --opencv     # 仅下载 OpenCV（WASM 构建用）
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ---- 依赖版本配置 -----------------------------------------------------------
LIBCIMBAR_REPO="https://github.com/sz3/libcimbar.git"
LIBCIMBAR_BRANCH="master"

OPENCV_REPO="https://github.com/opencv/opencv.git"
OPENCV_TAG="4.13.0"

# ---- 仅下载 libcimbar ---------------------------------------------------------
setup_libcimbar() {
    echo "=============================================="
    echo ">>> 初始化 libcimbar（项目根目录）"
    echo "=============================================="

    if [ -d "libcimbar" ]; then
        echo "[skip] libcimbar/ 已存在，跳过克隆。"
        echo "       如需重新拉取，请先删除: rm -rf libcimbar/"
        return 0
    fi

    echo "[clone] $LIBCIMBAR_REPO (branch: $LIBCIMBAR_BRANCH)"
    git clone --depth 1 --branch "$LIBCIMBAR_BRANCH" "$LIBCIMBAR_REPO" libcimbar
    echo "[done] libcimbar 已下载到 $SCRIPT_DIR/libcimbar/"
}

# ---- 下载 OpenCV 源码（WASM 构建用）-------------------------------------------
setup_opencv() {
    echo "=============================================="
    echo ">>> 初始化 OpenCV（项目根目录）"
    echo "=============================================="

    if [ -d "opencv4" ]; then
        echo "[skip] opencv4/ 已存在，跳过克隆。"
        echo "       如需重新拉取，请先删除: rm -rf opencv4/"
        return 0
    fi

    echo "[clone] $OPENCV_REPO (tag: $OPENCV_TAG)"
    git clone --depth 1 --branch "$OPENCV_TAG" "$OPENCV_REPO" opencv4
    echo "[done] OpenCV 源码已下载到 $SCRIPT_DIR/opencv4/"
    echo "       该目录仅在构建 WASM 编码端时需要。"
}

# ---- 主流程 ------------------------------------------------------------------
case "${1:-}" in
    --libcimbar)
        setup_libcimbar
        ;;
    --opencv)
        setup_opencv
        ;;
    "")
        setup_libcimbar
        setup_opencv
        ;;
    -h|--help)
        echo "用法: $0 [--libcimbar|--opencv|-h]"
        echo ""
        echo "  (无参数)      下载 libcimbar + OpenCV 到项目根目录"
        echo "  --libcimbar   仅下载 libcimbar（app 构建必需）"
        echo "  --opencv      仅下载 OpenCV 源码（WASM 编码端构建用）"
        echo "  -h, --help    显示本帮助"
        exit 0
        ;;
    *)
        echo "未知参数: $1"
        echo "用法: $0 [--libcimbar|--opencv|-h]"
        exit 1
        ;;
esac

echo ""
echo "=============================================="
echo ">>> 初始化完成！"
echo "=============================================="
echo ""
echo "下一步："
echo "  Android 构建:  在 Android Studio 中打开项目根目录，配置 OpenCV SDK 路径后构建"
echo "  WASM 编码端:   docker run --rm \\"
echo "                   --mount type=bind,source=\"\$\(pwd\)\",target=\"/usr/src/app\" \\"
echo "                   emscripten/emsdk:3.1.69 \\"
echo "                   bash /usr/src/app/web/build-wasm-encode.sh"
