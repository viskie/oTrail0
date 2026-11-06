package in.otrail;

import java.util.*;

/** Derived distances use observed GPS edges only. Green estimated gaps never become logged km. */
public final class DriveMetrics {
  public record Point(long time,double lat,double lon,float accuracy,float speed) {}
  public record Stop(long id,long arrival,long departure,long observed) {}
  public record Leg(long stopId,long start,long end,double meters,long observedMs,long missingMs) {
    public double averageKmh(){return end>start?meters*3600/(end-start):0;}
  }
  public record Result(double totalMeters,Leg current,Map<Long,Leg> stops) {}
  public static Result calculate(List<Point> points,List<Stop> rawStops) {
    if(points.isEmpty())return new Result(0,new Leg(-1,0,0,0,0,0),Map.of());
    List<Stop> stops=new ArrayList<>(rawStops);stops.sort(Comparator.comparingLong(Stop::arrival).thenComparingLong(Stop::id));
    long[] time=new long[points.size()];double[] distance=new double[points.size()];long[] observed=new long[points.size()];
    int stopCursor=0;long coveredUntil=0;
    for(int i=0;i<points.size();i++) {
      Point b=points.get(i);time[i]=b.time();if(i==0)continue;Point a=points.get(i-1);
      distance[i]=distance[i-1];observed[i]=observed[i-1];
      while(stopCursor<stops.size()&&stops.get(stopCursor).arrival()<=a.time()) {
        Stop s=stops.get(stopCursor++);coveredUntil=Math.max(coveredUntil,s.departure()>0?s.departure():s.observed());
      }
      long dt=b.time()-a.time();
      if(dt<=0||dt>StopDetector.MAX_GAP_MS||a.accuracy()>50||b.accuracy()>50)continue;
      double meters=StopDetector.distance(a.lat(),a.lon(),b.lat(),b.lon());
      if(meters/(dt/1000.0)>60)continue;
      observed[i]+=dt;
      if(coveredUntil>=b.time())continue;
      if(a.speed()<.8&&b.speed()<.8&&meters<Math.max(5,(a.accuracy()+b.accuracy())/2))continue;
      distance[i]+=meters;
    }
    Map<Long,Leg> legs=new LinkedHashMap<>();long start=time[0];long latestEnd=start;
    for(Stop s:stops) {
      // Legacy duplicate/overlapping stops must never create negative legs.
      long end=Math.max(start,s.arrival());
      legs.put(s.id(),leg(s.id(),start,end,time,distance,observed));
      long departure=s.departure()>0?s.departure():s.observed();latestEnd=Math.max(latestEnd,departure);start=Math.max(start,departure);
    }
    long last=time[time.length-1];
    return new Result(distance[distance.length-1],leg(-1,Math.min(latestEnd,last),last,time,distance,observed),Collections.unmodifiableMap(legs));
  }
  private static Leg leg(long id,long start,long end,long[] times,double[] d,long[] observed) {
    int a=index(times,start),b=index(times,end);double meters=Math.max(0,d[b]-d[a]);long duration=Math.max(0,observed[b]-observed[a]);
    return new Leg(id,start,end,meters,duration,Math.max(0,end-start-duration));
  }
  private static int index(long[] time,long target){int i=Arrays.binarySearch(time,target);if(i>=0)return i;return Math.max(0,Math.min(time.length-1,-i-2));}
}
