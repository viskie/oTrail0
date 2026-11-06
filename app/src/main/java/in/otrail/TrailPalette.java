package in.otrail;

import java.util.*;

/** Thirty-one source-palette pass colors; the user-selected continuity color stays separate. */
public final class TrailPalette {
  public static final int CONTINUITY=0xFF00FBFF;
  // First five: Vibrant Life (color-hex 5338). Remaining 26: Siter retro palettes.
  public static final int[] COLORS = {
      0xff17deee, 0xffff7f50, 0xffff4162, 0xffecf284, 0xff10aeb2,
      0xff3e2a18, 0xff8a5a2b, 0xffc1651e, 0xffd9a441, 0xffefe0bf,
      0xffc9a227, 0xffefe7c8, 0xff2a4747, 0xff5c8d89, 0xff9bc5be,
      0xfff4a6a0, 0xfffbf2e5, 0xff29335c, 0xffa8322d, 0xffe4572e,
      0xfff3a712, 0xfff5e9d6, 0xff12071f, 0xff3b1170, 0xff8b21b8,
      0xffe0218a, 0xff1b1b2f, 0xff4c3a87, 0xffb33f86, 0xfff5a65b, 0xfff2e8d5
  };
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
