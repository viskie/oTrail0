package in.otrail;

/** Camera geometry only: GPS courses are clockwise from true north, map rotation is clockwise. */
public final class WtfCamera {
  private WtfCamera() {}
  public static float diagonalBearing(){return normalize((float)Math.toDegrees(Math.atan2(UiConfig.WTF_DIAGONAL_X,UiConfig.WTF_DIAGONAL_Y)));}
  public static float normalize(float degrees){return (degrees%360+360)%360;}
  public static float rotation(float trueCourse){return normalize(diagonalBearing()-trueCourse);}
  public static float shortestTurn(float current,float target){return (normalize(target)-normalize(current)+540)%360-180;}
  public static boolean reliable(long now,long fixTime,float accuracy,float speed,boolean stopped){return !stopped&&now-fixTime>=-10000&&now-fixTime<=30000&&Float.isFinite(accuracy)&&accuracy>0&&accuracy<=UiConfig.WTF_MAX_ACCURACY_M&&Float.isFinite(speed)&&speed>=UiConfig.WTF_MIN_SPEED_MPS;}
  /** Falls back to displacement only if it clearly exceeds the combined location uncertainty. */
  public static Float displacementCourse(long time,long priorTime,double lat,double lon,double priorLat,double priorLon,float accuracy,float priorAccuracy){
    if(!Float.isFinite(accuracy)||!Float.isFinite(priorAccuracy)||!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(priorLat)||!Double.isFinite(priorLon)||accuracy<=0||time<=priorTime||time-priorTime>60000||priorAccuracy<=0||priorAccuracy>UiConfig.WTF_MAX_ACCURACY_M)return null;
    double a=Math.toRadians(priorLat),b=Math.toRadians(lat),dl=Math.toRadians(lon-priorLon);
    double h=Math.pow(Math.sin((b-a)/2),2)+Math.cos(a)*Math.cos(b)*Math.pow(Math.sin(dl/2),2);
    double distance=12742000*Math.asin(Math.min(1,Math.sqrt(h)));
    if(distance<Math.max(12,(accuracy+priorAccuracy)*.75))return null;
    return normalize((float)Math.toDegrees(Math.atan2(Math.sin(dl)*Math.cos(b),Math.cos(a)*Math.sin(b)-Math.sin(a)*Math.cos(b)*Math.cos(dl))));
  }
}
