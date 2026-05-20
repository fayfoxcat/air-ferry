package cc.asac.cimbar;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatToggleButton;

public class ModeSelToggle extends AppCompatToggleButton {

    private static final int[] STATE_MODE_4C = { R.attr.state_mode4c };
    private static final int[] STATE_MODE_BM = { R.attr.state_modebm };
    private static final int[] STATE_MODE_BU = { R.attr.state_modebu };

    private int mModeVal = 0;

    public ModeSelToggle(Context context) {
        super(context);
    }

    public ModeSelToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ModeSelToggle(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    // 设置模式值
    public void setModeVal(int modeval) {
        if (mModeVal != modeval) {
            mModeVal = modeval;
            // 强制视图重新评估其 drawable 状态
            refreshDrawableState(); 
        }
    }

    @Override
    protected int[] onCreateDrawableState(int extraSpace) {
        // 1. 获取当前标准 drawable 状态（包含 state_checked）
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);

        // 仅在复选框被选中时处理
        if (isChecked()) {
            switch (mModeVal) {
                case 4:
                    mergeDrawableStates(drawableState, STATE_MODE_4C);
                    break;
                case 66:
                    mergeDrawableStates(drawableState, STATE_MODE_BU);
                    break;
                case 67:
                    mergeDrawableStates(drawableState, STATE_MODE_BM);
                    break;
                case 68:
                default:
                    // 默认模式（B）无需额外处理
                    break;
            }
        }

        return drawableState;
    }
}
