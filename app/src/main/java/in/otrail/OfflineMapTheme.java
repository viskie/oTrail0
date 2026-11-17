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
  private final boolean dark;
  OfflineMapTheme(){this(false);}
  OfflineMapTheme(boolean dark){this.dark=dark;}
  static String cacheKey() {
    return cacheKey(false);
  }
  static String cacheKey(boolean dark) {
    return Integer.toHexString(Objects.hash("readable-labels-v4",UiConfig.ROAD_LABEL_MIN_SIZE,UiConfig.ROAD_LABEL_MAX_SIZE,UiConfig.ROAD_LABEL_SIZE_SCALE,UiConfig.CITY_LABEL_FAR_FACTOR,UiConfig.CITY_LABEL_MID_FACTOR,UiConfig.CITY_LABEL_STREET_FACTOR,UiConfig.CITY_LABEL_CLOSE_FACTOR,dark,UiConfig.MAP_DARK_BACKGROUND,
        UiConfig.MAP_DARK_OUTSIDE_BACKGROUND,UiConfig.MAP_DARK_ROAD_CASING,
        UiConfig.MAP_DARK_ROAD_MOTORWAY,UiConfig.MAP_DARK_ROAD_TRUNK,
        UiConfig.MAP_DARK_ROAD_PRIMARY,UiConfig.MAP_DARK_ROAD_SECONDARY,
        UiConfig.MAP_DARK_ROAD_TERTIARY,UiConfig.MAP_DARK_ROAD_MINOR,UiConfig.MAP_DARK_ROAD_PATH,
        UiConfig.MAP_DARK_ROAD_LABEL_COLOR,UiConfig.MAP_DARK_OTHER_LABEL_COLOR,
        UiConfig.MAP_DARK_TEXT_OUTLINE_COLOR,UiConfig.MAP_DARK_CITY_LABEL_COLOR,
        UiConfig.MAP_DARK_CITY_LABEL_OUTLINE_COLOR,UiConfig.MAP_DARK_BIG_CITY_LABEL_COLOR,
        UiConfig.CITY_LABEL_FONT,UiConfig.CITY_LABEL_STYLE,
        UiConfig.CITY_LABEL_COLOR,UiConfig.CITY_LABEL_OUTLINE_COLOR,
        UiConfig.CITY_LABEL_OUTLINE_WIDTH,UiConfig.CITY_LABEL_SIZE_SCALE,UiConfig.CITY_LABEL_MIN_SIZE,UiConfig.CITY_LABEL_MAX_SIZE,UiConfig.BIG_CITY_LABEL_COLOR,UiConfig.BIG_CITY_LABEL_STYLE,UiConfig.MAP_ROAD_LABEL_COLOR,UiConfig.MAP_OTHER_LABEL_COLOR,UiConfig.MAP_TEXT_OUTLINE_COLOR,UiConfig.MAP_TEXT_FONT,
        UiConfig.MAP_ROAD_CASING,UiConfig.MAP_ROAD_MOTORWAY,UiConfig.MAP_ROAD_TRUNK,UiConfig.MAP_ROAD_PRIMARY,UiConfig.MAP_ROAD_SECONDARY,UiConfig.MAP_ROAD_TERTIARY,UiConfig.MAP_ROAD_MINOR,UiConfig.MAP_ROAD_PATH,UiConfig.MAP_ROAD_WIDTH_FAR,UiConfig.MAP_ROAD_WIDTH_MID,UiConfig.MAP_ROAD_WIDTH_STREET,UiConfig.MAP_ROAD_WIDTH_CLOSE));
  }
  @Override public InputStream getRenderThemeAsStream() throws IOException {
    try (InputStream input=base.getRenderThemeAsStream()) {
      DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Document document=factory.newDocumentBuilder().parse(input);
      applyPalette(document,dark);
      NodeList roads=document.getElementsByTagName("line");
      List<Element> roadLines=new ArrayList<>();for(int i=0;i<roads.getLength();i++)roadLines.add((Element)roads.item(i));
      for(Element line:roadLines){
        String highway=highway(line);
        if(highway==null||!line.hasAttribute("stroke"))continue;
        String old=line.getAttribute("stroke");
        boolean casing=old.equalsIgnoreCase("#707070")||old.equalsIgnoreCase("#000000");
        line.setAttribute("stroke",color(casing
            ?(dark?UiConfig.MAP_DARK_ROAD_CASING:UiConfig.MAP_ROAD_CASING)
            :roadColor(highway,dark)));
        if(line.hasAttribute("stroke-width")){
          float width=Float.parseFloat(line.getAttribute("stroke-width"));Element parent=(Element)line.getParentNode();
          int[][] bands={{0,10},{11,13},{14,16},{17,22}};
          for(int[] band:bands){Element rule=document.createElement("rule");rule.setAttribute("e","way");rule.setAttribute("k","*");rule.setAttribute("v","*");rule.setAttribute("zoom-min",Integer.toString(band[0]));rule.setAttribute("zoom-max",Integer.toString(band[1]));Element scaled=(Element)line.cloneNode(true);scaled.setAttribute("stroke-width",Float.toString(width*roadWidthScale(band[1])*roadClassScale(highway)));String[] laneValues={"-|1|2|3|4|5|6|7|8|9|10|11|12","1","2","3|4","5|6","7|8|9|10|11|12"};float[] laneFactors={1,.65f,.95f,1.25f,1.6f,1.9f};for(int lane=0;lane<laneValues.length;lane++){Element laneRule=document.createElement("rule");laneRule.setAttribute("e","way");laneRule.setAttribute("k","lanes");laneRule.setAttribute("v",laneValues[lane]);Element paint=(Element)scaled.cloneNode(true);paint.setAttribute("stroke-width",Float.toString(width*roadWidthScale(band[1])*roadClassScale(highway)*laneFactors[lane]));laneRule.appendChild(paint);rule.appendChild(laneRule);}parent.insertBefore(rule,line);}parent.removeChild(line);
        }
      }
      int roadLabel=dark?UiConfig.MAP_DARK_ROAD_LABEL_COLOR:UiConfig.MAP_ROAD_LABEL_COLOR;
      int otherLabel=dark?UiConfig.MAP_DARK_OTHER_LABEL_COLOR:UiConfig.MAP_OTHER_LABEL_COLOR;
      int textOutline=dark?UiConfig.MAP_DARK_TEXT_OUTLINE_COLOR:UiConfig.MAP_TEXT_OUTLINE_COLOR;
      for(String type:new String[]{"motorway","trunk","primary","secondary"}){Element rule=document.createElement("rule");rule.setAttribute("e","way");rule.setAttribute("k","highway");rule.setAttribute("v",type);rule.setAttribute("zoom-min",type.equals("motorway")||type.equals("trunk")?"10":"13");Element label=document.createElement("pathText");label.setAttribute("k","ref");label.setAttribute("font-size","10");label.setAttribute("fill",color(roadLabel));label.setAttribute("stroke",color(textOutline));label.setAttribute("stroke-width","1.5");rule.appendChild(label);document.getDocumentElement().appendChild(rule);}
      NodeList captions=document.getElementsByTagName("caption");
      List<Element> originalCaptions=new ArrayList<>();
      for(int i=0;i<captions.getLength();i++)originalCaptions.add((Element)captions.item(i));
      for(Element caption:originalCaptions) {
        if(!settlement(caption)){
          caption.setAttribute("fill",color(otherLabel));
          caption.setAttribute("stroke",color(textOutline));
          caption.setAttribute("font-family",UiConfig.MAP_TEXT_FONT);continue;
        }
        caption.setAttribute("font-family",UiConfig.CITY_LABEL_FONT);
        caption.setAttribute("font-style",bigCity(caption)?UiConfig.BIG_CITY_LABEL_STYLE:UiConfig.CITY_LABEL_STYLE);
        caption.setAttribute("fill",color(dark
            ?(bigCity(caption)?UiConfig.MAP_DARK_BIG_CITY_LABEL_COLOR:UiConfig.MAP_DARK_CITY_LABEL_COLOR)
            :(bigCity(caption)?UiConfig.BIG_CITY_LABEL_COLOR:UiConfig.CITY_LABEL_COLOR)));
        caption.setAttribute("stroke",color(dark?UiConfig.MAP_DARK_CITY_LABEL_OUTLINE_COLOR:UiConfig.CITY_LABEL_OUTLINE_COLOR));
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
      List<Element> labels=new ArrayList<>();for(int i=0;i<roadLabels.getLength();i++)labels.add((Element)roadLabels.item(i));
      for(Element text:labels) {
        text.setAttribute("fill",color(roadLabel));text.setAttribute("stroke",color(textOutline));text.setAttribute("font-family",UiConfig.MAP_TEXT_FONT);
        if(!text.hasAttribute("font-size"))continue;
        float original=Float.parseFloat(text.getAttribute("font-size"));Element parent=(Element)text.getParentNode();
        for(int[] band:new int[][]{{0,10},{11,13},{14,16},{17,22}}) {
          Element rule=document.createElement("rule");rule.setAttribute("e","way");rule.setAttribute("k","*");rule.setAttribute("v","*");
          rule.setAttribute("zoom-min",Integer.toString(band[0]));rule.setAttribute("zoom-max",Integer.toString(band[1]));
          Element scaled=(Element)text.cloneNode(true);scaled.setAttribute("font-size",Float.toString(roadLabelSize(original,band[1])));rule.appendChild(scaled);parent.appendChild(rule);
        }
        parent.removeChild(text);
      }
      ByteArrayOutputStream output=new ByteArrayOutputStream();
      TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document),new StreamResult(output));
      return new ByteArrayInputStream(output.toByteArray());
    } catch(Exception e) {throw new IOException("Cannot prepare offline city-label theme",e);}
  }
  private static void applyPalette(Document document,boolean dark) {
    Element root=document.getDocumentElement();
    root.setAttribute("map-background",color(dark?UiConfig.MAP_DARK_BACKGROUND:UiConfig.MAP_LIGHT_BACKGROUND));
    root.setAttribute("map-background-outside",color(dark?UiConfig.MAP_DARK_OUTSIDE_BACKGROUND:UiConfig.MAP_LIGHT_OUTSIDE_BACKGROUND));
    if(!dark)return;
    NodeList all=document.getElementsByTagName("*");
    for(int i=0;i<all.getLength();i++) {
      Element element=(Element)all.item(i);
      for(String attribute:new String[]{"fill","stroke"}) {
        String value=element.getAttribute(attribute);
        if(value.matches("#[0-9a-fA-F]{6}"))
          element.setAttribute(attribute,color(darkenColor((int)Long.parseLong(value.substring(1),16))));
      }
    }
  }
  static int darkenColor(int rgb) {
    int red=(rgb>>16)&255,green=(rgb>>8)&255,blue=rgb&255;
    return 0xff000000|((red*42/100+8)<<16)|((green*42/100+8)<<8)|(blue*42/100+8);
  }
  static float citySize(float original,int zoom) {
    float factor=zoom<=7?UiConfig.CITY_LABEL_FAR_FACTOR:zoom<=10?UiConfig.CITY_LABEL_MID_FACTOR:zoom<=13?UiConfig.CITY_LABEL_STREET_FACTOR:UiConfig.CITY_LABEL_CLOSE_FACTOR;
    return Math.max(UiConfig.CITY_LABEL_MIN_SIZE,Math.min(UiConfig.CITY_LABEL_MAX_SIZE,original*factor))*UiConfig.CITY_LABEL_SIZE_SCALE;
  }
  static float roadLabelSize(float original,int zoom){
    float factor=zoom<=10?1.1f:zoom<=13?1.4f:zoom<=16?1.7f:2f;
    return Math.max(UiConfig.ROAD_LABEL_MIN_SIZE,Math.min(UiConfig.ROAD_LABEL_MAX_SIZE,original*factor))*UiConfig.ROAD_LABEL_SIZE_SCALE;
  }
  static float roadClassScale(String highway){if(highway.contains("motorway"))return 1.65f;if(highway.contains("trunk"))return 1.4f;if(highway.contains("primary"))return 1.2f;if(highway.contains("secondary"))return 1.05f;if(highway.contains("tertiary"))return .9f;return .65f;}
  static float roadWidthScale(int zoom){return zoom<=10?UiConfig.MAP_ROAD_WIDTH_FAR:zoom<=13?UiConfig.MAP_ROAD_WIDTH_MID:zoom<=16?UiConfig.MAP_ROAD_WIDTH_STREET:UiConfig.MAP_ROAD_WIDTH_CLOSE;}
  static String highway(Element element){
    for(Node node=element.getParentNode();node instanceof Element;node=node.getParentNode()){
      Element rule=(Element)node;if(!"rule".equals(rule.getTagName()))continue;
      if("highway".equals(rule.getAttribute("k"))&&!"*".equals(rule.getAttribute("v")))return rule.getAttribute("v");
    }return null;
  }
  static int roadColor(String highway){
    return roadColor(highway,false);
  }
  static int roadColor(String highway,boolean dark){
    Set<String> types=new HashSet<>(Arrays.asList(highway.split("\\|")));
    if(types.contains("motorway")||types.contains("motorway_link"))return dark?UiConfig.MAP_DARK_ROAD_MOTORWAY:UiConfig.MAP_ROAD_MOTORWAY;
    if(types.contains("trunk")||types.contains("trunk_link"))return dark?UiConfig.MAP_DARK_ROAD_TRUNK:UiConfig.MAP_ROAD_TRUNK;
    if(types.contains("primary")||types.contains("primary_link"))return dark?UiConfig.MAP_DARK_ROAD_PRIMARY:UiConfig.MAP_ROAD_PRIMARY;
    if(types.contains("secondary")||types.contains("secondary_link"))return dark?UiConfig.MAP_DARK_ROAD_SECONDARY:UiConfig.MAP_ROAD_SECONDARY;
    if(types.contains("tertiary")||types.contains("tertiary_link"))return dark?UiConfig.MAP_DARK_ROAD_TERTIARY:UiConfig.MAP_ROAD_TERTIARY;
    if(types.contains("track")||types.contains("path")||types.contains("footway")||types.contains("cycleway")||types.contains("steps")||types.contains("bridleway"))return dark?UiConfig.MAP_DARK_ROAD_PATH:UiConfig.MAP_ROAD_PATH;
    return dark?UiConfig.MAP_DARK_ROAD_MINOR:UiConfig.MAP_ROAD_MINOR;
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
