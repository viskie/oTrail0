package in.otrail;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoIndexTest {
  private JSONObject photo()throws Exception{return new JSONObject().put("id","eb11c7d1-d714-45f2-81de-50d9389a475c").put("captured_ms",1760000000000L).put("uri","content://media/external/downloads/123").put("filename","oTrail;s123-456;20260101;abc.jpg").put("latitude",29.37).put("longitude",78.13);}
  @Test public void acceptsPublicPhotoWithNullableStop()throws Exception{JSONObject p=photo();PhotoIndex.validate(p);assertFalse(p.has("stop_id"));}
  @Test public void rejectsOutOfRangeLocation()throws Exception{JSONObject p=photo().put("latitude",95);try{PhotoIndex.validate(p);fail();}catch(IllegalArgumentException expected){}}
  @Test public void rejectsFilesystemUri()throws Exception{JSONObject p=photo().put("uri","file:///data/user/0/in.otrail/trails.db");try{PhotoIndex.validate(p);fail();}catch(IllegalArgumentException expected){}}
  @Test public void rejectsMalformedIdentity()throws Exception{JSONObject p=photo().put("id","------------------------------------");try{PhotoIndex.validate(p);fail();}catch(IllegalArgumentException expected){}}
  @Test public void distanceSupportsStopRadius(){assertEquals(0,PhotoIndex.distance(29,78,29,78),.001);assertTrue(PhotoIndex.distance(29,78,29.002,78)>150);}
}
