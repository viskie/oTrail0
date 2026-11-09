package in.otrail;
import java.util.*;
/** Screen-space decluttering only; never removes stored stops or trail geometry. */
public final class StopDotLayout {
  public record Point(long id,double x,double y,boolean longStop){}
  private record Cell(long x,long y){}
  public static double detail(double zoom){return Math.max(0,Math.min(1,(zoom-7)/9));}
  public static int diameter(double zoom,boolean longStop){return (int)Math.round(6+8*detail(zoom)+(longStop?2+2*detail(zoom):0));}
  public static double spacing(double zoom){return 46-28*detail(zoom);}
  public static Set<Long> select(List<Point> points,double gap,double liveX,double liveY){
    return select(points,gap,liveX,liveY,gap);
  }
  public static Set<Long> select(List<Point> points,double gap,double liveX,double liveY,double liveGap){
    List<Point> ordered=new ArrayList<>(points);ordered.sort(Comparator.comparing(Point::longStop).reversed().thenComparing(Comparator.comparingLong(Point::id).reversed()));
    Map<Cell,List<Point>> occupied=new HashMap<>();Set<Long> shown=new LinkedHashSet<>();double squared=gap*gap;
    for(Point p:ordered){if(!Double.isFinite(p.x())||!Double.isFinite(p.y()))continue;
      if(Double.isFinite(liveX)&&Double.isFinite(liveY)&&Math.hypot(p.x()-liveX,p.y()-liveY)<liveGap)continue;
      Cell cell=new Cell((long)Math.floor(p.x()/gap),(long)Math.floor(p.y()/gap));boolean crowded=false;
      for(int x=-1;x<=1&&!crowded;x++)for(int y=-1;y<=1&&!crowded;y++)for(Point q:occupied.getOrDefault(new Cell(cell.x()+x,cell.y()+y),List.of()))if(Math.pow(p.x()-q.x(),2)+Math.pow(p.y()-q.y(),2)<squared){crowded=true;break;}
      if(!crowded){shown.add(p.id());occupied.computeIfAbsent(cell,k->new ArrayList<>()).add(p);}
    }return shown;
  }
}
