package cc.asac.cimbar;

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
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    private static final String PREFS_NAME = "cimbar_settings";

    private RadioGroup rgReceiveMode;
    private EditText etUploadUrl;

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
            if (checkedId == R.id.rb_zh) {
                LocaleHelper.setLocale(requireContext(), "zh");
            } else if (checkedId == R.id.rb_en) {
                LocaleHelper.setLocale(requireContext(), "en");
            }
            restartActivity();
        });

        // Receive mode
        rgReceiveMode = view.findViewById(R.id.rg_receive_mode);
        etUploadUrl = view.findViewById(R.id.et_upload_url);

        boolean uploadMode = prefs.getBoolean("upload_mode", false);
        rgReceiveMode.check(uploadMode ? R.id.rb_upload_url : R.id.rb_save_local);
        if (uploadMode) {
            etUploadUrl.setVisibility(View.VISIBLE);
            etUploadUrl.setText(prefs.getString("upload_url", ""));
        }

        rgReceiveMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_upload_url) {
                etUploadUrl.setVisibility(View.VISIBLE);
                etUploadUrl.requestFocus();
            } else {
                etUploadUrl.setVisibility(View.GONE);
            }
            saveReceiveSettings();
        });

        etUploadUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveReceiveSettings();
        });

        // Open source link
        TextView tvOpenSource = view.findViewById(R.id.tv_opensource_link);
        tvOpenSource.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/fayfoxcat/cfc"));
            startActivity(intent);
        });

        // Version info
        TextView tvVersion = view.findViewById(R.id.tv_version);
        try {
            PackageInfo pInfo = requireActivity().getPackageManager()
                    .getPackageInfo(requireActivity().getPackageName(), 0);
            tvVersion.setText(getString(R.string.version_format, pInfo.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            tvVersion.setText("");
        }

        // About text
        TextView tvAbout = view.findViewById(R.id.tv_about);
        tvAbout.setText(getString(R.string.about_text));

        return view;
    }

    private void saveReceiveSettings() {
        SharedPreferences prefs = requireActivity()
                .getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        boolean uploadMode = rgReceiveMode.getCheckedRadioButtonId() == R.id.rb_upload_url;
        prefs.edit()
                .putBoolean("upload_mode", uploadMode)
                .putString("upload_url",
                        uploadMode ? etUploadUrl.getText().toString().trim() : "")
                .apply();
    }

    private void restartActivity() {
        Intent intent = requireActivity().getIntent();
        requireActivity().finish();
        startActivity(intent);
        // overridePendingTransition is deprecated in API 34; use the new API when available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requireActivity().overrideActivityTransition(
                    Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            requireActivity().overridePendingTransition(0, 0);
        }
    }
}
