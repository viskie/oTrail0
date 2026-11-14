package in.otrail;
/** Adaptive requests; poor fixes remain raw observations, never inferred movement. */
public final class GpsPolling {
 public static long interval(float accuracy,float speed,boolean stopped,long dwell,boolean motionWakeup){
  if(!Float.isFinite(accuracy)||accuracy>25||!Float.isFinite(speed)||speed>.8f)return 1000;
  return stopped&&dwell>=600000&&motionWakeup?30000:13000;
 }
}
