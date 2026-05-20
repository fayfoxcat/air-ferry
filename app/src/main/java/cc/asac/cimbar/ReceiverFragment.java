package cc.asac.cimbar;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.core.Mat;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ReceiverFragment extends Fragment implements CvCameraViewListener2 {
    private static final String TAG = "ReceiverFragment";

    private String activePath;
    // 当前正在保存的文件名，保存此值是为了在用户取消保存对话框时，
    // 可通过 JNI resetCompletedJNI 将其从 _completed 中移除。
    private String pendingFileName;
    private boolean isSaving = false;

    // 延迟初始化，在 onAttach 或 onCreate 中注册
    private ActivityResultLauncher<Intent> createFileLauncher;

    // 新增权限请求 launcher
    private ActivityResultLauncher<String> cameraPermissionLauncher;

    private CameraBridgeViewBase mOpenCvCameraView;
    private ModeSelToggle mModeSwitch;
    private ScanCornersView mScanCornersView;
    private CircularProgressView circularProgress;
    private int modeVal = 0;
    private int detectedMode = 68;
    private String dataPath;
    private int deviceRotationDeg = 0;

    // ── 网速计算（滑动窗口，在 camera 线程更新）──────────────────────────────
    // 直接使用 JNI 上报的累计已解码字节数，结合时间差计算真实速度。
    // 不再依赖文件大小估算，对任意大小的文件都准确。
    private static final long   SPEED_WINDOW_MS      = 3000L;       // 3 秒滑动窗口
    // 环形缓冲：记录最近若干次进度更新的 (时间戳ms, 累计字节数)
    private static final int    WINDOW_SIZE          = 32;
    private final long[]  windowTimeMs   = new long[WINDOW_SIZE];
    private final long[]  windowBytes    = new long[WINDOW_SIZE];  // 累计已解码字节
    private int windowHead = 0;
    private int windowCount = 0;
    // EMA 平滑：α=0.2，新值权重低，历史值权重高，抑制逐帧抖动
    private static final float EMA_ALPHA        = 0.2f;
    private long emaSpeedBps = -1;
    // 上次上报给 UI 的速度，避免频繁刷新
    private long lastReportedSpeedBps = -1;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 在 onAttach 中注册 launcher，此时 requireActivity() 可以安全调用
        createFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && activePath != null && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            try (
                                    InputStream istream = new FileInputStream(activePath);
                                    OutputStream ostream = requireActivity().getContentResolver()
                                            .openOutputStream(uri)
                            ) {
                                byte[] buf = new byte[8192];
                                int length;
                                while ((length = istream.read(buf)) > 0) {
                                    ostream.write(buf, 0, length);
                                }
                                ostream.flush();
                            } catch (Exception e) {
                                Log.e(TAG, "failed to write file " + e.toString());
                            }
                        }
                    } else {
                        // 用户取消了保存对话框——从已完成集合中移除该文件，
                        // 使 JNI 层下次可以重新上报它。
                        if (pendingFileName != null) {
                            resetCompletedJNI(pendingFileName);
                            Log.d(TAG, "Save cancelled, removed from completed: " + pendingFileName);
                        }
                    }

                    // 无论如何都清理临时文件并重置状态，以便恢复扫描。
                    if (activePath != null) {
                        try {
                            new File(activePath).delete();
                        } catch (Exception ignored) {
                        }
                        activePath = null;
                    }
                    pendingFileName = null;
                    isSaving = false;
                }
        );

        // 注册相机权限请求 launcher
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        // 权限已授予，启用相机
                        if (MainActivity.nativeLibsLoaded && mOpenCvCameraView != null) {
                            mOpenCvCameraView.setCameraPermissionGranted();
                            mOpenCvCameraView.enableView();
                        }
                    } else {
                        // 权限被拒绝，可提示用户
                        Toast.makeText(getActivity(), R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_receiver, container, false);

        mOpenCvCameraView = view.findViewById(R.id.main_surface);
        mOpenCvCameraView.setVisibility(android.view.SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);

        mModeSwitch = view.findViewById(R.id.mode_switch);
        mModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                modeVal = detectedMode;
            } else {
                modeVal = 0;
            }
        });

        mScanCornersView = view.findViewById(R.id.scan_corners);
        circularProgress = view.findViewById(R.id.circular_progress);

        this.dataPath = requireActivity().getFilesDir().getPath();

        // 将状态栏高度作为顶部内边距推入 header
        View header = view.findViewById(R.id.receiver_header);
        ViewCompat.setOnApplyWindowInsetsListener(header, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), sys.top, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        mModeSwitch.setChecked(false);
        modeVal = 0;
    }

    @Override
    public void onResume() {
        super.onResume();
        requestCameraPermission();
    }

    private void requestCameraPermission() {
        if (ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // 使用新的 Activity Result API 替代已过时的 requestPermissions
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else if (MainActivity.nativeLibsLoaded && mOpenCvCameraView != null) {
            mOpenCvCameraView.setCameraPermissionGranted();
            mOpenCvCameraView.enableView();
        }
    }

    @Override
    public void onPause() {
        shutdownJNI();
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onDestroy() {
        shutdownJNI();
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
    }

    @Override
    public void onCameraViewStopped() {
    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame frame) {
        Mat mat = frame.rgba();
        String res = processImageJNI(mat.getNativeObjAddr(), this.dataPath, this.modeVal, this.deviceRotationDeg);

        Activity activity = getActivity();
        if (activity == null)
            return mat;

        // 解析复合消息格式：[文件名或模式][|~进度:字节数]
        // 示例："/68"、"~42:153600"、"/68|~42:153600"、"filename.bin"、"filename.bin|~100:921600"
        String primary = res;
        int progressPct = -1;
        long decodedBytes = -1;

        int pipeIdx = res.indexOf('|');
        if (pipeIdx >= 0) {
            primary = res.substring(0, pipeIdx);
            String progressPart = res.substring(pipeIdx + 1);
            if (progressPart.startsWith("~")) {
                int colonIdx = progressPart.indexOf(':');
                try {
                    if (colonIdx >= 0) {
                        progressPct  = Integer.parseInt(progressPart.substring(1, colonIdx));
                        decodedBytes = Long.parseLong(progressPart.substring(colonIdx + 1));
                    } else {
                        progressPct = Integer.parseInt(progressPart.substring(1));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } else if (res.startsWith("~")) {
            primary = "";
            int colonIdx = res.indexOf(':');
            try {
                if (colonIdx >= 0) {
                    progressPct  = Integer.parseInt(res.substring(1, colonIdx));
                    decodedBytes = Long.parseLong(res.substring(colonIdx + 1));
                } else {
                    progressPct = Integer.parseInt(res.substring(1));
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // 处理模式检测信号
        if (primary.startsWith("/")) {
            if (primary.length() == 2 && primary.charAt(1) == '4') {
                detectedMode = 4;
            } else if (primary.length() == 3 && primary.charAt(1) == '6' && primary.charAt(2) == '6') {
                detectedMode = 66;
            } else if (primary.length() == 3 && primary.charAt(1) == '6' && primary.charAt(2) == '7') {
                detectedMode = 67;
            } else {
                detectedMode = 68;
            }
            activity.runOnUiThread(() -> {
                if (mModeSwitch != null) {
                    mModeSwitch.setChecked(true);
                    mModeSwitch.setModeVal(detectedMode);
                }
            });
        } else if (!primary.isEmpty() && !this.isSaving) {
            // 文件接收完成：先将进度动画推到 100%，短暂停留后再触发保存
            // 用 final 局部变量捕获 primary，使其可在嵌套 lambda 中引用
            final String completedFileName = primary;
            this.isSaving = true;
            this.activePath = this.dataPath + "/" + completedFileName;
            this.pendingFileName = completedFileName;

            SharedPreferences prefs = activity
                    .getSharedPreferences("cimbar_settings", Activity.MODE_PRIVATE);
            boolean uploadMode = prefs.getBoolean("upload_mode", false);
            String uploadUrl = uploadMode ? prefs.getString("upload_url", "") : "";
            final boolean doUpload = uploadMode && !uploadUrl.isEmpty();
            final String finalUploadUrl = uploadUrl;

            activity.runOnUiThread(() -> {
                // 先把进度推到 100%，动画完成后（400ms）再触发保存
                if (circularProgress != null) {
                    circularProgress.setProgress(100);
                    circularProgress.postDelayed(() -> {
                        resetSpeedWindow();
                        circularProgress.reset();
                        if (doUpload) {
                            uploadFile(this.activePath, finalUploadUrl, completedFileName);
                        } else {
                            promptSaveFile(completedFileName);
                        }
                    }, 500); // 等待进度动画（400ms）+ 短暂停留
                } else {
                    // 无 View 时直接触发
                    if (doUpload) {
                        uploadFile(this.activePath, finalUploadUrl, completedFileName);
                    } else {
                        promptSaveFile(completedFileName);
                    }
                }
            });
        }

        // 若收到进度值，始终更新进度（即使同时有模式检测信号）
        if (progressPct >= 0) {
            final int pct = progressPct;
            // 在 camera 线程记录时间戳和字节数，保证时间精度
            recordProgressSample(decodedBytes);
            final long speedBps = computeSmoothedSpeed();
            activity.runOnUiThread(() -> updateProgressUI(pct, speedBps));
        }

        return mat;
    }

    /**
     * 在 camera 线程调用：将当前时间戳和累计已解码字节数写入滑动窗口。
     * @param totalDecodedBytes JNI 上报的累计字节数，-1 表示未知（跳过记录）
     */
    private void recordProgressSample(long totalDecodedBytes) {
        if (totalDecodedBytes < 0) return;
        windowTimeMs[windowHead]  = System.currentTimeMillis();
        windowBytes[windowHead]   = totalDecodedBytes;
        windowHead = (windowHead + 1) % WINDOW_SIZE;
        if (windowCount < WINDOW_SIZE) windowCount++;
    }

    /**
     * 在 camera 线程调用：用滑动窗口内最早和最新的样本计算平滑速度。
     * 速度 = (最新字节数 - 最早字节数) / 时间差，单位 bytes/s。
     * 返回 -1 表示样本不足或无进展。
     */
    private long computeSmoothedSpeed() {
        if (windowCount < 2) return -1;

        int newestIdx = (windowHead - 1 + WINDOW_SIZE) % WINDOW_SIZE;
        long nowMs    = windowTimeMs[newestIdx];
        long nowBytes = windowBytes[newestIdx];

        // 找到窗口内最早的、距当前时间不超过 SPEED_WINDOW_MS 的样本
        int oldestIdx = -1;
        for (int i = 1; i < windowCount; i++) {
            int idx = (windowHead - 1 - i + WINDOW_SIZE * 2) % WINDOW_SIZE;
            if (nowMs - windowTimeMs[idx] <= SPEED_WINDOW_MS) {
                oldestIdx = idx;
            } else {
                break;
            }
        }
        if (oldestIdx < 0) return -1;

        long deltaMs    = nowMs - windowTimeMs[oldestIdx];
        long deltaBytes = nowBytes - windowBytes[oldestIdx];
        if (deltaMs <= 0 || deltaBytes <= 0) return -1;

        // bytes/s = deltaBytes * 1000 / deltaMs
        long rawSpeed = deltaBytes * 1000L / deltaMs;

        // EMA 平滑：抑制逐帧抖动
        if (emaSpeedBps < 0) {
            emaSpeedBps = rawSpeed;
        } else {
            emaSpeedBps = (long)(EMA_ALPHA * rawSpeed + (1f - EMA_ALPHA) * emaSpeedBps);
        }
        return emaSpeedBps;
    }

    /** 重置滑动窗口（文件完成或重新开始时调用）。 */
    private void resetSpeedWindow() {
        windowHead  = 0;
        windowCount = 0;
        emaSpeedBps = -1;
        lastReportedSpeedBps = -1;
    }

    /**
     * 在 UI 线程调用：更新环形进度和网速显示。
     * @param percent  进度 0-100
     * @param speedBps 字节/秒，-1 表示不更新速度文字
     */
    private void updateProgressUI(int percent, long speedBps) {
        if (circularProgress == null) return;
        circularProgress.setProgress(percent);
        if (speedBps >= 0) {
            // 只在速度变化超过 15% 时才刷新，减少视觉抖动
            long diff = Math.abs(speedBps - lastReportedSpeedBps);
            if (lastReportedSpeedBps < 0 || diff > lastReportedSpeedBps * 5 / 100) {
                circularProgress.setSpeed(speedBps);
                lastReportedSpeedBps = speedBps;
            }
        }
    }

    private void promptSaveFile(String res) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, res);
        createFileLauncher.launch(intent);
    }

    private void uploadFile(String filePath, String uploadUrl, String filename) {
        new Thread(() -> {
            try {
                File file = new File(filePath);
                HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("X-Filename", filename);

                try (InputStream is = new FileInputStream(file);
                     OutputStream os = conn.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        os.write(buf, 0, len);
                    }
                    os.flush();
                }

                int responseCode = conn.getResponseCode();
                conn.disconnect();

                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(),
                            responseCode == 200 ? R.string.upload_success : R.string.upload_failed,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.toString());
                requireActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), R.string.upload_failed, Toast.LENGTH_SHORT).show();
                });
            } finally {
                new File(filePath).delete();
                this.activePath = null;
                this.pendingFileName = null;
                this.isSaving = false;
            }
        }).start();
    }

    public void setDeviceRotation(float rotation) {
        deviceRotationDeg = ((int) rotation % 360 + 360) % 360;
        if (mModeSwitch != null) {
            mModeSwitch.animate()
                    .rotation(rotation)
                    .setDuration(250)
                    .start();
        }
        if (circularProgress != null) {
            circularProgress.animate()
                    .rotation(rotation)
                    .setDuration(250)
                    .start();
        }
        if (mScanCornersView != null) {
            mScanCornersView.setDeviceRotation(rotation);
        }
    }

    private native String processImageJNI(long mat, String path, int modeInt, int rotationDeg);

    private native void shutdownJNI();

    /**
     * 从 native 已完成集合中移除文件名，使其可以被重新上报。
     */
    private native void resetCompletedJNI(String filename);
}