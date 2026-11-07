package in.otrail;

import static org.junit.Assert.*;
import java.io.InputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.*;

public class OfflineMapThemeTest {
  @Test public void cityCaptionUsesConfigAndRetainsZoomRule() throws Exception {
    try(InputStream stream=new OfflineMapTheme().getRenderThemeAsStream()) {
      Document document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
      NodeList rules=document.getElementsByTagName("rule");boolean found=false;
      for(int i=0;i<rules.getLength();i++) {
        Element rule=(Element)rules.item(i);
        if(!"place".equals(rule.getAttribute("k"))||!"city".equals(rule.getAttribute("v")))continue;
        Element caption=(Element)rule.getElementsByTagName("caption").item(0);
        assertEquals(UiConfig.CITY_LABEL_FONT,caption.getAttribute("font-family"));
        assertEquals(UiConfig.BIG_CITY_LABEL_STYLE,caption.getAttribute("font-style"));
        assertEquals(String.format(java.util.Locale.ROOT,"#%08X",UiConfig.BIG_CITY_LABEL_COLOR),caption.getAttribute("fill"));
        assertEquals(OfflineMapTheme.citySize(25,7),Float.parseFloat(caption.getAttribute("font-size")),.001);
        assertEquals("22",rule.getAttribute("zoom-max"));assertEquals(4,rule.getElementsByTagName("caption").getLength());found=true;
      }
      assertTrue("Base map city rule should still exist",found);
      assertTrue("Road geometry rules should remain",rules.getLength()>100);
    }
  }
  @Test public void citySizesGrowWithZoomAndAreCapped(){
    assertTrue(OfflineMapTheme.citySize(25,7)<OfflineMapTheme.citySize(25,10));
    assertTrue(OfflineMapTheme.citySize(25,10)<OfflineMapTheme.citySize(25,13));
    assertTrue(OfflineMapTheme.citySize(25,13)<=OfflineMapTheme.citySize(25,18));
    assertEquals(UiConfig.CITY_LABEL_MAX_SIZE*UiConfig.CITY_LABEL_SIZE_SCALE,OfflineMapTheme.citySize(100,18),.001);
  }
}
