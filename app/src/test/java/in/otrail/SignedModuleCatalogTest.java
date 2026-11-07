package in.otrail;
import org.junit.Test;
import static org.junit.Assert.*;
import java.security.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
public class SignedModuleCatalogTest {
 private JSONObject version()throws Exception{return new JSONObject().put("id","mod1").put("name","GOD").put("version","1.0.0").put("api",1).put("minApp",23).put("entry","in.otrail.extensions.mod1.SourceStudio").put("url",SignedModuleCatalog.PREFIX+"GOD-1.0.0.oTrail0").put("sha256","a".repeat(64)).put("sizeBytes",1000).put("installSalt",Base64.getEncoder().encodeToString(new byte[16])).put("installHash",Base64.getEncoder().encodeToString(new byte[32]));}
 private String[] signed(JSONObject item)throws Exception{
   KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair keys=g.generateKeyPair();
   byte[] payload=new JSONObject().put("schema",1).put("versions",new JSONArray().put(item)).toString().getBytes(StandardCharsets.UTF_8);
   Signature s=Signature.getInstance("SHA256withRSA");s.initSign(keys.getPrivate());s.update(payload);
   return new String[]{new JSONObject().put("payload",Base64.getEncoder().encodeToString(payload)).put("signature",Base64.getEncoder().encodeToString(s.sign())).toString(),Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())};
 }
 @Test public void trustedCatalogPreservesCustomExtensionAndVersion()throws Exception{String[] data=signed(version());var entries=SignedModuleCatalog.parse(data[0],data[1]);assertEquals("GOD",entries.get(0).name());assertEquals("1.0.0",entries.get(0).version());assertTrue(entries.get(0).url().endsWith(".oTrail0"));}
 @Test(expected=SecurityException.class) public void tamperedSignatureRejected()throws Exception{String[] data=signed(version());JSONObject changed=new JSONObject(data[0]);byte[] sig=Base64.getDecoder().decode(changed.getString("signature"));sig[0]^=1;changed.put("signature",Base64.getEncoder().encodeToString(sig));SignedModuleCatalog.parse(changed.toString(),data[1]);}
 @Test(expected=SecurityException.class) public void signedExternalDownloadRejected()throws Exception{String[] data=signed(version().put("url","https://example.org/GOD.oTrail0"));SignedModuleCatalog.parse(data[0],data[1]);}
}
