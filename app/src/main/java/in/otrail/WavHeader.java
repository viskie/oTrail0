package in.otrail;
import java.nio.*;
import java.nio.charset.StandardCharsets;
/** Standard RIFF/WAVE PCM16 header, kept current during recording for crash recovery. */
final class WavHeader {
 static byte[] pcm16(long bytes,int rate,int channels){
  if(bytes<0||bytes>0xffffffffL-36||rate<=0||channels<1||channels>2)throw new IllegalArgumentException("Invalid WAV size/format");
  ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
  b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(bytes+36)).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
  b.putInt(16).putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2).putShort((short)(channels*2)).putShort((short)16);
  b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)bytes);return b.array();
 }
}
