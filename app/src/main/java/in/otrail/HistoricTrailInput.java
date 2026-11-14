package in.otrail;
import java.util.*;
import java.time.*;
/** User list order is preserved unless explicit order or timestamps define chronology. */
public final class HistoricTrailInput {
 public record Stop(int order,String name,Double lat,Double lon,Long time){}
 public static List<Stop> parse(String raw){
  if(raw.length()>262144)throw new IllegalArgumentException("List exceeds 256 KB");
  List<List<String>> rows=csv(raw);List<Stop> stops=new ArrayList<>();if(rows.isEmpty())throw new IllegalArgumentException("Enter at least two places");
  List<String> first=rows.get(0).stream().map(s->s.trim().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toList());boolean header=first.contains("name")||first.contains("lat")||first.contains("order");boolean explicitOrder=header&&first.contains("order");
  for(int r=header?1:0;r<rows.size();r++){
   List<String> row=rows.get(r);if(header){String name=value(row,first.indexOf("name")),lat=value(row,first.indexOf("lat")),lon=value(row,first.indexOf("lon")),time=value(row,first.indexOf("time")),order=value(row,first.indexOf("order"));stops.add(stop(order.isBlank()?stops.size():Integer.parseInt(order),name,lat,lon,time));}
   else if(row.size()>=2&&number(row.get(0))&&number(row.get(1)))stops.add(stop(stops.size(),"",row.get(0),row.get(1),value(row,2)));
   else if(row.size()>=3&&number(row.get(1))&&number(row.get(2)))stops.add(stop(stops.size(),row.get(0),row.get(1),row.get(2),value(row,3)));
   else for(String city:row)if(!city.isBlank())stops.add(stop(stops.size(),city,"","",""));
  }
  if(stops.size()<2||stops.size()>100)throw new IllegalArgumentException("Use 2–100 places per list");
  if(explicitOrder){if(stops.stream().map(Stop::order).distinct().count()!=stops.size())throw new IllegalArgumentException("Order values must be unique");stops.sort(Comparator.comparingInt(Stop::order));}
  else if(stops.stream().allMatch(s->s.time()!=null))stops.sort(Comparator.comparingLong(Stop::time));
  return List.copyOf(stops);
 }
 private static Stop stop(int order,String name,String lat,String lon,String time){
  Double a=null,b=null;if(!lat.isBlank()||!lon.isBlank()){a=Double.parseDouble(lat);b=Double.parseDouble(lon);if(!Double.isFinite(a)||!Double.isFinite(b)||a<6||a>38||b<68||b>98)throw new IllegalArgumentException("Coordinates must be in India");}
  if(name.isBlank()&&a==null)throw new IllegalArgumentException("Each place needs a name or coordinates");
  Long t=null;if(!time.isBlank())try{t=Long.parseLong(time);}catch(NumberFormatException e){try{t=OffsetDateTime.parse(time).toInstant().toEpochMilli();}catch(Exception x){try{t=LocalDate.parse(time).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli();}catch(Exception y){throw new IllegalArgumentException("Use ISO date/time with timezone, YYYY-MM-DD, or epoch milliseconds");}}}
  return new Stop(order,name.trim(),a,b,t);
 }
 private static boolean number(String v){try{Double.parseDouble(v);return true;}catch(Exception e){return false;}}
 private static String value(List<String> row,int i){return i<0||i>=row.size()?"":row.get(i).trim();}
 private static List<List<String>> csv(String raw){
  List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
  for(int i=0;i<=raw.length();i++){char c=i==raw.length()?'\n':raw.charAt(i);if(c=='"'){if(quoted&&i+1<raw.length()&&raw.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}
   else if(!quoted&&(c==','||c=='\n'||c=='\r')){row.add(cell.toString().trim());cell.setLength(0);if(c!=','){if(row.stream().anyMatch(s->!s.isBlank())&&!row.get(0).startsWith("#"))rows.add(List.copyOf(row));row.clear();if(c=='\r'&&i+1<raw.length()&&raw.charAt(i+1)=='\n')i++;}}
   else cell.append(c);
  }if(quoted)throw new IllegalArgumentException("Unclosed CSV quote");return rows;
 }
}
