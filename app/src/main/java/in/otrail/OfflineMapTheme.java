package in.otrail;

import java.io.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.mapsforge.map.rendertheme.*;
import org.w3c.dom.*;

/** Restyles settlement captions while retaining the bundled map theme's roads and zoom rules. */
final class OfflineMapTheme implements XmlRenderTheme {
  private final XmlRenderTheme base=InternalRenderTheme.OSMARENDER;
  static String cacheKey() {
    return Integer.toHexString(Objects.hash(UiConfig.CITY_LABEL_FONT,UiConfig.CITY_LABEL_STYLE,
        UiConfig.CITY_LABEL_COLOR,UiConfig.CITY_LABEL_OUTLINE_COLOR,
        UiConfig.CITY_LABEL_OUTLINE_WIDTH,UiConfig.CITY_LABEL_SIZE_SCALE,UiConfig.MAP_ROAD_LABEL_COLOR,UiConfig.MAP_OTHER_LABEL_COLOR,UiConfig.MAP_TEXT_OUTLINE_COLOR,UiConfig.MAP_TEXT_FONT));
  }
  @Override public InputStream getRenderThemeAsStream() throws IOException {
    try (InputStream input=base.getRenderThemeAsStream()) {
      DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Document document=factory.newDocumentBuilder().parse(input);
      NodeList captions=document.getElementsByTagName("caption");
      for(int i=0;i<captions.getLength();i++) {
        Element caption=(Element)captions.item(i);
        if(!settlement(caption)){
          caption.setAttribute("fill",color(UiConfig.MAP_OTHER_LABEL_COLOR));
          caption.setAttribute("stroke",color(UiConfig.MAP_TEXT_OUTLINE_COLOR));
          caption.setAttribute("font-family",UiConfig.MAP_TEXT_FONT);continue;
        }
        caption.setAttribute("font-family",UiConfig.CITY_LABEL_FONT);
        caption.setAttribute("font-style",UiConfig.CITY_LABEL_STYLE);
        caption.setAttribute("fill",color(UiConfig.CITY_LABEL_COLOR));
        caption.setAttribute("stroke",color(UiConfig.CITY_LABEL_OUTLINE_COLOR));
        caption.setAttribute("stroke-width",Float.toString(UiConfig.CITY_LABEL_OUTLINE_WIDTH));
        if(caption.hasAttribute("font-size"))caption.setAttribute("font-size",Float.toString(
            Float.parseFloat(caption.getAttribute("font-size"))*UiConfig.CITY_LABEL_SIZE_SCALE));
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
