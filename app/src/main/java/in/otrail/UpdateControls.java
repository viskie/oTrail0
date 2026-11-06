package in.otrail;

import android.app.*;
import android.widget.Toast;
import java.io.File;

final class UpdateControls {
  private final Activity activity;
  private File pending;

  UpdateControls(Activity a) {
    activity = a;
  }

  void show() {
    new AppDialogs(activity)
        .setTitle("oTraiL updates")
        .setMessage(
            AppUpdater.status(activity)
                + "\n\n"
                + "APK updates come from viskie/oTrail0/releases. Downloads work on cellular and"
                + " Wi-Fi. Installation uses Android's confirmation screen and preserves app data.")
        .setPositiveButton("Check now", (d, w) -> check())
        .setNeutralButton(
            AppUpdater.daily(activity) ? "Disable daily checks" : "Enable daily checks",
            (d, w) -> {
              AppUpdater.daily(activity, !AppUpdater.daily(activity));
              show();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  void resume() {
    if (pending != null && activity.getPackageManager().canRequestPackageInstalls()) {
      File file = pending;
      pending = null;
      AppUpdater.install(activity, file);
    }
  }

  private void check() {
    Toast.makeText(activity, "Checking GitHub…", Toast.LENGTH_SHORT).show();
    AppUpdater.check(
        activity,
        true,
        (manifest, message) ->
            activity.runOnUiThread(
                () -> {
                  if (activity.isDestroyed() || activity.isFinishing()) return;
                  if (manifest == null) {
                    new AppDialogs(activity)
                        .setTitle("App updates")
                        .setMessage(message)
                        .setPositiveButton("Close", null)
                        .show();
                    return;
                  }
                  new AppDialogs(activity)
                      .setTitle("Update to " + manifest.versionName())
                      .setMessage(
                          manifest.notes()
                              + "\n\nDownload: "
                              + MapPacks.size(manifest.size())
                              + ". SHA-256 and the APK signing key will be checked before"
                              + " installation.")
                      .setPositiveButton("Download", (d, w) -> download(manifest))
                      .setNegativeButton("Later", null)
                      .show();
                }));
  }

  private void download(UpdateManifest manifest) {
    android.app.ProgressDialog progress = new android.app.ProgressDialog(activity);
    progress.setTitle("Downloading oTraiL " + manifest.versionName());
    progress.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
    progress.setMax(100);
    progress.setCancelable(false);
    progress.show();
    AppUpdater.download(
        activity,
        manifest,
        new AppUpdater.DownloadCallback() {
          public void progress(int percent) {
            activity.runOnUiThread(
                () -> {
                  if (!activity.isDestroyed()) progress.setProgress(percent);
                });
          }

          public void done(File file, String error) {
            activity.runOnUiThread(
                () -> {
                  if (activity.isDestroyed()) return;
                  progress.dismiss();
                  if (error != null) {
                    new AppDialogs(activity)
                        .setTitle("Update not installed")
                        .setMessage(error)
                        .setPositiveButton("Close", null)
                        .show();
                    return;
                  }
                  new AppDialogs(activity)
                      .setTitle("Update verified")
                      .setMessage(
                          "Android will ask you to approve installation. If prompted, allow oTraiL"
                              + " to install app updates. Your trails and maps stay in place.")
                      .setPositiveButton(
                          "Install",
                          (d, w) -> {
                            pending = file;
                            if (activity.getPackageManager().canRequestPackageInstalls())
                              pending = null;
                            AppUpdater.install(activity, file);
                          })
                      .setNegativeButton("Later", null)
                      .show();
                });
          }
        });
  }
}
