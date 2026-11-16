package in.otrail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** On-demand OpenStreetMap toll-feature lookup for a calculated route. */
final class RouteTolls {
  record Toll(String key, String name, String details, double latitude, double longitude,
      double alongMeters,String carRate,String rateEffective,String rateRevision) {}
  private record NhaiPlaza(int id,String name) {}
  private record NhaiRate(String amount,String effective,String revision) {}
  private static final String NHAI_API="https://rajmargyatra.nhai.gov.in/nhai/api/";
  private static final long NHAI_CACHE_MS=12L*60*60*1000;
  private static volatile List<NhaiPlaza> nhaiPlazas=List.of();
  private static volatile long nhaiPlazasFetched;
  private static final Map<Integer,NhaiRate> nhaiRates=new HashMap<>();
  private static final Pattern NUMERIC_RATE=Pattern.compile("\\d+(?:\\.\\d+)?");

  private RouteTolls() {}

  static String routeKey(RoutePlan route) {
    long hash=0xcbf29ce484222325L;
    for(RoutePlan.Point point:route.points) {
      hash=(hash^Double.doubleToLongBits(point.lat()))*0x100000001b3L;
      hash=(hash^Double.doubleToLongBits(point.lon()))*0x100000001b3L;
    }
    return Long.toHexString(hash);
  }

  static String query(RoutePlan route) {
    List<RoutePlan.Point> samples=samples(route);
    StringBuilder poly=new StringBuilder();
    for(RoutePlan.Point point:samples) {
      if(poly.length()>0)poly.append(' ');
      poly.append(String.format(Locale.US,"%.6f %.6f",point.lat(),point.lon()));
    }
    String around="around:"+UiConfig.TOLL_ROUTE_OFFSET_METERS+",poly:\""+poly+"\"";
    return "[out:json][timeout:25];("
        +"nwr("+around+")[barrier~\"^(toll_booth|toll_gantry)$\"];"
        +"nwr("+around+")[highway=toll_gantry];"
        +");out tags center 300;";
  }

  static List<Toll> fetch(RoutePlan route) throws Exception {
    String query=query(route);
    Exception last=null;
    List<String> failures=new ArrayList<>();
    for(String endpoint:UiConfig.LIVE_WORSHIP_SERVERS) {
      try {
        return addNhaiCarRates(request(endpoint,query,route));
      } catch(Exception error) {
        last=error;
        String detail=error.getMessage();
        if(detail==null||detail.isBlank())detail=error.getClass().getSimpleName();
        failures.add(new URL(endpoint).getHost()+": "+detail);
        Log.w("RouteTolls","Toll lookup failed at "+endpoint,error);
      }
    }
    throw new IOException("Overpass unavailable ("+String.join("; ",failures)+")",last);
  }

  private static List<Toll> request(String endpoint,String query,RoutePlan route) throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setConnectTimeout(12_000);
    connection.setReadTimeout(30_000);
    connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
    connection.setRequestProperty("User-Agent","oTraiL/0.4.17 (in.otrail; personal navigation)");
    try {
      try(OutputStream output=connection.getOutputStream()) {
        output.write(("data="+URLEncoder.encode(query,StandardCharsets.UTF_8.name()))
            .getBytes(StandardCharsets.UTF_8));
      }
      int status=connection.getResponseCode();
      if(status!=200)throw new IOException("Toll-map service HTTP "+status);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(InputStream input=connection.getInputStream()) {
        byte[] buffer=new byte[8192];
        int count;
        while((count=input.read(buffer))!=-1) {
          if(bytes.size()+count>4_000_000)throw new IOException("Toll-map response is too large");
          bytes.write(buffer,0,count);
        }
      }
      JSONObject response=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
      if(response.has("remark"))
        throw new IOException("Toll-map service returned an incomplete result");
      return parse(response,route);
    } finally {
      connection.disconnect();
    }
  }

  static List<Toll> parse(JSONObject response,RoutePlan route) throws Exception {
    JSONArray elements=response.getJSONArray("elements");
    Map<String,Toll> tolls=new LinkedHashMap<>();
    for(int i=0;i<elements.length();i++) {
      JSONObject element=elements.getJSONObject(i);
      JSONObject tags=element.optJSONObject("tags");
      if(tags==null||!isToll(tags))continue;
      JSONObject position=element.has("lat")?element:element.optJSONObject("center");
      if(position==null||!position.has("lat")||!position.has("lon"))continue;
      double latitude=position.getDouble("lat"),longitude=position.getDouble("lon");
      RoutePlan.Match match=route.match(new RoutePlan.Point(latitude,longitude),0,route.length());
      if(match.distance()>UiConfig.TOLL_ROUTE_OFFSET_METERS)continue;
      String type=element.optString("type","osm"),id=element.optString("id");
      if(id.isBlank())continue;
      String key=type+"/"+id;
      String name=tags.optString("name").trim();
      if(name.isBlank())name=UiConfig.text("toll_booth");
      List<String> details=new ArrayList<>();
      addTag(details,tags,"operator");
      addTag(details,tags,"ref");
      tolls.putIfAbsent(key,new Toll(key,name,String.join(" · ",details),
          latitude,longitude,match.along(),"","",""));
    }
    List<Toll> ordered=new ArrayList<>(tolls.values());
    ordered.sort(Comparator.comparingDouble(Toll::alongMeters));
    return List.copyOf(ordered);
  }

  private static List<Toll> addNhaiCarRates(List<Toll> tolls) {
    try {
      List<NhaiPlaza> plazas=nhaiPlazas();
      Map<String,NhaiPlaza> byName=new HashMap<>();
      for(NhaiPlaza plaza:plazas)byName.putIfAbsent(normalizeName(plaza.name()),plaza);
      List<Toll> result=new ArrayList<>(tolls.size());
      int requests=0;
      for(Toll toll:tolls) {
        NhaiPlaza plaza=byName.get(normalizeName(toll.name()));
        if(plaza==null||requests>=UiConfig.TOLL_NHAI_MAX_RATE_LOOKUPS) {
          result.add(toll);
          continue;
        }
        requests++;
        try {
          NhaiRate rate=nhaiRate(plaza.id());
          result.add(rate==null?toll:new Toll(toll.key(),toll.name(),toll.details(),
              toll.latitude(),toll.longitude(),toll.alongMeters(),rate.amount(),
              rate.effective(),rate.revision()));
        } catch(Exception error) {
          Log.w("RouteTolls","NHAI rate unavailable for plaza "+plaza.name(),error);
          result.add(toll);
        }
      }
      return List.copyOf(result);
    } catch(Exception error) {
      Log.w("RouteTolls","NHAI toll rates unavailable; toll locations retained",error);
      return tolls;
    }
  }

  private static synchronized List<NhaiPlaza> nhaiPlazas() throws Exception {
    long now=System.currentTimeMillis();
    if(!nhaiPlazas.isEmpty()&&now-nhaiPlazasFetched<NHAI_CACHE_MS)return nhaiPlazas;
    JSONObject response=nhaiRequest("getTollplazaName",new JSONObject(),120_000);
    JSONArray payload=successfulPayload(response);
    List<NhaiPlaza> plazas=new ArrayList<>();
    for(int i=0;i<payload.length();i++) {
      JSONObject item=payload.optJSONObject(i);
      if(item==null)continue;
      int id=item.optInt("tollplaza_id",0);
      String name=item.optString("tollplaza_name").trim();
      if(id>0&&!name.isBlank())plazas.add(new NhaiPlaza(id,name));
    }
    if(plazas.isEmpty())throw new IOException("NHAI toll list is empty");
    nhaiPlazas=List.copyOf(plazas);
    nhaiPlazasFetched=now;
    return nhaiPlazas;
  }

  private static synchronized NhaiRate nhaiRate(int id) throws Exception {
    if(nhaiRates.containsKey(id))return nhaiRates.get(id);
    JSONObject response=nhaiRequest("getTollplazaDetails",
        new JSONObject().put("tollplaza_id",id),4_000_000);
    JSONArray payload=successfulPayload(response);
    JSONObject item=payload.optJSONObject(0);
    if(item==null||item.optInt("tollplaza_id",0)!=id)
      throw new IOException("NHAI returned no details for plaza "+id);
    String amount=item.optString("CarRate_single").trim();
    if(!NUMERIC_RATE.matcher(amount).matches())amount="";
    NhaiRate rate=new NhaiRate(amount,item.optString("DtEffectiveRates",""),
        item.optString("DtRateRevision",""));
    nhaiRates.put(id,rate);
    return rate;
  }

  private static JSONObject nhaiRequest(String path,JSONObject body,int maxBytes) throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL(NHAI_API+path).openConnection();
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setConnectTimeout(12_000);
    connection.setReadTimeout(25_000);
    connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
    connection.setRequestProperty("Accept","application/json");
    connection.setRequestProperty("User-Agent","oTraiL/0.4.17 (in.otrail; personal navigation)");
    try {
      try(OutputStream output=connection.getOutputStream()) {
        output.write(body.toString().getBytes(StandardCharsets.UTF_8));
      }
      int status=connection.getResponseCode();
      if(status!=200)throw new IOException("NHAI "+path+" HTTP "+status);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();
      try(InputStream input=connection.getInputStream()) {
        byte[] buffer=new byte[8192];
        int count;
        while((count=input.read(buffer))!=-1) {
          if(bytes.size()+count>maxBytes)throw new IOException("NHAI response too large");
          bytes.write(buffer,0,count);
        }
      }
      return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    } finally {
      connection.disconnect();
    }
  }

  private static JSONArray successfulPayload(JSONObject response) throws Exception {
    if(response.optInt("resultCode",-1)!=200)
      throw new IOException("NHAI toll service returned an unsuccessful result");
    return response.getJSONArray("payload");
  }

  static String normalizeName(String value) {
    return value.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)"," ")
        .replaceAll("[^a-z0-9]","")
        .replaceFirst("(toll)?plaza$","");
  }

  private static boolean isToll(JSONObject tags) {
    String barrier=tags.optString("barrier");
    return barrier.equals("toll_booth")||barrier.equals("toll_gantry")
        ||tags.optString("highway").equals("toll_gantry");
  }

  private static void addTag(List<String> details,JSONObject tags,String key) {
    String value=tags.optString(key).trim();
    if(!value.isBlank()&&!details.contains(value))details.add(value);
  }

  private static List<RoutePlan.Point> samples(RoutePlan route) {
    double length=route.length();
    if(length<1)return List.of(route.points.get(0),route.points.get(route.points.size()-1));
    int count=(int)Math.min(UiConfig.TOLL_ROUTE_MAX_SAMPLES,
        Math.max(2,Math.ceil(length/UiConfig.TOLL_ROUTE_SAMPLE_SPACING_METERS)+1));
    List<RoutePlan.Point> result=new ArrayList<>(count);
    for(int i=0;i<count;i++)result.add(pointAt(route,length*i/(count-1)));
    return result;
  }

  private static RoutePlan.Point pointAt(RoutePlan route,double along) {
    double[] cumulative=route.cumulative;
    int index=java.util.Arrays.binarySearch(cumulative,along);
    if(index>=0)return route.points.get(index);
    int next=Math.min(cumulative.length-1,-index-1);
    int previous=Math.max(0,next-1);
    double segment=cumulative[next]-cumulative[previous];
    if(segment<=0)return route.points.get(next);
    double fraction=(along-cumulative[previous])/segment;
    RoutePlan.Point a=route.points.get(previous),b=route.points.get(next);
    return new RoutePlan.Point(a.lat()+(b.lat()-a.lat())*fraction,
        a.lon()+(b.lon()-a.lon())*fraction);
  }
}
