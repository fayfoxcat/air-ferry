package cc.asac.airferry;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;

/**
 * 环形进度 + 百分比显示控件。
 * 外圈：360° 轨道弧（半透明）+ 进度弧（颜色随进度变化：浅灰 → 黄 → 绿）。
 * 中心：进度百分比数字 + "%"，无进度时显示 "--"。
 * 用法：
 * setProgress(int 0-100)  — 带动画更新进度弧和中心百分比文字
 * reset()                 — 清零进度
 */
public class CircularProgressView extends View {

    // ── 画笔 ──────────────────────────────────────────────────────────────────
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── 几何 ──────────────────────────────────────────────────────────────────
    private final RectF arcRect = new RectF();

    // ── 状态 ──────────────────────────────────────────────────────────────────
    private float displayProgress = 0f;
    private int targetProgress = 0;

    // ── 动画 ──────────────────────────────────────────────────────────────────
    private ValueAnimator progressAnimator;

    // ── 进度色阶（5% 一个色阶：浅蓝 → 黄 → 绿）──────────────────────────────
    private static final int[] STAGE_COLORS = {
            0xFF7BB8F0, // 0%    浅蓝
            0xFF85BEF1, // 5%
            0xFF8FC4F2, // 10%
            0xFF99CAF3, // 15%
            0xFFA3D0F4, // 20%   浅蓝向蓝白过渡
            0xFFADD6F5, // 25%
            0xFFB7DCF6, // 30%
            0xFFC1E2F7, // 35%
            0xFFCBE8F8, // 40%   浅蓝向淡黄过渡区
            0xFFD5E4C0, // 45%   淡黄绿过渡
            0xFFDFE088, // 50%
            0xFFE9DC50, // 55%
            0xFFF3D818, // 60%   金黄
            0xFFF0C800, // 65%   橙黄
            0xFFE0B800, // 70%   黄绿过渡
            0xFFC0AA00, // 75%
            0xFFA09C00, // 80%
            0xFF808E00, // 85%   草绿
            0xFF608000, // 90%
            0xFF408000, // 95%
            0xFF208000, // 100%  深绿（稳重）
    };

    /** 根据 progress(0-100) 查色阶表，取最近5%档的颜色 */
    private int colorForProgress(float pct) {
        int idx = Math.round(pct / 5f);
        idx = Math.max(0, Math.min(idx, STAGE_COLORS.length - 1));
        return STAGE_COLORS[idx];
    }

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
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(0x33FFFFFF);

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0x88000000);

        numPaint.setColor(Color.WHITE);
        numPaint.setFakeBoldText(true);
        numPaint.setTextAlign(Paint.Align.CENTER);

        unitPaint.setColor(0xCCFFFFFF);
        unitPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ── 尺寸变化 ──────────────────────────────────────────────────────────────
    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);

        float size = Math.min(w, h);
        float stroke = size * 0.10f;
        float inset = stroke / 2f + 1f;

        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        arcRect.set(inset, inset, w - inset, h - inset);

        numPaint.setTextSize(size * 0.22f);
        unitPaint.setTextSize(size * 0.13f);
    }

    // ── 绘制 ──────────────────────────────────────────────────────────────────
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - trackPaint.getStrokeWidth() / 2f - 1f;

        canvas.drawCircle(cx, cy, r, bgPaint);
        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint);

        if (displayProgress > 0f) {
            arcPaint.setColor(colorForProgress(displayProgress));
            float sweep = displayProgress / 100f * 360f;
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint);
        }

        drawPercentText(canvas, cx, cy);
    }

    private void drawPercentText(Canvas canvas, float cx, float cy) {
        String numStr;
        String unitStr = "%";

        if (displayProgress <= 0f && targetProgress == 0) {
            numStr = "--";
        } else {
            numStr = String.valueOf(Math.round(displayProgress));
        }

        Paint.FontMetrics numFm = numPaint.getFontMetrics();
        Paint.FontMetrics unitFm = unitPaint.getFontMetrics();

        float numLineH = numFm.descent - numFm.ascent;
        float unitLineH = unitFm.descent - unitFm.ascent;
        float gap = getHeight() * 0.04f;
        float totalH = numLineH + gap + unitLineH;

        float numBaseline = cy - totalH / 2f - numFm.ascent;
        float unitBaseline = numBaseline + numFm.descent + gap - unitFm.ascent;

        canvas.drawText(numStr, cx, numBaseline, numPaint);
        canvas.drawText(unitStr, cx, unitBaseline, unitPaint);
    }

    // ── 公开 API ──────────────────────────────────────────────────────────────

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

    public void reset() {
        if (progressAnimator != null) progressAnimator.cancel();
        displayProgress = 0f;
        targetProgress = 0;
        invalidate();
    }
}