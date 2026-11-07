package in.otrail;

import java.util.*;
import java.util.regex.*;

/** Compact display labels only; stored destinations and routing coordinates stay intact. */
final class DestinationLabel {
  private static final Map<String,String> STATES=Map.ofEntries(
      Map.entry("andhra pradesh","AP"),Map.entry("arunachal pradesh","AR"),Map.entry("assam","AS"),
      Map.entry("bihar","BR"),Map.entry("chhattisgarh","CG"),Map.entry("goa","GA"),Map.entry("gujarat","GJ"),
      Map.entry("haryana","HR"),Map.entry("himachal pradesh","HP"),Map.entry("jharkhand","JH"),
      Map.entry("karnataka","KA"),Map.entry("kerala","KL"),Map.entry("madhya pradesh","MP"),
      Map.entry("maharashtra","MH"),Map.entry("manipur","MN"),Map.entry("meghalaya","ML"),
      Map.entry("mizoram","MZ"),Map.entry("nagaland","NL"),Map.entry("odisha","OD"),Map.entry("orissa","OD"),
      Map.entry("punjab","PB"),Map.entry("rajasthan","RJ"),Map.entry("sikkim","SK"),
      Map.entry("tamil nadu","TN"),Map.entry("telangana","TS"),Map.entry("tripura","TR"),
      Map.entry("uttar pradesh","UP"),Map.entry("uttarakhand","UK"),Map.entry("west bengal","WB"),
      Map.entry("delhi","DL"),Map.entry("chandigarh","CH"),Map.entry("jammu and kashmir","JK"),
      Map.entry("ladakh","LA"),Map.entry("puducherry","PY"),Map.entry("lakshadweep","LD"),
      Map.entry("andaman and nicobar islands","AN"),Map.entry("dadra and nagar haveli and daman and diu","DN"));
  static String format(String raw){
    if(raw==null||raw.isBlank())return "";
    List<String> parts=new ArrayList<>();String state="",pin="";
    for(String token:raw.split(",")){
      String part=token.trim();if(part.isEmpty()||part.equalsIgnoreCase("India"))continue;
      Matcher postal=Pattern.compile("(?<![0-9])[1-9][0-9]{5}(?![0-9])").matcher(part);
      if(postal.find()){pin=postal.group();part=(part.substring(0,postal.start())+part.substring(postal.end())).trim();}
      String code=STATES.get(part.toLowerCase(Locale.ROOT));
      if(code!=null){state=code;continue;}
      if(!part.isEmpty())parts.add(part);
    }
    String name=String.join(", ",parts),suffix=state+(pin.isEmpty()?"":(state.isEmpty()?"":"-")+pin);
    return name+(suffix.isEmpty()?"":(name.isEmpty()?"":": ")+suffix);
  }
}
