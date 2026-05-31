package cc.asac.airferry;

public class MimeTypeHelper {
    public static String fromFilename(String filename) {
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
}
