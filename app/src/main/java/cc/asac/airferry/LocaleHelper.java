package cc.asac.airferry;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

public class LocaleHelper {

    private static final String PREFS_NAME = "airferry_settings";
    private static final String KEY_LANGUAGE = "language";

    public static void setLocale(Context context, String languageCode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply();
        if (context instanceof Activity) {
            ((Activity) context).recreate();
        }
    }

    public static Context applyLocale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE);
        String lang = prefs.getString(KEY_LANGUAGE, "");
        if (lang.isEmpty()) {
            lang = systemLanguage(context);
        }

        Locale locale = Locale.forLanguageTag(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    /** 从系统 Configuration 读取当前首选语言，映射到 zh/en */
    public static String systemLanguage(Context context) {
        Locale sys = context.getResources().getConfiguration().getLocales().get(0);
        String lang = sys.getLanguage();
        return lang.startsWith("zh") ? "zh" : "en";
    }
}
