package cc.asac.airferry;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLogger {
    private static final String TAG = "AppLogger";
    private static final String LOG_FILE = "airferry_run.log";
    private static final long MAX_BYTES = 2 * 1024 * 1024; // 2 MB cap
    private static final int TRIM_CHECK_INTERVAL = 100;

    private static AppLogger instance;
    private final File logFile;
    private int writeCount = 0;

    private static final ThreadLocal<SimpleDateFormat> dateFmtTL =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US));

    private AppLogger(Context ctx) {
        logFile = new File(ctx.getFilesDir(), LOG_FILE);
        trimIfNeeded();
    }

    public static synchronized AppLogger get(Context ctx) {
        if (instance == null)
            instance = new AppLogger(ctx.getApplicationContext());
        return instance;
    }

    public static File getLogFile(Context ctx) {
        return new File(ctx.getFilesDir(), LOG_FILE);
    }

    public void i(String tag, String msg) {
        write("I", tag, msg);
        Log.i(tag, msg);
    }

    public void e(String tag, String msg) {
        write("E", tag, msg);
        Log.e(tag, msg);
    }

    public void w(String tag, String msg) {
        write("W", tag, msg);
        Log.w(tag, msg);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    public static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        long remainMs = ms % 1000;
        if (sec < 60) return sec + "." + String.format(Locale.US, "%03d", remainMs) + "s";
        long min = sec / 60;
        sec = sec % 60;
        return min + "m" + sec + "s";
    }

    public static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        return String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }

    public String now() {
        return dateFmtTL.get().format(new Date());
    }

    private synchronized void write(String level, String tag, String msg) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true))) {
            bw.write(dateFmtTL.get().format(new Date()) + " " + level + "/" + tag + ": " + msg);
            bw.newLine();
        } catch (IOException e) {
            Log.e(TAG, "write failed: " + e.getMessage());
        }
        if (++writeCount % TRIM_CHECK_INTERVAL == 0) {
            trimIfNeeded();
        }
    }

    private synchronized void trimIfNeeded() {
        if (!logFile.exists() || logFile.length() <= MAX_BYTES) return;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(logFile.toPath());
            int start = (int) (bytes.length / 2);
            while (start < bytes.length && bytes[start] != '\n') start++;
            if (start >= bytes.length) {
                // 后半部分无换行符，直接从中间截断
                start = (int) (bytes.length / 2);
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(logFile, false)) {
                fos.write(bytes, start, bytes.length - start);
            }
        } catch (IOException e) {
            Log.e(TAG, "trim failed: " + e.getMessage());
        }
    }
}
