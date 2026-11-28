package in.otrail.extensions.files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Directory listing and file operations shared by the module UI. */
final class FileOps {
  private static final int MAX_ENTRIES = 3000;
  private static final int MAX_TEXT_BYTES = 512 * 1024;
  private static final int MAX_TEXT_CHARS = 200 * 1024;

  private FileOps() {}

  static List<File> list(File directory) throws IOException {
    File[] children = directory.listFiles();
    if (children == null) throw new IOException("Cannot read " + directory.getAbsolutePath());
    List<File> out = new ArrayList<>();
    for (File child : children) {
      if (out.size() >= MAX_ENTRIES) break;
      out.add(child);
    }
    out.sort((a, b) -> {
      boolean left = a.isDirectory(), right = b.isDirectory();
      if (left != right) return left ? -1 : 1;
      return a.getName().compareToIgnoreCase(b.getName());
    });
    return out;
  }

  static String size(long bytes) {
    if (bytes < 1024) return bytes + " B";
    double value = bytes / 1024.0;
    String[] units = {"KB", "MB", "GB", "TB"};
    int unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.US, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
  }

  static String date(long millis) {
    if (millis <= 0) return "—";
    return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(new Date(millis));
  }

  static boolean isText(File file) {
    if (!file.isFile()) return false;
    String name = file.getName().toLowerCase(Locale.US);
    int dot = name.lastIndexOf('.');
    String extension = dot < 0 ? "" : name.substring(dot + 1);
    if (extension.matches("txt|md|markdown|log|json|xml|html?|css|js|java|kt|py|sh|bash|conf|cfg|ini|yml|yaml|csv|tsv|sql|properties|gradle|toml|nfo|list|rules|desktop|service|hook|env|rc|profile|hosts|passwd|group|fstab|sources")) {
      return true;
    }
    if (file.length() > 2 * 1024 * 1024) return false;
    try (InputStream in = new FileInputStream(file)) {
      byte[] head = new byte[4096];
      int count = in.read(head);
      if (count <= 0) return false;
      for (int i = 0; i < count; i++) {
        if (head[i] == 0) return false;
      }
      return true;
    } catch (IOException error) {
      return false;
    }
  }

  static String readText(File file) throws IOException {
    long available = Math.min(file.length(), MAX_TEXT_BYTES);
    StringBuilder out = new StringBuilder();
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[8192];
      long remaining = available;
      while (remaining > 0) {
        int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
        if (read < 0) break;
        out.append(new String(buffer, 0, read, "UTF-8"));
        remaining -= read;
        if (out.length() > MAX_TEXT_CHARS) break;
      }
    }
    if (out.length() > MAX_TEXT_CHARS) out.setLength(MAX_TEXT_CHARS);
    String text = out.toString();
    if (file.length() > available) text += "\n\n[truncated at " + size(available) + " of " + size(file.length()) + "]";
    return text.isEmpty() ? "(empty file)" : text;
  }

  static File uniqueTarget(File directory, String name) {
    File target = new File(directory, name);
    if (!target.exists()) return target;
    String base = name, extension = "";
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      base = name.substring(0, dot);
      extension = name.substring(dot);
    }
    for (int index = 1; index < 1000; index++) {
      File candidate = new File(directory, base + " (" + index + ")" + extension);
      if (!candidate.exists()) return candidate;
    }
    return new File(directory, base + "-" + System.currentTimeMillis() + extension);
  }

  static void delete(File file) throws IOException {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) delete(child);
    }
    if (!file.delete() && file.exists()) throw new IOException("Could not delete " + file.getName());
  }

  static void copy(File source, File target) throws IOException {
    if (source.isDirectory()) {
      if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not create " + target.getName());
      File[] children = source.listFiles();
      if (children != null) for (File child : children) copy(child, new File(target, child.getName()));
    } else {
      File parent = target.getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs())
        throw new IOException("Could not create " + parent.getName());
      try (InputStream in = new FileInputStream(source);
           OutputStream out = new FileOutputStream(target)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
      }
      //noinspection ResultOfMethodCallIgnored
      target.setLastModified(source.lastModified());
    }
  }

  static void move(File source, File target) throws IOException {
    if (source.renameTo(target)) return;
    copy(source, target);
    delete(source);
  }

  static long countChildren(File directory) {
    File[] children = directory.listFiles();
    return children == null ? 0 : children.length;
  }
}
