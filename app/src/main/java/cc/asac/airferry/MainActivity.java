package cc.asac.airferry;

import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
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
    private final FilesFragment filesFragment = new FilesFragment();
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

        // 鸿蒙/部分国产 ROM 会忽略主题的 navigationBarColor，需程序强制设置
        getWindow().setNavigationBarColor(getResources().getColor(R.color.colorBackground));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().setNavigationBarDividerColor(getResources().getColor(R.color.colorBackground));
        }

        // 在 Activity 层加载 native 库
        loadNativeLibraries();

        AppLogger.get(this).i(TAG, "App started at " + AppLogger.get(this).now());

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
            } else if (id == R.id.nav_files) {
                switchFragment(filesFragment);
                resetTabRotation();
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
                    .add(R.id.fragment_container, filesFragment, "files")
                    .add(R.id.fragment_container, receiverFragment, "receiver")
                    .hide(settingsFragment)
                    .hide(filesFragment)
                    .commit();
        }

        // 处理 App Shortcut / Intent extra 跳转到指定 Tab
        String openTab = getIntent().getStringExtra("open_tab");
        if ("files".equals(openTab)) {
            bottomNav.setSelectedItemId(R.id.nav_files);
        } else if ("settings".equals(openTab)) {
            bottomNav.setSelectedItemId(R.id.nav_settings);
        } else {
            bottomNav.setSelectedItemId(R.id.nav_receiver);
        }

        // Right-edge swipe-back gesture
        setupEdgeBackGesture();

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
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String openTab = intent.getStringExtra("open_tab");
        if ("files".equals(openTab) && bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_files);
        } else if ("settings".equals(openTab) && bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_settings);
        } else if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_receiver);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener.canDetectOrientation())
            orientationListener.enable();
        AppLogger.get(this).i(TAG, "App resumed");
    }

    @Override
    protected void onPause() {
        super.onPause();
        orientationListener.disable();
        AppLogger.get(this).i(TAG, "App paused");
    }

    @Override
    protected void onDestroy() {
        AppLogger.get(this).i(TAG, "App closed at " + AppLogger.get(this).now());
        super.onDestroy();
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

    private void setupEdgeBackGesture() {
        final float edgeWidthPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 40, getResources().getDisplayMetrics());
        final GestureDetector edgeBackDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null) return false;
                        int screenWidth = getResources().getDisplayMetrics().widthPixels;
                        if (e1.getX() < screenWidth - edgeWidthPx) return false;
                        float dx = e2.getX() - e1.getX();
                        if (dx < -100 || velocityX < -1000) {
                            getOnBackPressedDispatcher().onBackPressed();
                            return true;
                        }
                        return false;
                    }
                });

        getWindow().getDecorView().setOnTouchListener((v, event) -> {
            edgeBackDetector.onTouchEvent(event);
            return false;
        });
    }

    public void setMultiActionBarVisible(boolean visible) {
        View actionBar = findViewById(R.id.multi_action_bar);
        if (actionBar != null) {
            actionBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (bottomNav != null) {
            bottomNav.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
    }

    private void loadNativeLibraries() {
        if (nativeLibsLoaded)
            return;
        AppLogger logger = AppLogger.get(this);
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("opencv_java4");
            System.loadLibrary("cfc-cpp");
            nativeLibsLoaded = true;
            logger.i(TAG, "Native libraries loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            logger.e(TAG, "Failed to load native libraries: " + e.getMessage());
            runOnUiThread(() ->
                android.widget.Toast.makeText(this,
                    getString(R.string.native_load_failed, e.getMessage()),
                    android.widget.Toast.LENGTH_LONG).show());
        }
    }

    private void switchFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .hide(receiverFragment)
                .hide(filesFragment)
                .hide(settingsFragment)
                .show(fragment)
                .commit();
    }
}
