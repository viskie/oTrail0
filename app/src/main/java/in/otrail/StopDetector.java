package in.otrail;

/** Anchored dwell detection. Raw fixes are kept separately; uncertain movement is provisional. */
public final class StopDetector {
  public static final long MIN_STOP_MS=120_000, MAX_GAP_MS=60_000;
  public static final double RADIUS_METERS=35;
  private long start,last,lastInput,stableSince,candidateStart,candidateDeparture,candidateLast;
  private double latitude,longitude,candidateLat,candidateLon,firstLat,firstLon,path;
  private int stableCount,candidateCount,preciseCount;
  private boolean stopped,uncertain,reliable,jam;
  private long freeFlowSince;
  private boolean jamMoving;private int jamCycles;
  public long persistedStopId=-1;
  public record Event(boolean opened,boolean closed,long arrival,long departure,double lat,double lon) {}

  public Event accept(long time,double lat,double lon,float accuracy,float speed) {
    if(time<=lastInput)return null;
    lastInput=time;reliable=false;
    if(!Double.isFinite(lat)||!Double.isFinite(lon)||!Float.isFinite(accuracy)||accuracy>50||accuracy<0) {
      uncertain=true;stableCount=0;clearDeparture();
      if(!stopped){start=last=0;}
      return null; // Loss of signal is not evidence of departure.
    }
    if(start==0){seed(time,lat,lon);return null;}
    double displacement=distance(latitude,longitude,lat,lon);
    if(stopped) {
      if(speed>=1.5f)jamMoving=true;else if(speed<.8f&&jamMoving){jamCycles++;jamMoving=false;}
      double tolerance=RADIUS_METERS+Math.min(50,accuracy*2);
      if(displacement<=tolerance) {
        clearDeparture();
        reliable=accuracy<=20&&displacement<=RADIUS_METERS+accuracy&&speed<=1.5f;
        uncertain=!reliable;
        if(reliable){if(time-last>MAX_GAP_MS)stableCount=0;last=time;stable(time);uncertain=!stableSignal();}else stableCount=0;
        return null;
      }
      if(displacement<600&&speed<4.2f&&(jam||jamCycles>=2&&time-start<600000)&&accuracy<=20){jam=true;freeFlowSince=0;last=time;reliable=true;uncertain=false;clearDeparture();return null;}
      if(jam&&speed>=4.2f){if(freeFlowSince==0)freeFlowSince=time;}else freeFlowSince=0;
      uncertain=true;stableCount=0;
      if(candidateStart==0||time-candidateLast>MAX_GAP_MS)beginDeparture(time,lat,lon);
      else {
        double step=distance(candidateLat,candidateLon,lat,lon);
        double seconds=(time-candidateLast)/1000.0;
        double positionSpeed=step/Math.max(1,seconds);
        // Teleports and contradictory speed/position estimates restart the evidence window.
        if(positionSpeed>60||positionSpeed>8&&speed<1.5f)beginDeparture(time,lat,lon);
        else {path+=step;candidateLat=lat;candidateLon=lon;candidateLast=time;candidateCount++;}
      }
      if(accuracy<=10&&speed>1.5f)preciseCount++;
      double net=distance(firstLat,firstLon,lat,lon);
      long elapsed=time-candidateStart;
      boolean coherent=net>=60&&net/Math.max(1,path)>=.65;
      boolean confirmed=coherent&&((elapsed>=20_000&&preciseCount>=4)
          ||(elapsed>=90_000&&candidateCount>=12&&net>=200));
      if(!confirmed||jam&&displacement<600&&(freeFlowSince==0||time-freeFlowSince<60000))return null;
      Event event=new Event(false,true,start,candidateDeparture,latitude,longitude);
      seed(time,lat,lon);jam=false;freeFlowSince=0;stopped=false;uncertain=false;clearDeparture();
      return event;
    }
    // Ignore a claimed speed spike at an unchanged coordinate, but do not join uncertain gaps.
    boolean moving=displacement>RADIUS_METERS||speed>1.5f&&displacement>Math.max(5,accuracy);
    if(moving||time-last>MAX_GAP_MS){seed(time,lat,lon);return null;}
    last=time;reliable=accuracy<=20;uncertain=!reliable;
    if(reliable)stable(time);else stableCount=0;
    if(time-start>MIN_STOP_MS&&reliable){stopped=true;return new Event(true,false,start,time,latitude,longitude);}
    return null;
  }
  private void stable(long time){if(stableCount++==0)stableSince=time;}
  private void beginDeparture(long time,double lat,double lon){candidateStart=candidateLast=time;candidateDeparture=last;firstLat=candidateLat=lat;firstLon=candidateLon=lon;candidateCount=1;preciseCount=0;path=0;}
  private void clearDeparture(){candidateStart=candidateLast=0;candidateCount=preciseCount=0;path=0;}
  /** Replay/restore uses observed timestamps; a stable return confirms continuity before DB repair. */
  public void restore(long arrival,long observed,double lat,double lon,boolean open){reset();start=arrival;last=lastInput=observed;latitude=lat;longitude=lon;stopped=open;uncertain=true;}
  public boolean isStopped(){return stopped;}
  public boolean isUncertain(){return uncertain;}
  public boolean hasReliableObservation(){return reliable;}
  public boolean stableSignal(){return reliable&&stableCount>=3&&last-stableSince>=10_000;}
  public long observationTime(){return last;}
  public long arrival(){return start;}
  public double latitude(){return latitude;}
  public double longitude(){return longitude;}
  public void reset(){start=last=lastInput=stableSince=0;stopped=uncertain=reliable=jam=jamMoving=false;jamCycles=0;freeFlowSince=0;persistedStopId=-1;stableCount=0;clearDeparture();}
  private void seed(long time,double lat,double lon){start=last=time;latitude=lat;longitude=lon;stableCount=0;}
  public static double distance(double a,double b,double c,double d){double p=Math.toRadians(c-a),q=Math.toRadians(d-b);double h=Math.sin(p/2)*Math.sin(p/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(q/2)*Math.sin(q/2);return 6371000*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));}
}
