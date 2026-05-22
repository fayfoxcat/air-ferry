package cc.asac.airferry;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import java.security.MessageDigest;

public class ReceiverFragment extends Fragment implements CvCameraViewListener2 {
    private static final String TAG = "ReceiverFragment";

    private String activePath;
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
    private float currentRotation = 0f;

    private AlertDialog confirmDialog;

    // ── 解码指标追踪 ────────────────────────────────────────────────────────────
    private long decodeStartTime = 0;       // 第一次收到有效进度的时间戳(ms)
    private long lastProgressTime = 0;      // 上次进度更新时间
    private long cumulativeDecodedBytes = 0; // 累计已解码字节
    private int lastProgressPct = -1;        // 上次进度百分比
    private int currentDecodeMode = -1;      // 当前解码使用的模式

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
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
                                AppLogger.get(requireContext()).e(TAG, "save local failed: " + e.toString());
                                if (confirmDialog != null && confirmDialog.isShowing()) {
                                    showDialogError(confirmDialog,
                                            getString(R.string.dialog_save_failed, e.getMessage()));
                                }
                                return;
                            }
                        }
                        // Success
                        AppLogger.get(requireContext()).i(TAG, "save local OK: " + pendingFileName);
                        Toast.makeText(getActivity(), R.string.save_success, Toast.LENGTH_SHORT).show();
                        if (confirmDialog != null && confirmDialog.isShowing()) {
                            confirmDialog.dismiss();
                        }
                        cleanupAfterSuccess();
                    } else {
                        // User cancelled the file picker — must always cleanup
                        AppLogger.get(requireContext()).w(TAG, "file picker cancelled for: " + pendingFileName);
                        cleanupAfterCancel();
                    }
                }
        );

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        if (MainActivity.nativeLibsLoaded && mOpenCvCameraView != null) {
                            mOpenCvCameraView.setCameraPermissionGranted();
                            mOpenCvCameraView.enableView();
                        }
                    } else {
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
                AppLogger.get(requireContext()).i(TAG, "Mode switch: manual ON -> mode=" + detectedMode);
            } else {
                modeVal = 0;
                AppLogger.get(requireContext()).i(TAG, "Mode switch: manual OFF -> auto-detect");
            }
        });

        mScanCornersView = view.findViewById(R.id.scan_corners);
        circularProgress = view.findViewById(R.id.circular_progress);

        this.dataPath = requireActivity().getFilesDir().getPath();

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
            AppLogger.get(requireContext()).w(TAG, "Camera permission not granted, requesting...");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else if (MainActivity.nativeLibsLoaded && mOpenCvCameraView != null) {
            mOpenCvCameraView.setCameraPermissionGranted();
            mOpenCvCameraView.enableView();
        }
    }

    @Override
    public void onPause() {
        AppLogger.get(requireContext()).i(TAG, "Receiver paused, shutting down decoder");
        dismissConfirmDialog();
        shutdownJNI();
        super.onPause();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onDestroy() {
        AppLogger.get(requireContext()).i(TAG, "Receiver destroyed");
        dismissConfirmDialog();
        shutdownJNI();
        if (activePath != null) {
            new File(activePath).delete();
            activePath = null;
        }
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        AppLogger.get(requireContext()).i(TAG, "Camera started: " + width + "x" + height);
        resetDecodeMetrics();
    }

    @Override
    public void onCameraViewStopped() {
        AppLogger.get(requireContext()).i(TAG, "Camera stopped");
    }

    @Override
    public Mat onCameraFrame(CvCameraViewFrame frame) {
        Mat mat = frame.rgba();
        String res = processImageJNI(mat.getNativeObjAddr(), this.dataPath, this.modeVal, this.deviceRotationDeg);

        Activity activity = getActivity();
        if (activity == null)
            return mat;

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

        // ── 解码指标追踪 ───────────────────────────────────────────────────────────
        if (progressPct >= 0) {
            long nowMs = System.currentTimeMillis();
            // 首次收到有效进度 → 记录解码开始时间
            if (decodeStartTime == 0) {
                decodeStartTime = nowMs;
                int effectiveMode = (modeVal != 0) ? modeVal : detectedMode;
                currentDecodeMode = effectiveMode;
                AppLogger.get(requireContext()).i(TAG, "Decode started: mode="
                        + effectiveMode + " at " + AppLogger.get(requireContext()).now());
            }
            lastProgressTime = nowMs;
            if (decodedBytes > 0) {
                cumulativeDecodedBytes = decodedBytes;
            }
            lastProgressPct = progressPct;
        }

        // 处理模式检测信号
        if (primary.startsWith("/")) {
            int prevMode = detectedMode;
            if (primary.length() == 2 && primary.charAt(1) == '4') {
                detectedMode = 4;
            } else if (primary.length() == 3 && primary.charAt(1) == '6' && primary.charAt(2) == '6') {
                detectedMode = 66;
            } else if (primary.length() == 3 && primary.charAt(1) == '6' && primary.charAt(2) == '7') {
                detectedMode = 67;
            } else {
                detectedMode = 68;
            }
            if (prevMode != detectedMode) {
                AppLogger.get(requireContext()).i(TAG, "Mode detected: " + detectedMode);
            }
            activity.runOnUiThread(() -> {
                if (mModeSwitch != null) {
                    mModeSwitch.setChecked(true);
                    mModeSwitch.setModeVal(detectedMode);
                }
            });
        } else if (!primary.isEmpty() && !this.isSaving) {
            final String completedFileName = primary;
            this.isSaving = true;
            this.activePath = this.dataPath + "/" + completedFileName;
            this.pendingFileName = completedFileName;

            // Persist file to app cache asynchronously (always, regardless of dialog action)
            final File completedFile = new File(this.activePath);
            final long fileSize = completedFile.exists() ? completedFile.length() : 0;
            final long decodeDurationMs = (decodeStartTime > 0) ? (lastProgressTime - decodeStartTime) : 0;
            final Context ctx = activity.getApplicationContext();
            new Thread(() -> {
                // Compute MD5 for deduplication
                String md5 = "";
                try {
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    try (InputStream in = new FileInputStream(completedFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            digest.update(buf, 0, len);
                        }
                    }
                    byte[] hash = digest.digest();
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hash) sb.append(String.format("%02x", b));
                    md5 = sb.toString();
                } catch (Exception e) {
                    AppLogger.get(ctx).e(TAG, "Failed to compute MD5: " + e.getMessage());
                }

                // Skip if duplicate
                if (!md5.isEmpty() && FileRecordManager.get(ctx).hasMd5(md5)) {
                    AppLogger.get(ctx).i(TAG, "Duplicate file skipped (MD5=" + md5 + "): " + completedFileName);
                    return;
                }

                String persistentPath = "";
                try {
                    File cacheDir = new File(activity.getExternalCacheDir(), "received");
                    cacheDir.mkdirs();
                    File destFile = new File(cacheDir, completedFileName);
                    try (InputStream in = new FileInputStream(completedFile);
                         OutputStream out = new java.io.FileOutputStream(destFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                        out.flush();
                    }
                    persistentPath = destFile.getAbsolutePath();
                } catch (Exception e) {
                    AppLogger.get(ctx).e(TAG, "Failed to persist file: " + e.getMessage());
                }
                FileRecordManager.get(ctx).addRecord(
                        completedFileName, fileSize, System.currentTimeMillis(),
                        decodeDurationMs, persistentPath, md5);
            }).start();

            // ── 记录解码完成指标 ───────────────────────────────────────────────────────
            AppLogger logger = AppLogger.get(requireContext());
            logger.i(TAG, "=== Decode complete ===");
            logger.i(TAG, "  File: " + completedFileName);
            logger.i(TAG, "  Size: " + AppLogger.formatSize(fileSize)
                    + " (" + fileSize + " bytes)");
            logger.i(TAG, "  Mode: " + (currentDecodeMode > 0 ? currentDecodeMode : detectedMode));
            logger.i(TAG, "  Duration: " + AppLogger.formatDuration(decodeDurationMs));
            logger.i(TAG, "  Avg speed: " + AppLogger.formatSpeed(
                    (decodeDurationMs > 0 && fileSize > 0) ? (fileSize * 1000 / decodeDurationMs) : 0));
            logger.i(TAG, "  Start time: " + (decodeStartTime > 0
                    ? logger.now() : "unknown"));
            logger.i(TAG, "  End time: " + logger.now());
            logger.i(TAG, "  Final progress: " + (lastProgressPct >= 0 ? lastProgressPct + "%" : "N/A"));
            logger.i(TAG, "  Decoded bytes (progress): "
                    + (cumulativeDecodedBytes > 0
                    ? AppLogger.formatSize(cumulativeDecodedBytes) : "N/A"));


            SharedPreferences prefs = activity
                    .getSharedPreferences("airferry_settings", Activity.MODE_PRIVATE);
            boolean doSave   = prefs.getBoolean("save_local",   true);
            boolean doUpload = prefs.getBoolean("upload_cloud", false);
            boolean doShare  = prefs.getBoolean("share_social", false);
            String uploadUrl = prefs.getString("upload_url", "");
            String uploadHeaders = prefs.getString("upload_headers", "");
            final boolean canUpload = doUpload && !uploadUrl.isEmpty();
            final String finalUploadUrl = uploadUrl;
            final String finalUploadHeaders = uploadHeaders;
            final boolean finalDoSave  = doSave;
            final boolean finalDoShare = doShare;

            activity.runOnUiThread(() -> {
                if (circularProgress != null) {
                    circularProgress.setProgress(100);
                    circularProgress.postDelayed(() -> {
                        if (!isSaving) return;
                        circularProgress.reset();
                        showConfirmDialog(completedFileName, finalDoSave, canUpload, finalUploadUrl, finalUploadHeaders, finalDoShare);
                    }, 500);
                } else {
                    if (!isSaving) return;
                    showConfirmDialog(completedFileName, finalDoSave, canUpload, finalUploadUrl, finalUploadHeaders, finalDoShare);
                }
            });
        }

        if (progressPct >= 0) {
            final int pct = progressPct;
            activity.runOnUiThread(() -> {
                if (circularProgress != null) {
                    circularProgress.setProgress(pct);
                }
            });
        }

        return mat;
    }

    // ── Confirm dialog ──────────────────────────────────────────────────────────

    private void showConfirmDialog(String filename, boolean doSave, boolean doUpload,
                                   String uploadUrl, String uploadHeaders, boolean doShare) {
        Activity a = getActivity();
        if (a == null || a.isFinishing()) return;

        AppLogger.get(a).i(TAG, "showConfirmDialog: " + filename
                + " save=" + doSave + " upload=" + doUpload + " share=" + doShare);

        View dialogView = LayoutInflater.from(a).inflate(R.layout.dialog_confirm_file, null);
        TextView tvTitle   = dialogView.findViewById(R.id.dialog_title);
        TextView tvMessage = dialogView.findViewById(R.id.dialog_message);
        TextView btnClose  = dialogView.findViewById(R.id.btn_close);
        TextView btnSave   = dialogView.findViewById(R.id.btn_save);
        TextView btnUpload = dialogView.findViewById(R.id.btn_upload);
        TextView btnShare  = dialogView.findViewById(R.id.btn_share);
        Button btnCopyErr  = dialogView.findViewById(R.id.btn_copy_error);

        tvTitle.setText(getString(R.string.confirm_file_title));
        tvMessage.setText(getString(R.string.confirm_file_msg, filename));

        if (doSave)   btnSave.setVisibility(View.VISIBLE);
        if (doUpload) btnUpload.setVisibility(View.VISIBLE);
        if (doShare)  btnShare.setVisibility(View.VISIBLE);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialog.show();

        // 关闭：清空缓存，可重新识别
        btnClose.setOnClickListener(v -> {
            AppLogger.get(a).i(TAG, "dialog closed: " + filename);
            dialog.dismiss();
            cleanupAfterCancel();
        });

        // 保存到本地
        btnSave.setOnClickListener(v -> executeSaveLocal(filename, dialog));

        // 上传到指定 URL
        btnUpload.setOnClickListener(v ->
                executeUpload(filename, uploadUrl, uploadHeaders, dialog, tvMessage, btnCopyErr));

        // 分享到 App
        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            executeShare(filename, a);
        });

        btnCopyErr.setOnClickListener(v -> {
            CharSequence errText = tvMessage.getText();
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("error", errText));
                Toast.makeText(a, android.R.string.copy, Toast.LENGTH_SHORT).show();
            }
        });

        confirmDialog = dialog;
    }

    private void showDialogError(AlertDialog dialog, View dialogView, String error) {
        TextView tvMessage = dialogView.findViewById(R.id.dialog_message);
        Button btnCopyErr  = dialogView.findViewById(R.id.btn_copy_error);
        TextView btnUpload = dialogView.findViewById(R.id.btn_upload);
        tvMessage.setText(error);
        tvMessage.setTextColor(tvMessage.getContext().getColor(R.color.colorError));
        btnCopyErr.setVisibility(View.VISIBLE);
        if (btnUpload != null) btnUpload.setEnabled(true);
    }

    private void showDialogError(AlertDialog dialog, String error) {
        if (dialog.getWindow() == null) return;
        View root = dialog.getWindow().getDecorView().findViewById(R.id.dialog_message);
        if (root instanceof TextView) {
            ((TextView) root).setText(error);
            ((TextView) root).setTextColor(root.getContext().getColor(R.color.colorError));
        }
        View copyBtn = dialog.getWindow().getDecorView().findViewById(R.id.btn_copy_error);
        if (copyBtn != null) copyBtn.setVisibility(View.VISIBLE);
        View uploadBtn = dialog.getWindow().getDecorView().findViewById(R.id.btn_upload);
        if (uploadBtn != null) uploadBtn.setEnabled(true);
    }

    private void dismissConfirmDialog() {
        if (confirmDialog != null && confirmDialog.isShowing()) {
            confirmDialog.dismiss();
        }
        confirmDialog = null;
    }

    private void cleanupAfterSuccess() {
        AppLogger.get(requireContext()).i(TAG, "Decode result: SUCCESS for " + pendingFileName);
        confirmDialog = null;
        if (activePath != null) {
            new File(activePath).delete();
            activePath = null;
        }
        pendingFileName = null;
        isSaving = false;
        resetDecodeMetrics();
        // 完全重置C++解码器状态，确保新的喷泉解码流可以从零开始接收新文件
        shutdownJNI();
        // 重置UI，准备继续扫描
        if (circularProgress != null) {
            circularProgress.reset();
        }
        mModeSwitch.setChecked(false);
        modeVal = 0;
    }

    private void cleanupAfterCancel() {
        AppLogger.get(requireContext()).w(TAG, "Decode result: CANCELLED for " + pendingFileName);
        if (activePath != null) {
            new File(activePath).delete();
            activePath = null;
        }
        pendingFileName = null;
        confirmDialog = null;
        isSaving = false;
        resetDecodeMetrics();
        // 完全重置C++解码器状态，确保新的喷泉解码流可以从零开始接收新文件
        shutdownJNI();
        // 重置UI，准备继续扫描
        if (circularProgress != null) {
            circularProgress.reset();
        }
        mModeSwitch.setChecked(false);
        modeVal = 0;
    }

    // ── Save / Upload executors ─────────────────────────────────────────────────

    private void executeSaveLocal(String filename, AlertDialog dialog) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        createFileLauncher.launch(intent);
    }

    private void executeShare(String filename, Activity a) {
        new Thread(() -> {
            try {
                // 微信等 App 不接受 files-path 下的 content URI，需复制到外部缓存目录
                File src = new File(this.activePath);
                File shareDir = new File(a.getExternalCacheDir(), "share");
                shareDir.mkdirs();
                File dst = new File(shareDir, filename);
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new java.io.FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                }

                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        a,
                        a.getPackageName() + ".fileprovider",
                        dst);

                String mime = getMimeType(filename);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType(mime);
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, filename);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                AppLogger.get(a).i(TAG, "share launched: " + filename + " mime=" + mime);
                a.runOnUiThread(() -> {
                    a.startActivity(Intent.createChooser(shareIntent, filename));
                    cleanupAfterSuccess();
                });
            } catch (Exception e) {
                AppLogger.get(a).e(TAG, "share failed: " + e.getMessage());
                a.runOnUiThread(() -> {
                    Toast.makeText(a, getString(R.string.dialog_save_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    cleanupAfterCancel();
                });
            }
        }).start();
    }

    private String getMimeType(String filename) {
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()
                : "";
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png":  return "image/png";
            case "gif":  return "image/gif";
            case "mp4":  return "video/mp4";
            case "mp3":  return "audio/mpeg";
            case "pdf":  return "application/pdf";
            case "zip":  return "application/zip";
            case "txt":  return "text/plain";
            default:     return "*/*";
        }
    }

    private void executeUpload(String filename, String uploadUrl, String uploadHeaders,
                               AlertDialog dialog, TextView tvMessage, Button btnCopyErr) {
        String filePath = this.activePath;
        AppLogger.get(requireContext()).i(TAG, "upload start: " + filename + " -> " + uploadUrl);
        tvMessage.setText(getString(R.string.uploading));
        tvMessage.setTextColor(tvMessage.getContext().getColor(R.color.colorOnSurface));
        btnCopyErr.setVisibility(View.GONE);
        dialog.getWindow().getDecorView().findViewById(R.id.btn_upload).setEnabled(false);

        new Thread(() -> {
            try {
                File file = new File(filePath);
                HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setRequestProperty("X-Filename", filename);
                // 解析可选自定义 headers（格式：Key:Value，多条用分号分隔）
                if (!uploadHeaders.isEmpty()) {
                    String[] pairs = uploadHeaders.split(";");
                    for (String pair : pairs) {
                        int colon = pair.indexOf(':');
                        if (colon > 0) {
                            String key = pair.substring(0, colon).trim();
                            String val = pair.substring(colon + 1).trim();
                            if (!key.isEmpty()) {
                                conn.setRequestProperty(key, val);
                            }
                        }
                    }
                }

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

                Activity a = getActivity();
                if (a == null || !dialog.isShowing()) return;
                a.runOnUiThread(() -> {
                    if (!dialog.isShowing()) return;
                    if (responseCode == 200) {
                        AppLogger.get(a).i(TAG, "upload OK: " + filename);
                        dialog.dismiss();
                        Toast.makeText(a, getString(R.string.upload_success), Toast.LENGTH_SHORT).show();
                        cleanupAfterSuccess();
                    } else {
                        String err = "HTTP " + responseCode;
                        AppLogger.get(a).e(TAG, "upload failed: " + filename + " " + err);
                        showDialogError(dialog, tvMessage.getRootView(),
                                getString(R.string.dialog_upload_failed, err));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.toString());
                try {
                    Activity a = getActivity();
                    if (a == null || !dialog.isShowing()) return;
                    AppLogger.get(a).e(TAG, "upload exception: " + filename + " " + e.toString());
                    a.runOnUiThread(() -> {
                        if (!dialog.isShowing()) return;
                        showDialogError(dialog, tvMessage.getRootView(),
                                getString(R.string.dialog_upload_failed, e.getMessage()));
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ── Device rotation ─────────────────────────────────────────────────────────

    public void setDeviceRotation(float rotation) {
        deviceRotationDeg = ((int) rotation % 360 + 360) % 360;
        currentRotation = rotation;
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

    // ── JNI ─────────────────────────────────────────────────────────────────────

    private native String processImageJNI(long mat, String path, int modeInt, int rotationDeg);

    private native void shutdownJNI();

    // ── Decode metrics helpers ──────────────────────────────────────────────────

    private void resetDecodeMetrics() {
        decodeStartTime = 0;
        lastProgressTime = 0;
        cumulativeDecodedBytes = 0;
        lastProgressPct = -1;
        currentDecodeMode = -1;
    }
}
