package in.otrail;

import java.util.*;

/** Thirty-one fixed pass colors; green is exclusively the inferred continuity overlay. */
public final class TrailPalette {
  public static final int CONTINUITY=0xff24b55a;
  public static final int[] COLORS={0xff0077cc,0xffe53935,0xff8e24aa,0xfffb8c00,0xff00acc1,0xffd81b60,0xff3949ab,0xff6d4c41,0xfff4511e,0xff5e35b1,0xff00838f,0xffc62828,0xffad1457,0xff1565c0,0xffef6c00,0xff7b1fa2,0xff455a64,0xffff4081,0xff536dfe,0xff795548,0xffb71c1c,0xff0097a7,0xfff57c00,0xff512da8,0xff880e4f,0xff0277bd,0xffa0522d,0xffe64a19,0xff6a1b9a,0xff283593,0xff546e7a};
  public record Stroke(int color,int pass,List<TrailSegments.Sample> points){}
  private record Cell(int lat,int lon){}
  private record Edge(Cell a,Cell b){}
  private record Visit(int pass,long time){}
  private static Cell cell(double lat,double lon){return new Cell((int)Math.round(lat*111000/35),(int)Math.round(lon*102000/35));}
  private static Edge edge(Cell a,Cell b){return a.lat()<b.lat()||a.lat()==b.lat()&&a.lon()<=b.lon()?new Edge(a,b):new Edge(b,a);}
  public static List<Stroke> build(List<TrailSegments.Segment> segments) {
    Map<Edge,Visit> visits=new HashMap<>();List<Stroke> out=new ArrayList<>();
    for(TrailSegments.Segment segment:segments) {
      if(segment.inferred()){out.add(new Stroke(CONTINUITY,0,segment.points()));continue;}
      List<TrailSegments.Sample> p=segment.points();List<TrailSegments.Sample> run=new ArrayList<>();int active=0;
      for(int i=1;i<p.size();i++) {
        TrailSegments.Sample a=p.get(i-1),b=p.get(i);double meters=StopDetector.distance(a.lat(),a.lon(),b.lat(),b.lon());
        int subdivisions=Math.min(300,Math.max(1,(int)Math.ceil(meters/15)));Cell prev=cell(a.lat(),a.lon());Set<Edge> cells=new HashSet<>();
        for(int j=1;j<=subdivisions;j++) {double f=j/(double)subdivisions;Cell next=cell(a.lat()+(b.lat()-a.lat())*f,a.lon()+(b.lon()-a.lon())*f);if(!next.equals(prev))cells.add(edge(prev,next));prev=next;}
        int pass=1;
        for(Edge e:cells){Visit v=visits.get(e);if(v!=null)pass=Math.max(pass,v.pass()+(a.time()-v.time()>120000?1:0));}
        for(Edge e:cells)visits.put(e,new Visit(pass,b.time()));
        if(run.isEmpty()){run.add(a);active=pass;}
        if(active!=pass){out.add(new Stroke(COLORS[(active-1)%COLORS.length],active,List.copyOf(run)));run=new ArrayList<>();run.add(a);active=pass;}
        run.add(b);
      }
      if(run.size()>1)out.add(new Stroke(COLORS[(active-1)%COLORS.length],active,List.copyOf(run)));
    }
    return out;
  }
}
