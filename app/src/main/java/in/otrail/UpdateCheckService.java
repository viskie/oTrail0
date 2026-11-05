package in.otrail;

import android.app.job.*;

public final class UpdateCheckService extends JobService {
  private volatile boolean stopped;

  @Override
  public boolean onStartJob(JobParameters params) {
    stopped = false;
    if (!AppUpdater.daily(this)) return false;
    AppUpdater.check(
        this,
        false,
        (update, message) -> {
          if (!stopped) jobFinished(params, false);
        });
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    stopped = true;
    return true;
  }
}
