package in.otrail;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Small, append-only event log shared by the module host, worker and UI.
 * It deliberately stores no passwords and truncates large payloads.
 */
public final class ModuleLog {
  private static final long MAX_BYTES = 2L * 1024 * 1024;
  private static final int MAX_DETAIL = 12000;
  private static final Object LOCK = new Object();
  private static Context context;

  private ModuleLog() {}

  static void init(Context source) {
    if (context == null && source != null) context = source.getApplicationContext();
  }

  public static void event(String module, String action, String detail) {
    Context app = context;
    if (app == null) return;
    String safeModule = module == null || module.isBlank() ? "host" : module;
    String safeAction = action == null || action.isBlank() ? "event" : action;
    String safeDetail = truncate(detail == null ? "" : detail);
    JSONObject line = new JSONObject();
    try {
      line.put("time", System.currentTimeMillis());
      line.put("module", safeModule);
      line.put("action", safeAction);
      line.put("detail", safeDetail);
    } catch (Exception ignored) {
      return;
    }
    synchronized (LOCK) {
      try {
        File folder = new File(app.getFilesDir(), "module-logs");
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        File file = new File(folder, "events.jsonl");
        if (file.length() > MAX_BYTES) rotate(file);
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile random = new RandomAccessFile(file, "rw");
            FileChannel channel = random.getChannel();
            FileLock lock = channel.lock()) {
          random.seek(random.length());
          random.write(bytes);
          if (safeAction.contains("crash") || safeAction.contains("fail")
              || safeAction.contains("complete")) {
            random.getFD().sync();
          }
        }
      } catch (Throwable error) {
        Log.w("ModuleLog", "Could not write event", error);
      }
    }
  }

  public static void crash(String stage, Throwable error) {
    String stack = error == null ? "unknown error" : Log.getStackTraceString(error);
    event("host", "crash", (stage == null ? "Uncaught" : stage) + "\n" + stack);
  }

  /** Newest-first event objects, capped by the caller's display/export limit. */
  public static String readJson(int limit) {
    Context app = context;
    if (app == null) return "[]";
    int cap = Math.max(1, Math.min(limit, 5000));
    List<JSONObject> entries = new ArrayList<>();
    synchronized (LOCK) {
      File folder = new File(app.getFilesDir(), "module-logs");
      readLines(entries, new File(folder, "events.previous.jsonl"));
      readLines(entries, new File(folder, "events.jsonl"));
    }
    JSONArray newestFirst = new JSONArray();
    int start = Math.max(0, entries.size() - cap);
    for (int index = entries.size() - 1; index >= start; index--) {
      newestFirst.put(entries.get(index));
    }
    return newestFirst.toString();
  }

  public static String readText(int limit) {
    Context app = context;
    if (app == null) return "No module log available.";
    int cap = Math.max(1, Math.min(limit, 5000));
    StringBuilder text = new StringBuilder();
    String data = readJson(cap);
    try {
      JSONArray array = new JSONArray(data);
      SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
      for (int index = 0; index < array.length(); index++) {
        JSONObject item = array.getJSONObject(index);
        text.append(stamp.format(new Date(item.optLong("time"))))
            .append("  ")
            .append(item.optString("module", "host"))
            .append(" / ")
            .append(item.optString("action", "event"))
            .append("  ")
            .append(item.optString("detail", ""))
            .append('\n');
      }
    } catch (Exception error) {
      text.append("Log parse failed: ").append(error).append('\n');
    }
    return text.length() == 0 ? "No module events recorded yet." : text.toString();
  }

  public static void clear() {
    Context app = context;
    if (app == null) return;
    synchronized (LOCK) {
      File folder = new File(app.getFilesDir(), "module-logs");
      delete(new File(folder, "events.jsonl"));
      delete(new File(folder, "events.previous.jsonl"));
    }
    event("host", "log.clear", "Module event log cleared");
  }

  private static void readLines(List<JSONObject> target, File file) {
    if (!file.isFile()) return;
    try {
      String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      String[] lines = text.split("\\n");
      for (String line : lines) {
        if (line.isBlank()) continue;
        try {
          target.add(new JSONObject(line));
        } catch (Exception ignored) {
          // A partially written final line is ignored.
        }
      }
    } catch (Exception ignored) {
      // A missing or unreadable log is treated as empty.
    }
  }

  private static void rotate(File file) {
    File previous = new File(file.getParentFile(), "events.previous.jsonl");
    delete(previous);
    if (!file.renameTo(previous)) delete(file);
  }

  private static void delete(File file) {
    if (file != null && file.exists()) file.delete();
  }

  private static String truncate(String value) {
    if (value.length() <= MAX_DETAIL) return value;
    return value.substring(0, MAX_DETAIL) + "…[truncated]";
  }
}
