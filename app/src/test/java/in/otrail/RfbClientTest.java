package in.otrail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RfbClientTest {
  @Test
  public void decodesDefaultX11vncTrueColorPixelLayout() {
    assertEquals(0xff12ab34, RfbClient.decodeColor(0x0012ab34, 255, 255, 255, 16, 8, 0));
  }

  @Test
  public void scalesSixBitChannelsToArgb() {
    assertEquals(0xffff0082, RfbClient.decodeColor(0x3f020, 63, 63, 63, 12, 6, 0));
  }
}
