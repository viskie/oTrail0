package in.otrail.modules;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import java.io.File;

/** Public ABI shared by the APK host and independently downloaded module DEX packages. */
public interface ModuleV1 {
  int API_VERSION=1;
  interface Host {
    File packageFile();
    File privateDirectory();
    void startTask(String request) throws Exception;
    void cancelTask();
    String taskStatus();
    String toolchains(boolean refresh) throws Exception;
    String selectedToolchain() throws Exception;
    void selectToolchain(String id) throws Exception;
    void installBuiltApk(File file) throws Exception;
  }
  String id();
  View create(Activity activity,Host host) throws Exception;
  interface Progress {
    void update(String phase,int percent,String log);
    boolean cancelled();
  }
  default void task(File packageFile,File privateDirectory,String request,Progress progress) throws Exception {
    throw new UnsupportedOperationException("Module has no background tasks");
  }
  default void activityResult(int request,int result,Intent data) {}
  default void resume() {}
  default void pause() {}
  default void close() {}
}
