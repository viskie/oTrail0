package in.otrail;

import android.content.Context;
import android.view.View;

/** Trusted, bundled extension API v1. All UI callbacks run on the main thread. */
public interface TrailModule {
  int API_VERSION=1;
  record Snapshot(long now,long fixTime,double latitude,double longitude,float accuracy,
                  double totalMeters,int stopCount,boolean history) {}
  View createView(Context context);
  void update(Snapshot snapshot);
  default void close() {}
}
