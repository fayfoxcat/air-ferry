package cc.asac.cimbar;

import android.os.Bundle;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    static volatile boolean nativeLibsLoaded = false;

    private final ReceiverFragment receiverFragment = new ReceiverFragment();
    private final SettingsFragment settingsFragment = new SettingsFragment();
    private OrientationEventListener orientationListener;
    private BottomNavigationView bottomNav;
    private float currentTabRotation = 0f;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Edge-to-edge：绘制到状态栏和导航栏后面
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // 在 Activity 层加载 native 库
        loadNativeLibraries();

        setContentView(R.layout.activity_main);

        // 应用内边距：底部导航栏加上系统导航栏高度，并绑定导航事件
        bottomNav = findViewById(R.id.bottom_nav);
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navInsets.bottom);
            return insets;
        });
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_receiver) {
                switchFragment(receiverFragment);
                // 切回接收页面时，恢复当前旋转角度
                applyCurrentRotationToTabs();
                return true;
            } else if (id == R.id.nav_settings) {
                switchFragment(settingsFragment);
                // 切换到设置页面时，将 Tab 图标重置为正常方向
                resetTabRotation();
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, settingsFragment, "settings")
                    .add(R.id.fragment_container, receiverFragment, "receiver")
                    .hide(settingsFragment)
                    .commit();
        }

        orientationListener = new OrientationEventListener(this) {
            private int lastQuadrant = -1;

            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                // 将原始角度映射到最近的 90° 象限
                int quadrant;
                if (orientation < 45 || orientation >= 315) {
                    quadrant = 0;       // 竖屏
                } else if (orientation < 135) {
                    quadrant = 90;      // 横屏右旋（设备逆时针旋转）
                } else if (orientation < 225) {
                    quadrant = 180;     // 倒置竖屏
                } else {
                    quadrant = 270;     // 横屏左旋（设备顺时针旋转）
                }
                if (quadrant == lastQuadrant) return;
                lastQuadrant = quadrant;

                // 将象限角映射到 [-180, 180] 范围，让 ViewPropertyAnimator
                // 始终走最短弧路径，避免 270° 被当作 -90° 的等价而走反方向。
                // 规则：0→0°, 90→-90°（向右横屏逆时针), 180→180°, 270→90°（向左横屏顺时针）
                float itemRotation;
                switch (quadrant) {
                    case 90:  itemRotation = -90f; break;
                    case 180: itemRotation = 180f; break;
                    case 270: itemRotation =  90f; break;
                    default:  itemRotation =   0f; break;
                }
                rotateTabItems(itemRotation);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener.canDetectOrientation())
            orientationListener.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        orientationListener.disable();
    }

    private void rotateTabItems(float rotation) {
        currentTabRotation = rotation;
        if (bottomNav == null) return;
        // 仅在接收页面时旋转 Tab 图标；设置页面保持正常方向
        boolean isReceiverActive = bottomNav.getSelectedItemId() == R.id.nav_receiver;
        ViewGroup menuView = (ViewGroup) bottomNav.getChildAt(0);
        if (menuView == null) return;
        for (int i = 0; i < menuView.getChildCount(); i++) {
            menuView.getChildAt(i).animate()
                    .rotation(isReceiverActive ? rotation : 0f)
                    .setDuration(250)
                    .start();
        }
        receiverFragment.setDeviceRotation(rotation);
    }

    /** 切换到设置页面时，将所有 Tab 图标动画归零 */
    private void resetTabRotation() {
        if (bottomNav == null) return;
        ViewGroup menuView = (ViewGroup) bottomNav.getChildAt(0);
        if (menuView == null) return;
        for (int i = 0; i < menuView.getChildCount(); i++) {
            menuView.getChildAt(i).animate()
                    .rotation(0f)
                    .setDuration(250)
                    .start();
        }
    }

    /** 切换回接收页面时，恢复当前设备旋转角度 */
    private void applyCurrentRotationToTabs() {
        if (bottomNav == null) return;
        ViewGroup menuView = (ViewGroup) bottomNav.getChildAt(0);
        if (menuView == null) return;
        for (int i = 0; i < menuView.getChildCount(); i++) {
            menuView.getChildAt(i).animate()
                    .rotation(currentTabRotation)
                    .setDuration(250)
                    .start();
        }
    }

    private void loadNativeLibraries() {
        if (nativeLibsLoaded)
            return;
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("opencv_java4");
            System.loadLibrary("cfc-cpp");
            nativeLibsLoaded = true;
            Log.i(TAG, "Native libraries loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries: " + e.getMessage());
        }
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .hide(receiverFragment)
                .hide(settingsFragment)
                .show(fragment)
                .commit();
    }
}
