package in.otrail;

import java.util.*;

/** Thirty-one source-palette pass colors; the user-selected continuity color stays separate. */
public final class TrailPalette {
  public static final int CONTINUITY=UiConfig.CONTINUITY;
  public static final int[] COLORS=UiConfig.TRAIL_COLORS;
  public record Stroke(int color,int pass,List<TrailSegments.Sample> points){}
  private record Cell(int x,int y){}
  private record Visit(int pass,long time,double x,double y,double dx,double dy){}
  private static Cell cell(double x,double y){return new Cell((int)Math.floor(x/35),(int)Math.floor(y/35));}
  /** Display-only highlight. Preserve inferred green strokes and all historical colors. */
  public static List<Stroke> highlightCurrent(List<Stroke> strokes,long start){
    List<Stroke> result=new ArrayList<>();
    for(Stroke stroke:strokes){
      if(stroke.pass()==0){result.add(stroke);continue;}
      List<TrailSegments.Sample> points=stroke.points();
      if(points.size()<2){result.add(stroke);continue;}
      int split=1;while(split<points.size()&&points.get(split).time()<start)split++;
      if(split==points.size()){result.add(stroke);continue;}
      if(split>1)result.add(new Stroke(stroke.color(),stroke.pass(),List.copyOf(points.subList(0,split))));
      result.add(new Stroke(UiConfig.LIVE_TRAIL_COLOR,stroke.pass(),List.copyOf(points.subList(split-1,points.size()))));
    }
    return result;
  }
  public static List<Stroke> build(List<TrailSegments.Segment> segments) {
    Map<Cell,Visit> visits=new HashMap<>();List<Stroke> out=new ArrayList<>();
    for(TrailSegments.Segment segment:segments) {
      if(segment.inferred()){out.add(new Stroke(CONTINUITY,0,segment.points()));continue;}
      List<TrailSegments.Sample> p=segment.points();List<TrailSegments.Sample> run=new ArrayList<>();int active=1;double unmatched=0;
      for(int i=1;i<p.size();i++) {
        TrailSegments.Sample a=p.get(i-1),b=p.get(i);
        double ax=a.lon()*102000,ay=a.lat()*111000,bx=b.lon()*102000,by=b.lat()*111000;
        double length=Math.hypot(bx-ax,by-ay),dx=length==0?0:(bx-ax)/length,dy=length==0?0:(by-ay)/length;
        boolean moving=length>=12 && b.time()>a.time() && length/((b.time()-a.time())/1000.0)>=1;
        int pass=1;boolean matched=false;int count=Math.min(500,Math.max(1,(int)Math.ceil(length/12)));
        if(moving)for(int j=0;j<=count;j++) {
          double f=j/(double)count,x=ax+(bx-ax)*f,y=ay+(by-ay)*f;Cell c=cell(x,y);
          Visit closest=null;double nearest=28;
          for(int sx=-1;sx<=1;sx++)for(int sy=-1;sy<=1;sy++) {
            Visit v=visits.get(new Cell(c.x()+sx,c.y()+sy));if(v==null)continue;
            double dot=dx*v.dx()+dy*v.dy();if(Math.abs(dot)<.8)continue;
            double d=Math.hypot(x-v.x(),y-v.y());if(d<nearest){closest=v;nearest=d;}
          }
          if(closest!=null){long age=a.time()-closest.time();double dot=dx*closest.dx()+dy*closest.dy();
            boolean another=age>90000 || dot<-.8&&age>=10000;
            pass=Math.max(pass,closest.pass()+(another?1:0));matched=true;
          }
        }
        if(!moving)pass=active;
        else if(!matched){unmatched+=length;if(active>1&&unmatched<=80)pass=active;}
        else unmatched=0;
        if(moving)for(int j=0;j<=count;j++){double f=j/(double)count,x=ax+(bx-ax)*f,y=ay+(by-ay)*f;visits.put(cell(x,y),new Visit(pass,b.time(),x,y,dx,dy));}
        if(run.isEmpty()){run.add(a);active=pass;}
        if(active!=pass){if(run.size()>1)out.add(new Stroke(COLORS[(active-1)%COLORS.length],active,List.copyOf(run)));run=new ArrayList<>();run.add(a);active=pass;}
        run.add(b);
      }
      if(run.size()>1)out.add(new Stroke(COLORS[(active-1)%COLORS.length],active,List.copyOf(run)));
    }
    return out;
  }
}
