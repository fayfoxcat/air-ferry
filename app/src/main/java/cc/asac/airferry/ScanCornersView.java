package cc.asac.airferry;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

public class ScanCornersView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 扫描线动画：分数 0.0 → 1.0（从顶部到底部）
    private final ValueAnimator scanAnimator;
    private float scanFraction = 0f;

    // 设备旋转角度（0=竖屏，90=横屏右旋，270=横屏左旋，180=倒置竖屏）
    // 扫描线沿垂直于"向上"方向的轴扫动。
    private float deviceRotation = 0f;

    public ScanCornersView(Context context, AttributeSet attrs) {
        super(context, attrs);

        linePaint.setStyle(Paint.Style.FILL);

        // 扫描线动画：每次扫动 2 秒，从顶部到底部后重新开始，无限循环
        scanAnimator = ValueAnimator.ofFloat(0f, 1f);
        scanAnimator.setDuration(2000);
        scanAnimator.setRepeatMode(ValueAnimator.RESTART);
        scanAnimator.setRepeatCount(ValueAnimator.INFINITE);
        scanAnimator.setInterpolator(new LinearInterpolator());
        scanAnimator.addUpdateListener(anim -> {
            scanFraction = (float) anim.getAnimatedValue();
            invalidate();
        });
    }

    /**
     * 更新设备旋转角度，使扫描线沿正确方向扫动。
     * @param rotation 角度：0=竖屏，90=横屏右旋，180=倒置竖屏，270=横屏左旋
     */
    public void setDeviceRotation(float rotation) {
        if (deviceRotation != rotation) {
            deviceRotation = rotation;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scanAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        scanAnimator.cancel();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();

        // 将旋转角度归一化为 0 / 90 / 180 / 270 之一
        int rot = ((int) deviceRotation % 360 + 360) % 360;
        boolean isLandscape = (rot == 90 || rot == 270);

        // 在扫动最后 25% 时淡出（分数 0.75 → 1.0）
        float alpha = scanFraction < 0.75f ? 1f : 1f - (scanFraction - 0.75f) / 0.25f;
        int aCC = (int) (0xCC * alpha);
        int aFF = (int) (0xFF * alpha);

        if (!isLandscape) {
            // 竖屏 / 倒置竖屏：扫描线从上到下移动
            float lineH = 2f * density;
            float lineY = scanFraction * (h - lineH);

            LinearGradient gradient = new LinearGradient(
                    0, lineY, w, lineY,
                    new int[]{
                        (0   << 24) | 0xFFFFFF,
                        (aCC << 24) | 0xFFFFFF,
                        (aFF << 24) | 0xFFFFFF,
                        (aCC << 24) | 0xFFFFFF,
                        (0   << 24) | 0xFFFFFF
                    },
                    new float[]{ 0f, 0.15f, 0.5f, 0.85f, 1f },
                    Shader.TileMode.CLAMP
            );
            linePaint.setShader(gradient);
            canvas.drawRect(0, lineY, w, lineY + lineH, linePaint);
        } else {
            // 横屏：扫描线沿水平轴移动。
            // 新的角度映射（MainActivity 传入）：
            //   itemRotation = -90° → 归一化 rot==270 → 设备向右横屏（逆时针旋转）→ 从左向右扫动
            //   itemRotation = +90° → 归一化 rot==90  → 设备向左横屏（顺时针旋转）→ 从右向左扫动
            float lineW = 2f * density;
            float lineX;
            if (rot == 270) {
                // 向右横屏：从左向右
                lineX = scanFraction * (w - lineW);
            } else {
                // rot==90，向左横屏：从右向左
                lineX = (1f - scanFraction) * (w - lineW);
            }

            LinearGradient gradient = new LinearGradient(
                    lineX, 0, lineX, h,
                    new int[]{
                        (0   << 24) | 0xFFFFFF,
                        (aCC << 24) | 0xFFFFFF,
                        (aFF << 24) | 0xFFFFFF,
                        (aCC << 24) | 0xFFFFFF,
                        (0   << 24) | 0xFFFFFF
                    },
                    new float[]{ 0f, 0.15f, 0.5f, 0.85f, 1f },
                    Shader.TileMode.CLAMP
            );
            linePaint.setShader(gradient);
            canvas.drawRect(lineX, 0, lineX + lineW, h, linePaint);
        }
    }
}
