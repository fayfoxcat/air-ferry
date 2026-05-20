package cc.asac.cimbar;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREFS_NAME = "cimbar_settings";
    private static final String KEY_LANGUAGE = "language";

    public static void setLocale(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply();
        // 语言设置将在下次 Activity 创建时通过 attachBaseContext → applyLocale 生效。
        if (context instanceof Activity) {
            ((Activity) context).recreate();
        }
    }

    /**
     * 返回一个已应用已保存语言设置的新 Context。
     * 在 Activity.attachBaseContext() 中调用，用于包装基础 Context。
     * 使用 createConfigurationContext()——现代的非废弃方式。
     */
    public static Context applyLocale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        String lang = prefs.getString(KEY_LANGUAGE, "zh");

        Locale locale = Locale.forLanguageTag(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }
}
