package cc.asac.cimbar;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * 环形进度 + 网速显示控件。
 *
 * 外圈：360° 轨道弧（半透明）+ 进度弧（渐变色，从顶部顺时针）。
 * 中心：数字速度（KB/s 或 MB/s），未知时显示 "--"。
 *
 * 用法：
 *   setProgress(int 0-100)  — 带动画更新进度
 *   setSpeed(long bytes/s)  — 更新速度文字（-1 = 未知）
 *   reset()                 — 清零进度和速度
 */
public class CircularProgressView extends View {

    // ── 画笔 ──────────────────────────────────────────────────────────────────
    private final Paint trackPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── 几何 ──────────────────────────────────────────────────────────────────
    private final RectF arcRect = new RectF();

    // ── 状态 ──────────────────────────────────────────────────────────────────
    /** 当前动画中的显示进度（0-100 浮点） */
    private float displayProgress = 0f;
    /** 目标进度（0-100 整数） */
    private int   targetProgress  = 0;
    /** 网速，bytes/s；-1 表示未知 */
    private long  speedBps        = -1;

    // ── 动画 ──────────────────────────────────────────────────────────────────
    private ValueAnimator progressAnimator;

    // ── 渐变 ──────────────────────────────────────────────────────────────────
    private SweepGradient sweepGradient;

    // 渐变颜色：紫 → 蓝紫 → 蓝 → 紫（首尾衔接）
    private static final int[] GRADIENT_COLORS = {
        0xFF7C4DFF,   // 紫（起点，顶部）
        0xFF5C6BC0,   // 蓝紫
        0xFF42A5F5,   // 蓝
        0xFF7C4DFF,   // 紫（终点，与起点衔接）
    };
    private static final float[] GRADIENT_POSITIONS = { 0f, 0.33f, 0.66f, 1f };

    // ── 构造 ──────────────────────────────────────────────────────────────────
    public CircularProgressView(Context context) {
        super(context);
        init();
    }

    public CircularProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CircularProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 轨道（底圈）
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0x33FFFFFF);   // 半透明白

        // 进度弧（颜色由 sweepGradient 覆盖）
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        // 背景圆（半透明黑，让文字可读）
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0x88000000);

        // 数字（白色粗体）
        numPaint.setColor(Color.WHITE);
        numPaint.setFakeBoldText(true);
        numPaint.setTextAlign(Paint.Align.CENTER);

        // 单位（半透明白）
        unitPaint.setColor(0xCCFFFFFF);
        unitPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ── 尺寸变化时重建几何和渐变 ─────────────────────────────────────────────
    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);

        float size    = Math.min(w, h);
        float stroke  = size * 0.10f;          // 笔画宽度 = 10% 尺寸
        float inset   = stroke / 2f + 1f;      // 弧矩形内缩，避免裁剪

        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        arcRect.set(inset, inset, w - inset, h - inset);

        numPaint.setTextSize(size * 0.22f);    // 数字字号
        unitPaint.setTextSize(size * 0.13f);   // 单位字号

        rebuildGradient(w, h);
    }

    /**
     * 构建 SweepGradient，旋转 -90° 使渐变从顶部（12点方向）开始。
     * SweepGradient 默认从 3 点方向（0°）开始，需要旋转矩阵补偿。
     */
    private void rebuildGradient(int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;
        sweepGradient = new SweepGradient(cx, cy, GRADIENT_COLORS, GRADIENT_POSITIONS);

        // 旋转 -90°，使渐变起点对齐顶部
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setRotate(-90f, cx, cy);
        sweepGradient.setLocalMatrix(matrix);

        arcPaint.setShader(sweepGradient);
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cx = getWidth()  / 2f;
        float cy = getHeight() / 2f;
        float r  = Math.min(cx, cy) - trackPaint.getStrokeWidth() / 2f - 1f;

        // 1. 半透明背景圆
        canvas.drawCircle(cx, cy, r, bgPaint);

        // 2. 轨道（完整 360°）
        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint);

        // 3. 进度弧（从顶部顺时针）
        if (displayProgress > 0f) {
            float sweep = displayProgress / 100f * 360f;
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint);
        }

        // 4. 中心速度文字
        drawSpeedText(canvas, cx, cy);
    }

    /**
     * 在圆心绘制速度数字和单位，垂直居中。
     *
     * drawText 的 y 参数是文字基线位置。
     * 用 FontMetrics 精确计算每行的实际高度（cap-height 区域），
     * 让两行整体在 cy 上下对称。
     */
    private void drawSpeedText(Canvas canvas, float cx, float cy) {
        String numStr;
        String unitStr;

        if (speedBps < 0) {
            numStr  = "--";
            unitStr = "KB/s";
        } else if (speedBps >= 1_048_576L) {
            float mb = speedBps / 1_048_576f;
            numStr  = mb < 10f
                    ? String.format("%.1f", mb)
                    : String.valueOf((int) mb);
            unitStr = "MB/s";
        } else {
            numStr  = String.valueOf((int)(speedBps / 1024));
            unitStr = "KB/s";
        }

        Paint.FontMetrics numFm  = numPaint.getFontMetrics();
        Paint.FontMetrics unitFm = unitPaint.getFontMetrics();

        // 每行的视觉高度 = descent - ascent（ascent 为负值）
        float numLineH  = numFm.descent  - numFm.ascent;
        float unitLineH = unitFm.descent - unitFm.ascent;
        float gap       = getHeight() * 0.04f;
        float totalH    = numLineH + gap + unitLineH;

        // 数字行基线：从整体顶部（cy - totalH/2）向下偏移 -ascent（即到基线的距离）
        float numBaseline  = cy - totalH / 2f - numFm.ascent;
        // 单位行基线：数字行顶部 + 数字行高 + 间距 + 单位行到基线距离
        float unitBaseline = numBaseline + numFm.descent + gap - unitFm.ascent;

        canvas.drawText(numStr,  cx, numBaseline,  numPaint);
        canvas.drawText(unitStr, cx, unitBaseline, unitPaint);
    }

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 带动画更新进度（0-100）。
     */
    public void setProgress(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        if (clamped == targetProgress) return;
        targetProgress = clamped;

        if (progressAnimator != null) progressAnimator.cancel();

        progressAnimator = ValueAnimator.ofFloat(displayProgress, clamped);
        progressAnimator.setDuration(400);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.addUpdateListener(anim -> {
            displayProgress = (float) anim.getAnimatedValue();
            invalidate();
        });
        progressAnimator.start();
    }

    /**
     * 更新网速显示。
     * @param bps 字节/秒；传 -1 显示 "--"
     */
    public void setSpeed(long bps) {
        if (speedBps != bps) {
            speedBps = bps;
            invalidate();
        }
    }

    /**
     * 重置进度和速度（文件完成后调用）。
     */
    public void reset() {
        if (progressAnimator != null) progressAnimator.cancel();
        displayProgress = 0f;
        targetProgress  = 0;
        speedBps        = -1;
        invalidate();
    }
}
