package cc.asac.airferry;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";
    private static final String PREFS_NAME = "airferry_settings";
    private static final String GITHUB_RELEASES_API =
            "https://api.github.com/repos/sz-afa/air-ferry/releases/latest";

    private CheckBox cbSaveLocal;
    private CheckBox cbUploadCloud;
    private CheckBox cbShareSocial;
    private EditText etUploadUrl;
    private EditText etUploadHeaders;
    private LinearLayout uploadConfigArea;
    private Button btnTestUpload;
    private Button btnSaveUpload;
    private ActivityResultLauncher<Intent> saveLogLauncher;

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        saveLogLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != Activity.RESULT_OK) return;
                    if (result.getData() == null) return;
                    Uri uri = result.getData().getData();
                    if (uri == null) return;
                    File logFile = AppLogger.getLogFile(requireContext());
                    if (!logFile.exists()) return;
                    try (InputStream is = new FileInputStream(logFile);
                         OutputStream os = requireActivity().getContentResolver().openOutputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                        os.flush();
                        Toast.makeText(getContext(), R.string.settings_log_export, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 将状态栏高度动态注入标题的顶部内边距，避免标题挤向屏幕顶部
        TextView tvTitle = view.findViewById(R.id.tv_settings_title);
        ViewCompat.setOnApplyWindowInsetsListener(tvTitle, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), sys.top + 20, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);

        // Language
        RadioGroup rgLanguage = view.findViewById(R.id.rg_language);
        String lang = prefs.getString("language", "zh");
        rgLanguage.check("zh".equals(lang) ? R.id.rb_zh : R.id.rb_en);
        rgLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            String newLang;
            if (checkedId == R.id.rb_zh) {
                newLang = "zh";
            } else if (checkedId == R.id.rb_en) {
                newLang = "en";
            } else {
                newLang = lang;
            }
            AppLogger.get(requireContext()).i(TAG, "Settings changed: language "
                    + lang + " -> " + newLang);
            LocaleHelper.setLocale(requireContext(), newLang);
            restartActivity();
        });

        // Receive mode
        cbSaveLocal      = view.findViewById(R.id.cb_save_local);
        cbUploadCloud    = view.findViewById(R.id.cb_upload_cloud);
        cbShareSocial    = view.findViewById(R.id.cb_share_social);
        etUploadUrl      = view.findViewById(R.id.et_upload_url);
        etUploadHeaders  = view.findViewById(R.id.et_upload_headers);
        uploadConfigArea = view.findViewById(R.id.upload_config_area);
        btnTestUpload    = view.findViewById(R.id.btn_test_upload);
        btnSaveUpload    = view.findViewById(R.id.btn_save_upload);

        cbSaveLocal.setChecked(prefs.getBoolean("save_local", true));
        cbUploadCloud.setChecked(prefs.getBoolean("upload_cloud", false));
        cbShareSocial.setChecked(prefs.getBoolean("share_social", false));

        if (cbUploadCloud.isChecked()) {
            uploadConfigArea.setVisibility(View.VISIBLE);
            etUploadUrl.setText(prefs.getString("upload_url", ""));
            etUploadHeaders.setText(prefs.getString("upload_headers", ""));
        }

        cbSaveLocal.setOnCheckedChangeListener((btn, checked) -> {
            AppLogger.get(requireContext()).i(TAG, "Settings changed: save_local "
                    + !checked + " -> " + checked);
            saveStorageSettings();
        });

        cbUploadCloud.setOnCheckedChangeListener((btn, checked) -> {
            AppLogger.get(requireContext()).i(TAG, "Settings changed: upload_cloud "
                    + !checked + " -> " + checked);
            if (checked) {
                uploadConfigArea.setVisibility(View.VISIBLE);
                etUploadUrl.requestFocus();
            } else {
                uploadConfigArea.setVisibility(View.GONE);
            }
            saveStorageSettings();
        });

        cbShareSocial.setOnCheckedChangeListener((btn, checked) -> {
            AppLogger.get(requireContext()).i(TAG, "Settings changed: share_social "
                    + !checked + " -> " + checked);
            saveStorageSettings();
        });

        // URL 输入框：仅做校验提示，不自动保存
        etUploadUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String url = etUploadUrl.getText().toString().trim();
                if (!url.isEmpty() && !isValidUrl(url)) {
                    etUploadUrl.setError(getString(R.string.error_invalid_url));
                } else {
                    etUploadUrl.setError(null);
                }
            }
        });

        // 保存按钮
        btnSaveUpload.setOnClickListener(v -> {
            String url = etUploadUrl.getText().toString().trim();
            if (!url.isEmpty() && !isValidUrl(url)) {
                etUploadUrl.setError(getString(R.string.error_invalid_url));
                return;
            }
            saveStorageSettings();
            Toast.makeText(requireContext(), getString(R.string.upload_saved), Toast.LENGTH_SHORT).show();
        });

        // 测试按钮
        btnTestUpload.setOnClickListener(v -> {
            String url = etUploadUrl.getText().toString().trim();
            if (url.isEmpty() || !isValidUrl(url)) {
                etUploadUrl.setError(getString(R.string.error_invalid_url));
                return;
            }
            String headers = etUploadHeaders.getText().toString().trim();
            btnTestUpload.setEnabled(false);
            new Thread(() -> {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/octet-stream");
                    conn.setRequestProperty("X-Filename", "test.ping");
                    // 解析自定义 headers
                    if (!headers.isEmpty()) {
                        String[] pairs = headers.split(";");
                        for (String pair : pairs) {
                            int colon = pair.indexOf(':');
                            if (colon > 0) {
                                String key = pair.substring(0, colon).trim();
                                String val = pair.substring(colon + 1).trim();
                                if (!key.isEmpty()) conn.setRequestProperty(key, val);
                            }
                        }
                    }
                    // 发送空 body 测试连通性
                    conn.getOutputStream().close();
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    Activity a = getActivity();
                    if (a != null) a.runOnUiThread(() -> {
                        btnTestUpload.setEnabled(true);
                        if (code >= 200 && code < 400) {
                            AppLogger.get(a).i(TAG, "Upload test OK: HTTP " + code);
                            Toast.makeText(a, getString(R.string.upload_test_ok, code), Toast.LENGTH_SHORT).show();
                        } else {
                            AppLogger.get(a).e(TAG, "Upload test fail: HTTP " + code);
                            Toast.makeText(a, getString(R.string.upload_test_fail, code), Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    Activity a = getActivity();
                    if (a != null) a.runOnUiThread(() -> {
                        btnTestUpload.setEnabled(true);
                        AppLogger.get(a).e(TAG, "Upload test error: " + e.getMessage());
                        Toast.makeText(a, getString(R.string.upload_test_error, e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        // View log
        Button btnViewLog = view.findViewById(R.id.btn_view_log);
        btnViewLog.setOnClickListener(v -> showLogDialog());

        // Clear log
        Button btnClearLog = view.findViewById(R.id.btn_clear_log);
        btnClearLog.setOnClickListener(v -> {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_log_clear)
                    .setMessage(R.string.settings_log_clear_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        File logFile = AppLogger.getLogFile(requireContext());
                        if (logFile.exists()) logFile.delete();
                        Toast.makeText(getContext(), R.string.settings_log_clear, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        // Export log
        Button btnExportLog = view.findViewById(R.id.btn_export_log);
        btnExportLog.setOnClickListener(v -> {
            File logFile = AppLogger.getLogFile(requireContext());
            if (!logFile.exists() || logFile.length() == 0) {
                Toast.makeText(getContext(), R.string.log_export_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "airferry_run.log");
            saveLogLauncher.launch(intent);
        });

        // About: show version
        TextView tvVersion = view.findViewById(R.id.tv_version);
        try {
            PackageInfo pi = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            tvVersion.setText(getString(R.string.settings_about_version) + "  " +
                    getString(R.string.version_format, pi.versionName));
        } catch (PackageManager.NameNotFoundException ignored) {}

        // Open source
        Button btnOpensource = view.findViewById(R.id.btn_opensource);
        btnOpensource.setOnClickListener(v -> {
            String url = getString(R.string.settings_opensource_url);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });

        // Check update
        TextView tvUpdateStatus = view.findViewById(R.id.tv_update_status);
        Button btnCheckUpdate = view.findViewById(R.id.btn_check_update);
        Button btnDownloadUpdate = view.findViewById(R.id.btn_download_update);
        btnCheckUpdate.setOnClickListener(v -> {
            tvUpdateStatus.setVisibility(View.VISIBLE);
            tvUpdateStatus.setText(R.string.update_checking);
            btnCheckUpdate.setEnabled(false);
            btnDownloadUpdate.setVisibility(View.GONE);
            new Thread(() -> {
                String latestTag = null;
                String downloadUrl = null;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_RELEASES_API).openConnection();
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                        }
                        JSONObject json = new JSONObject(sb.toString());
                        latestTag = json.optString("tag_name", "");
                        downloadUrl = json.optString("html_url", "");
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    AppLogger.get(requireContext()).e(TAG, "Update check error: " + e.getMessage());
                }
                final String tag = latestTag;
                final String dlUrl = downloadUrl;
                Activity a = getActivity();
                if (a == null) return;
                a.runOnUiThread(() -> {
                    btnCheckUpdate.setEnabled(true);
                    if (tag == null || tag.isEmpty()) {
                        tvUpdateStatus.setText(R.string.update_failed);
                        return;
                    }
                    String currentVersion = "v0.0.0";
                    try {
                        PackageInfo pi = requireContext().getPackageManager()
                                .getPackageInfo(requireContext().getPackageName(), 0);
                        currentVersion = "v" + pi.versionName;
                    } catch (PackageManager.NameNotFoundException ignored) {}
                    if (tag.equals(currentVersion)) {
                        tvUpdateStatus.setText(R.string.update_latest);
                    } else {
                        tvUpdateStatus.setText(getString(R.string.update_available, tag));
                        if (dlUrl != null && !dlUrl.isEmpty()) {
                            btnDownloadUpdate.setVisibility(View.VISIBLE);
                            btnDownloadUpdate.setTag(dlUrl);
                        }
                    }
                });
            }).start();
        });
        btnDownloadUpdate.setOnClickListener(v -> {
            Object tag = v.getTag();
            if (tag instanceof String) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse((String) tag)));
            }
        });

        return view;
    }

    private void saveStorageSettings() {
        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        boolean oldCloud = prefs.getBoolean("upload_cloud", false);
        String oldUrl = prefs.getString("upload_url", "");
        String oldHeaders = prefs.getString("upload_headers", "");
        boolean cloud = cbUploadCloud.isChecked();
        String url = cloud ? etUploadUrl.getText().toString().trim() : "";
        String headers = cloud ? etUploadHeaders.getText().toString().trim() : "";
        if (cloud && !url.equals(oldUrl) && !url.isEmpty()) {
            AppLogger.get(requireContext()).i(TAG, "Settings changed: upload_url "
                    + "'" + oldUrl + "' -> '" + url + "'");
        }
        if (cloud && !headers.equals(oldHeaders)) {
            AppLogger.get(requireContext()).i(TAG, "Settings changed: upload_headers "
                    + "'" + oldHeaders + "' -> '" + headers + "'");
        }
        prefs.edit()
                .putBoolean("save_local",    cbSaveLocal.isChecked())
                .putBoolean("upload_cloud",  cloud)
                .putBoolean("share_social",  cbShareSocial.isChecked())
                .putString("upload_url",     url)
                .putString("upload_headers", headers)
                .apply();
    }

    private boolean isValidUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void showLogDialog() {
        File logFile = AppLogger.getLogFile(requireContext());
        if (!logFile.exists() || logFile.length() == 0) {
            Toast.makeText(getContext(), R.string.log_export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new java.io.FileReader(logFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }

            View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_log_view, null);
            TextView tvTitle = dialogView.findViewById(R.id.dialog_log_title);
            TextView tvContent = dialogView.findViewById(R.id.tv_log_content);
            TextView btnClose = dialogView.findViewById(R.id.btn_log_close);

            tvTitle.setText(getString(R.string.settings_log));
            tvContent.setText(sb.toString());

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setView(dialogView)
                    .setCancelable(true)
                    .create();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            btnClose.setOnClickListener(v2 -> dialog.dismiss());

            dialog.show();
        } catch (Exception e) {
            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void restartActivity() {
        Intent intent = requireActivity().getIntent();
        requireActivity().finish();
        startActivity(intent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requireActivity().overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            requireActivity().overridePendingTransition(0, 0);
        }
    }
}