package in.otrail;
import java.util.*;

/** Display-only lane allocation; latest traversal stays centred. Journal coordinates never change. */
final class TrailLanes {
 private record Cell(int x,int y){}
 private record Visit(int pass,double x,double y,double dx,double dy){}
 private final Map<Cell,List<Visit>> visits=new HashMap<>();
 private static Cell cell(double x,double y){return new Cell((int)Math.floor(x/35),(int)Math.floor(y/35));}
 TrailLanes(List<TrailPalette.Stroke> strokes){
  for(TrailPalette.Stroke stroke:strokes){if(stroke.pass()==0)continue;var p=stroke.points();
   for(int i=1;i<p.size();i++) {
    var a=p.get(i-1);var b=p.get(i);double ax=a.lon()*102000,ay=a.lat()*111000,dx=b.lon()*102000-ax,dy=b.lat()*111000-ay,length=Math.hypot(dx,dy);
    if(length<1)continue;int count=Math.min(200,Math.max(1,(int)Math.ceil(length/20)));dx/=length;dy/=length;
    for(int j=0;j<=count;j++){
     double t=j/(double)count,x=ax+dx*length*t,y=ay+dy*length*t;List<Visit> bucket=visits.computeIfAbsent(cell(x,y),k->new ArrayList<>());
     final int pass=stroke.pass();bucket.removeIf(v->v.pass()==pass&&Math.hypot(v.x()-x,v.y()-y)<10);
     bucket.add(new Visit(pass,x,y,dx,dy));
    }
   }
  }
 }
 int lane(TrailSegments.Sample p,TrailSegments.Sample a,TrailSegments.Sample b,int pass){
  if(pass==0)return 0;
  double x=p.lon()*102000,y=p.lat()*111000,dx=(b.lon()-a.lon())*102000,dy=(b.lat()-a.lat())*111000,length=Math.hypot(dx,dy);
  if(length<1)return 0;dx/=length;dy/=length;Cell c=cell(x,y);int latest=pass;
  for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++)for(Visit v:visits.getOrDefault(new Cell(c.x()+i,c.y()+j),List.of()))
   if(Math.hypot(v.x()-x,v.y()-y)<28&&Math.abs(dx*v.dx()+dy*v.dy())>.8)latest=Math.max(latest,v.pass());
  return position(latest-pass);
 }
 static int position(int age){return age<=0?0:(age%2==1?(age+1)/2:-age/2);}
}
