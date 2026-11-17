package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
public class WavHeaderTest {
 @Test public void stereo48kHeaderMatchesPcmPayload(){
  byte[] h=WavHeader.pcm16(192000,48000,2);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
  assertEquals(44,h.length);assertEquals("RIFF",new String(h,0,4,java.nio.charset.StandardCharsets.US_ASCII));assertEquals(192036,b.getInt(4));assertEquals(1,b.getShort(20));assertEquals(2,b.getShort(22));assertEquals(48000,b.getInt(24));assertEquals(192000,b.getInt(28));assertEquals(4,b.getShort(32));assertEquals(16,b.getShort(34));assertEquals(192000,b.getInt(40));
 }
 @Test(expected=IllegalArgumentException.class)public void refusesRiffOverflow(){WavHeader.pcm16(0xffffffffL,48000,2);}
}
