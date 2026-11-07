package in.otrail;

import java.net.URI;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import org.json.*;

/** Signed catalog is the trust root for every downloadable executable version. */
public final class SignedModuleCatalog {
  public static final String PREFIX="https://raw.githubusercontent.com/viskie/oTrail0/main/modules/releases/";
  public record Release(String id,String name,String version,int api,int minApp,String entry,String url,String sha256,long size,String notes,String installSalt,String installHash) {}
  public static JSONObject verified(String envelope,String publicKey) throws Exception {
    if(envelope.length()>1_000_000)throw new SecurityException("Catalog too large");
    JSONObject signed=new JSONObject(envelope);
    byte[] payload=Base64.getDecoder().decode(signed.getString("payload"));
    Signature verifier=Signature.getInstance("SHA256withRSA");
    verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
    verifier.update(payload);
    if(!verifier.verify(Base64.getDecoder().decode(signed.getString("signature"))))throw new SecurityException("Module catalog signature is invalid");
    JSONObject root=new JSONObject(new String(payload,java.nio.charset.StandardCharsets.UTF_8));
    if(root.getInt("schema")!=1)throw new SecurityException("Unsupported catalog schema");
    return root;
  }
  public static List<Release> parse(String envelope,String publicKey) throws Exception {
    JSONObject root=verified(envelope,publicKey);
    JSONArray versions=root.getJSONArray("versions");List<Release> out=new ArrayList<>();Set<String> seen=new HashSet<>();
    for(int i=0;i<versions.length();i++){
      JSONObject v=versions.getJSONObject(i);String id=v.getString("id"),version=v.getString("version"),url=v.getString("url"),sha=v.getString("sha256"),entry=v.getString("entry");
      URI uri=new URI(url);long size=v.getLong("sizeBytes");
      if(!id.matches("[a-z][a-z0-9-]{0,63}")||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!seen.add(id+"/"+version)
          ||!url.startsWith(PREFIX)||url.contains("..")||!url.endsWith(".oTrail0")||uri.getQuery()!=null||uri.getFragment()!=null
          ||!sha.matches("[a-f0-9]{64}")||size<100||size>20_000_000||!entry.matches("in\\.otrail\\.extensions\\.[A-Za-z0-9_.]+"))throw new SecurityException("Invalid module version");
      if(Base64.getDecoder().decode(v.getString("installSalt")).length<16||Base64.getDecoder().decode(v.getString("installHash")).length!=32)throw new SecurityException("Invalid password verifier");
      out.add(new Release(id,v.getString("name"),version,v.getInt("api"),v.getInt("minApp"),entry,url,sha,size,v.optString("notes",""),v.getString("installSalt"),v.getString("installHash")));
    }
    return List.copyOf(out);
  }
}
