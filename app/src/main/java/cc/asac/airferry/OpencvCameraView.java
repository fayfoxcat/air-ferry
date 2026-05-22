package cc.asac.airferry;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.ImageReader;
import android.util.AttributeSet;
import android.util.Log;

import org.opencv.android.JavaCamera2View;

/**
 * 基于 Camera2 的 OpenCV 摄像头视图。
 * <p>
 * 继承自 JavaCamera2View（OpenCV 内置的 Camera2 实现），并扩展了以下功能：
 * <p>
 * - 智能分辨率选择：优先选择短边最接近 720px（最低 640px）的尺寸。
 *   720p 在 CIMB 1024×1024 条码辨识中已有足够清晰度，同时大幅降低 Scanner 扫描面积。
 *   当没有尺寸满足 640px 最低要求时（如极低端设备），回退到 OpenCV 默认选择器。
 * <p>
 * - 渲染完全委托给 CameraBridgeViewBase.deliverAndDrawFrame()，
 *   该方法使用预分配的 mCacheBitmap 和在 connectCamera() 中一次性计算的 mScale 缩放因子，
 *   避免了每帧分配 Bitmap 和重复缩放计算，从而消除了约 60% 的吞吐量损耗。
 */
public class OpencvCameraView extends JavaCamera2View {

    private static final String TAG = "OpencvCameraView";

    private static final int TARGET_SHORT_SIDE = 1080;
    private static final int MIN_SHORT_SIDE = 960;

    public OpencvCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 重写 connectCamera，在基类调用 calcPreviewSize() 之前，
     * 通过公开的 setMaxFrameSize() API 限制预览分辨率。
     * <p>
     * 分辨率策略：选择短边最接近 1080px 的尺寸，且短边至少为 960px。规则如下：
     * - 低端设备（最大 < 960p）  → 回退到 OpenCV 默认（最大适配尺寸）
     * - 中端设备（最大 1080p）   → 选择 1080p
     * - 高端设备（支持 4K）      → 仍选择 1080p（最接近目标值）
     */
    @Override
    protected boolean connectCamera(int width, int height) {
        if (!selectCamera()) {
            return super.connectCamera(width, height);
        }

        android.util.Size preferred = choosePreviewSize();
        if (preferred != null) {
            // setMaxFrameSize() 是稳定的公开 API：calcPreviewSize() 将选择
            // 在此范围内支持的最大尺寸。
            setMaxFrameSize(preferred.getWidth(), preferred.getHeight());
            Log.i(TAG, "connectCamera: requested max frame size "
                    + preferred.getWidth() + "x" + preferred.getHeight());
        }

        return super.connectCamera(width, height);
    }

    /**
     * 查询摄像头支持的输出尺寸，返回短边最接近 TARGET_SHORT_SIDE（最低 MIN_SHORT_SIDE）的尺寸。
     * 若未找到符合条件的尺寸，返回 null（调用方回退到默认行为）。
     */
    private android.util.Size choosePreviewSize() {
        if (mCameraID == null) return null;

        CameraManager manager =
                (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(mCameraID);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;

            android.util.Size[] sizes = map.getOutputSizes(ImageReader.class);

            android.util.Size best = null;
            int bestDiff = Integer.MAX_VALUE;

            for (android.util.Size s : sizes) {
                int shortSide = Math.min(s.getWidth(), s.getHeight());
                if (shortSide < MIN_SHORT_SIDE) continue;
                int diff = Math.abs(shortSide - TARGET_SHORT_SIDE);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }

            return best; // null → 调用方使用 OpenCV 默认行为

        } catch (CameraAccessException e) {
            Log.e(TAG, "choosePreviewSize: CameraAccessException", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "choosePreviewSize: IllegalArgumentException", e);
        } catch (SecurityException e) {
            Log.e(TAG, "choosePreviewSize: SecurityException", e);
        }
        return null;
    }
}
