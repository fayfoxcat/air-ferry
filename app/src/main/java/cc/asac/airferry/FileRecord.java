package cc.asac.airferry;

import org.json.JSONException;
import org.json.JSONObject;

public class FileRecord {
    public final String fileName;
    public final long fileSize;
    public final long timestamp;
    public final long decodeDurationMs;
    public final String filePath;
    public final String md5;

    public FileRecord(String fileName, long fileSize, long timestamp, long decodeDurationMs, String filePath, String md5) {
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.timestamp = timestamp;
        this.decodeDurationMs = decodeDurationMs;
        this.filePath = filePath;
        this.md5 = md5 != null ? md5 : "";
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("fileName", fileName);
            obj.put("fileSize", fileSize);
            obj.put("timestamp", timestamp);
            obj.put("decodeDurationMs", decodeDurationMs);
            obj.put("filePath", filePath != null ? filePath : "");
            obj.put("md5", md5);
        } catch (JSONException ignored) {
        }
        return obj;
    }

    public static FileRecord fromJson(JSONObject obj) {
        return new FileRecord(
                obj.optString("fileName", ""),
                obj.optLong("fileSize", 0),
                obj.optLong("timestamp", 0),
                obj.optLong("decodeDurationMs", 0),
                obj.optString("filePath", ""),
                obj.optString("md5", "")
        );
    }

    public boolean matchesQuery(String query) {
        if (query == null || query.isEmpty()) return true;
        return fileName.toLowerCase().contains(query.toLowerCase());
    }
}