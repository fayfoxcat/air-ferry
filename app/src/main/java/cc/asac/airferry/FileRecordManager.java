package cc.asac.airferry;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class FileRecordManager {
    private static final String FILE_NAME = "file_records.json";
    private static FileRecordManager instance;
    private final File recordsFile;
    private final List<FileRecord> records = new ArrayList<>();

    private FileRecordManager(Context ctx) {
        recordsFile = new File(ctx.getFilesDir(), FILE_NAME);
        loadFromFile();
    }

    public static synchronized FileRecordManager get(Context ctx) {
        if (instance == null) {
            instance = new FileRecordManager(ctx.getApplicationContext());
        }
        return instance;
    }

    private synchronized void loadFromFile() {
        records.clear();
        if (!recordsFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(recordsFile), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                records.add(FileRecord.fromJson(arr.getJSONObject(i)));
            }
        } catch (IOException | JSONException e) {
            android.util.Log.e("FileRecordManager", "Failed to load records: " + e.getMessage());
        }
    }

    private synchronized void saveToFile() {
        JSONArray arr = new JSONArray();
        for (FileRecord r : records) {
            arr.put(r.toJson());
        }
        try (FileWriter writer = new FileWriter(recordsFile)) {
            writer.write(arr.toString());
            writer.flush();
        } catch (IOException e) {
            android.util.Log.e("FileRecordManager", "Failed to save records: " + e.getMessage());
        }
    }

    public synchronized void addRecord(String fileName, long fileSize, long timestamp,
                                        long decodeDurationMs, String filePath, String md5) {
        records.add(0, new FileRecord(fileName, fileSize, timestamp, decodeDurationMs, filePath, md5));
        saveToFile();
    }

    public synchronized boolean hasMd5(String md5) {
        if (md5 == null || md5.isEmpty()) return false;
        for (FileRecord r : records) {
            if (md5.equals(r.md5)) return true;
        }
        return false;
    }

    public synchronized void deleteRecord(int index) {
        if (index < 0 || index >= records.size()) return;
        FileRecord r = records.get(index);
        if (r.filePath != null && !r.filePath.isEmpty()) {
            new File(r.filePath).delete();
        }
        records.remove(index);
        saveToFile();
    }

    public synchronized void deleteRecords(Set<Integer> indices) {
        List<Integer> sorted = new ArrayList<>(indices);
        Collections.sort(sorted, Collections.reverseOrder());
        for (int idx : sorted) {
            if (idx >= 0 && idx < records.size()) {
                FileRecord r = records.get(idx);
                if (r.filePath != null && !r.filePath.isEmpty()) {
                    new File(r.filePath).delete();
                }
                records.remove(idx);
            }
        }
        saveToFile();
    }

    public synchronized List<FileRecord> getRecords() {
        return new ArrayList<>(records);
    }

    public synchronized List<FileRecord> getFilteredRecords(String query) {
        if (query == null || query.trim().isEmpty()) return getRecords();
        String lower = query.toLowerCase().trim();
        List<FileRecord> filtered = new ArrayList<>();
        for (FileRecord r : records) {
            if (r.fileName.toLowerCase().contains(lower)) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    }