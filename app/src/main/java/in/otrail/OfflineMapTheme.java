package in.otrail;

import java.io.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.mapsforge.map.rendertheme.*;
import org.w3c.dom.*;

/** Restyles base-map road colors and captions while preserving geometry, widths and zoom rules. */
final class OfflineMapTheme implements XmlRenderTheme {
  private final XmlRenderTheme base=InternalRenderTheme.OSMARENDER;
  static String cacheKey() {
    return Integer.toHexString(Objects.hash(UiConfig.CITY_LABEL_FONT,UiConfig.CITY_LABEL_STYLE,
        UiConfig.CITY_LABEL_COLOR,UiConfig.CITY_LABEL_OUTLINE_COLOR,
        UiConfig.CITY_LABEL_OUTLINE_WIDTH,UiConfig.CITY_LABEL_SIZE_SCALE,UiConfig.CITY_LABEL_MIN_SIZE,UiConfig.CITY_LABEL_MAX_SIZE,UiConfig.BIG_CITY_LABEL_COLOR,UiConfig.BIG_CITY_LABEL_STYLE,UiConfig.MAP_ROAD_LABEL_COLOR,UiConfig.MAP_OTHER_LABEL_COLOR,UiConfig.MAP_TEXT_OUTLINE_COLOR,UiConfig.MAP_TEXT_FONT,
        UiConfig.MAP_ROAD_CASING,UiConfig.MAP_ROAD_MOTORWAY,UiConfig.MAP_ROAD_TRUNK,UiConfig.MAP_ROAD_PRIMARY,UiConfig.MAP_ROAD_SECONDARY,UiConfig.MAP_ROAD_TERTIARY,UiConfig.MAP_ROAD_MINOR,UiConfig.MAP_ROAD_PATH));
  }
  @Override public InputStream getRenderThemeAsStream() throws IOException {
    try (InputStream input=base.getRenderThemeAsStream()) {
      DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Document document=factory.newDocumentBuilder().parse(input);
      NodeList roads=document.getElementsByTagName("line");
      for(int i=0;i<roads.getLength();i++){
        Element line=(Element)roads.item(i);String highway=highway(line);
        if(highway==null||!line.hasAttribute("stroke"))continue;
        String old=line.getAttribute("stroke");
        boolean casing=old.equalsIgnoreCase("#707070")||old.equalsIgnoreCase("#000000");
        line.setAttribute("stroke",color(casing?UiConfig.MAP_ROAD_CASING:roadColor(highway)));
      }
      NodeList captions=document.getElementsByTagName("caption");
      List<Element> originalCaptions=new ArrayList<>();
      for(int i=0;i<captions.getLength();i++)originalCaptions.add((Element)captions.item(i));
      for(Element caption:originalCaptions) {
        if(!settlement(caption)){
          caption.setAttribute("fill",color(UiConfig.MAP_OTHER_LABEL_COLOR));
          caption.setAttribute("stroke",color(UiConfig.MAP_TEXT_OUTLINE_COLOR));
          caption.setAttribute("font-family",UiConfig.MAP_TEXT_FONT);continue;
        }
        caption.setAttribute("font-family",UiConfig.CITY_LABEL_FONT);
        caption.setAttribute("font-style",bigCity(caption)?UiConfig.BIG_CITY_LABEL_STYLE:UiConfig.CITY_LABEL_STYLE);
        caption.setAttribute("fill",color(bigCity(caption)?UiConfig.BIG_CITY_LABEL_COLOR:UiConfig.CITY_LABEL_COLOR));
        caption.setAttribute("stroke",color(UiConfig.CITY_LABEL_OUTLINE_COLOR));
        caption.setAttribute("stroke-width",Float.toString(UiConfig.CITY_LABEL_OUTLINE_WIDTH));
        if(caption.hasAttribute("font-size")) {
          float original=Float.parseFloat(caption.getAttribute("font-size"));
          Element parent=(Element)caption.getParentNode();
          if("city".equals(parent.getAttribute("v")))parent.setAttribute("zoom-max","22");
          int[][] bands={{0,7},{8,10},{11,13},{14,22}};
          for(int[] band:bands) {
            Element zoomRule=document.createElement("rule");
            zoomRule.setAttribute("e",parent.getAttribute("e").isEmpty()?"any":parent.getAttribute("e"));
            zoomRule.setAttribute("k","*");zoomRule.setAttribute("v","*");
            zoomRule.setAttribute("zoom-min",Integer.toString(band[0]));zoomRule.setAttribute("zoom-max",Integer.toString(band[1]));
            Element scaled=(Element)caption.cloneNode(true);
            scaled.setAttribute("font-size",Float.toString(citySize(original,band[1])));
            zoomRule.appendChild(scaled);parent.appendChild(zoomRule);
          }
          parent.removeChild(caption);
        }
      }
      NodeList roadLabels=document.getElementsByTagName("pathText");
      for(int i=0;i<roadLabels.getLength();i++) {
        Element text=(Element)roadLabels.item(i);
        text.setAttribute("fill",color(UiConfig.MAP_ROAD_LABEL_COLOR));
        text.setAttribute("stroke",color(UiConfig.MAP_TEXT_OUTLINE_COLOR));
        text.setAttribute("font-family",UiConfig.MAP_TEXT_FONT);
      }
      ByteArrayOutputStream output=new ByteArrayOutputStream();
      TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document),new StreamResult(output));
      return new ByteArrayInputStream(output.toByteArray());
    } catch(Exception e) {throw new IOException("Cannot prepare offline city-label theme",e);}
  }
  static float citySize(float original,int zoom) {
    float factor=zoom<=7?.36f:zoom<=10?.40f:zoom<=13?.48f:.56f;
    return Math.max(UiConfig.CITY_LABEL_MIN_SIZE,Math.min(UiConfig.CITY_LABEL_MAX_SIZE,original*factor))*UiConfig.CITY_LABEL_SIZE_SCALE;
  }
  static String highway(Element element){
    for(Node node=element.getParentNode();node instanceof Element;node=node.getParentNode()){
      Element rule=(Element)node;if(!"rule".equals(rule.getTagName()))continue;
      if("highway".equals(rule.getAttribute("k"))&&!"*".equals(rule.getAttribute("v")))return rule.getAttribute("v");
    }return null;
  }
  static int roadColor(String highway){
    Set<String> types=new HashSet<>(Arrays.asList(highway.split("\\|")));
    if(types.contains("motorway")||types.contains("motorway_link"))return UiConfig.MAP_ROAD_MOTORWAY;
    if(types.contains("trunk")||types.contains("trunk_link"))return UiConfig.MAP_ROAD_TRUNK;
    if(types.contains("primary")||types.contains("primary_link"))return UiConfig.MAP_ROAD_PRIMARY;
    if(types.contains("secondary")||types.contains("secondary_link"))return UiConfig.MAP_ROAD_SECONDARY;
    if(types.contains("tertiary")||types.contains("tertiary_link"))return UiConfig.MAP_ROAD_TERTIARY;
    if(types.contains("track")||types.contains("path")||types.contains("footway")||types.contains("cycleway")||types.contains("steps")||types.contains("bridleway"))return UiConfig.MAP_ROAD_PATH;
    return UiConfig.MAP_ROAD_MINOR;
  }
  private static boolean bigCity(Element caption){
    for(Node node=caption.getParentNode();node instanceof Element;node=node.getParentNode()) {
      Element rule=(Element)node;if("place".equals(rule.getAttribute("k"))&&Arrays.asList(rule.getAttribute("v").split("\\|")).contains("city"))return true;
    }
    return false;
  }
  private static boolean settlement(Element caption) {
    Set<String> places=Set.of("city","town","village","suburb","hamlet","locality","neighbourhood");
    for(Node n=caption.getParentNode();n instanceof Element;n=n.getParentNode()) {
      Element rule=(Element)n;
      if("rule".equals(rule.getTagName())&&"place".equals(rule.getAttribute("k")))
        for(String value:rule.getAttribute("v").split("\\|"))if(places.contains(value))return true;
    }
    return false;
  }
  private static String color(int argb){return String.format(Locale.ROOT,"#%08X",argb);}
  @Override public String getRelativePathPrefix(){return base.getRelativePathPrefix();}
  @Override public XmlRenderThemeMenuCallback getMenuCallback(){return base.getMenuCallback();}
  @Override public void setMenuCallback(XmlRenderThemeMenuCallback callback){base.setMenuCallback(callback);}
  @Override public XmlThemeResourceProvider getResourceProvider(){return base.getResourceProvider();}
  @Override public void setResourceProvider(XmlThemeResourceProvider provider){base.setResourceProvider(provider);}
}
