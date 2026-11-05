package in.otrail;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class AppUpdater {
  public static final String ACTION = "in.otrail.CHECK_UPDATE";
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

  public interface Callback {
    void done(UpdateManifest manifest, String message);
  }

  public interface DownloadCallback {
    void progress(int percent);

    void done(File file, String error);
  }

  public static long installed(Context c) throws Exception {
    return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
  }

  public static boolean daily(Context c) {
    return c.getSharedPreferences("updates", 0).getBoolean("daily", true);
  }

  public static void daily(Context c, boolean enabled) {
    c.getSharedPreferences("updates", 0).edit().putBoolean("daily", enabled).apply();
    schedule(c);
  }

  public static void schedule(Context c) {
    android.app.job.JobScheduler scheduler =
        (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    if (!daily(c)) {
      scheduler.cancel(2401);
      return;
    }
    if (scheduler.getPendingJob(2401) != null) return;
    scheduler.schedule(
        new android.app.job.JobInfo.Builder(2401, new ComponentName(c, UpdateCheckService.class))
            .setPersisted(true)
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(24L * 60 * 60 * 1000, 4L * 60 * 60 * 1000)
            .build());
  }

  public static String status(Context c) {
    return c.getSharedPreferences("updates", 0).getString("status", "No update check yet")
        + "\nDaily check: "
        + (daily(c) ? "on" : "off")
        + " · Android may defer the job until network/background work is available.";
  }

  public static void check(Context context, boolean manual, Callback callback) {
    Context c = context.getApplicationContext();
    WORKER.execute(
        () -> {
          UpdateManifest manifest = null;
          String message;
          try {
            String text = OnlineRouting.get(UpdateManifest.PREFIX + "latest.json", 32_000);
            UpdateManifest available = UpdateManifest.parse(text);
            if (available.newerThan(installed(c))) {
              manifest = available;
              message = "Update " + available.versionName() + " available";
              if (!manual) notifyUpdate(c, available);
            } else message = "Up to date · " + available.versionName();
          } catch (Exception e) {
            message = "Update check unavailable: " + e.getMessage();
          }
          c.getSharedPreferences("updates", 0)
              .edit()
              .putLong("checked", System.currentTimeMillis())
              .putString("status", message + "\nChecked: " + new Date())
              .apply();
          if (callback != null) callback.done(manifest, message);
        });
  }

  private static void notifyUpdate(Context c, UpdateManifest m) {
    android.content.SharedPreferences p = c.getSharedPreferences("updates", 0);
    if (p.getLong("notified", 0) >= m.versionCode()) return;
    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    nm.createNotificationChannel(
        new NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT));
    if (Build.VERSION.SDK_INT >= 33
        && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    PendingIntent open =
        PendingIntent.getActivity(
            c,
            2401,
            new Intent(c, MainActivity.class).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    nm.notify(
        2401,
        new Notification.Builder(c, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("oTraiL " + m.versionName() + " is available")
            .setContentText("Tap to review and install. Your trails and maps are preserved.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build());
    p.edit().putLong("notified", m.versionCode()).apply();
  }

  public static void download(Context context, UpdateManifest m, DownloadCallback callback) {
    Context c = context.getApplicationContext();
    WORKER.execute(
        () -> {
          File folder = new File(c.getFilesDir(), "updates");
          folder.mkdirs();
          File part = new File(folder, "candidate.part"), ready = new File(folder, "verified.apk");
          String error = null;
          File file = null;
          try {
            HttpURLConnection connection = (HttpURLConnection) new URL(m.url()).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("User-Agent", "oTraiL updater/0.2.4");
            try {
              if (connection.getResponseCode() != 200)
                throw new IOException("APK download returned HTTP " + connection.getResponseCode());
              MessageDigest digest = MessageDigest.getInstance("SHA-256");
              long count = 0;
              int percent = -1;
              try (InputStream in = connection.getInputStream();
                  FileOutputStream out = new FileOutputStream(part)) {
                byte[] bytes = new byte[65536];
                int n;
                while ((n = in.read(bytes)) != -1) {
                  count += n;
                  if (count > m.size()) throw new IOException("APK exceeds declared size");
                  out.write(bytes, 0, n);
                  digest.update(bytes, 0, n);
                  int next = (int) (count * 100 / m.size());
                  if (next != percent) {
                    percent = next;
                    callback.progress(percent);
                  }
                }
                out.getFD().sync();
              }
              if (count != m.size() || !hex(digest.digest()).equals(m.sha256()))
                throw new SecurityException("APK checksum or size mismatch");
              verifyPackage(c, part, m);
              java.nio.file.Files.move(
                  part.toPath(), ready.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              file = ready;
            } finally {
              connection.disconnect();
            }
          } catch (Exception e) {
            part.delete();
            error = e.getMessage();
          }
          callback.done(file, error);
        });
  }

  private static void verifyPackage(Context c, File file, UpdateManifest m) throws Exception {
    PackageManager pm = c.getPackageManager();
    PackageInfo
        archive =
            pm.getPackageArchiveInfo(
                file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES),
        current = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    if (archive == null
        || !c.getPackageName().equals(archive.packageName)
        || archive.getLongVersionCode() != m.versionCode()
        || archive.getLongVersionCode() <= current.getLongVersionCode()
        || archive.signingInfo == null
        || current.signingInfo == null)
      throw new SecurityException("APK identity or version does not match");
    Set<String> a = signers(archive.signingInfo), b = signers(current.signingInfo);
    if (a.isEmpty() || !a.equals(b))
      throw new SecurityException("APK signing key differs from the installed app");
  }

  private static Set<String> signers(SigningInfo info) throws Exception {
    Set<String> out = new HashSet<>();
    for (android.content.pm.Signature signature : info.getApkContentsSigners())
      out.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
    return out;
  }

  static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  public static void install(Activity activity, File verified) {
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      activity.startActivity(
          new Intent(
              Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
              Uri.parse("package:" + activity.getPackageName())));
      return;
    }
    Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/verified.apk");
    activity.startActivity(
        new Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
  }
}
