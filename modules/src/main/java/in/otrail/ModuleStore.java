package in.otrail;

import android.content.Context;
import dalvik.system.DexClassLoader;
import in.otrail.modules.ModuleV1;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;

final class ModuleStore {
  interface Progress { void update(int percent); }
  private final Context context;
  ModuleStore(Context c){context=c.getApplicationContext();}
  List<SignedModuleCatalog.Release> catalog(boolean refresh) throws Exception {
    File cache=new File(context.getFilesDir(),"modules/catalog.json");String text;
    if(refresh){
      text=OnlineRouting.get(SignedModuleCatalog.PREFIX+"catalog.json",1_000_000);
      List<SignedModuleCatalog.Release> verified=SignedModuleCatalog.parse(text,ModuleTrust.PUBLIC_KEY);
      cache.getParentFile().mkdirs();android.util.AtomicFile atomic=new android.util.AtomicFile(cache);FileOutputStream out=atomic.startWrite();
      try{out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception error){atomic.failWrite(out);throw error;}
      return verified;
    }
    if(!cache.isFile())return List.of();
    return SignedModuleCatalog.parse(new String(Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8),ModuleTrust.PUBLIC_KEY);
  }
  String toolchains(boolean refresh) throws Exception {
    if(refresh)catalog(true);
    File cache=new File(context.getFilesDir(),"modules/catalog.json");
    if(!cache.isFile())return "[]";
    org.json.JSONArray list=SignedModuleCatalog.verified(new String(Files.readAllBytes(cache.toPath()),java.nio.charset.StandardCharsets.UTF_8),ModuleTrust.PUBLIC_KEY).optJSONArray("toolchains");
    return list==null?"[]":list.toString();
  }
  JSONObject selectedToolchain(String id) throws Exception {
    File choice=new File(context.getFilesDir(),"modules/data/"+id+"/selected-toolchain");
    String selected=choice.isFile()?new String(Files.readAllBytes(choice.toPath()),java.nio.charset.StandardCharsets.UTF_8):"";
    org.json.JSONArray list=new org.json.JSONArray(toolchains(false));
    for(int i=0;i<list.length();i++){
      JSONObject item=list.getJSONObject(i);
      if((selected.isEmpty()&&i==0)||selected.equals(item.getString("id"))){
        if(item.getInt("api")!=1||AppUpdater.installed(context)<item.getInt("minApp"))throw new IOException("Toolchain requires a compatible host update");return item;
      }
    }
    throw new IOException("Refresh and select a compatible toolchain first");
  }
  File file(SignedModuleCatalog.Release r){return new File(context.getFilesDir(),"modules/packages/"+r.id()+"/"+r.version()+"/module.oTrail0");}
  boolean installed(SignedModuleCatalog.Release r){return file(r).isFile();}
  String active(String id){return context.getSharedPreferences("downloaded_modules",0).getString("active."+id,"");}
  void deactivate(String id){context.getSharedPreferences("downloaded_modules",0).edit().remove("active."+id).apply();}
  void activate(SignedModuleCatalog.Release r) throws Exception {
    ModuleV1 trial=load(r);trial.close();
    context.getSharedPreferences("downloaded_modules",0).edit().putString("previous."+r.id(),active(r.id())).putString("active."+r.id(),r.version()).commit();
  }
  void rollback(String id) throws Exception {
    String previous=context.getSharedPreferences("downloaded_modules",0).getString("previous."+id,"");
    for(SignedModuleCatalog.Release r:catalog(false))if(r.id().equals(id)&&r.version().equals(previous)){activate(r);return;}
    throw new IOException("No previously active version is available");
  }
  void install(SignedModuleCatalog.Release r,char[] password,Progress progress) throws Exception {
    try {
      javax.crypto.spec.PBEKeySpec spec=new javax.crypto.spec.PBEKeySpec(password,java.util.Base64.getDecoder().decode(r.installSalt()),120000,256);
      byte[] check;try{check=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}finally{spec.clearPassword();}
      if(!MessageDigest.isEqual(check,java.util.Base64.getDecoder().decode(r.installHash())))throw new SecurityException("Incorrect module installation password");
    } finally { java.util.Arrays.fill(password, '\0'); }
    checkCompatibility(r);File ready=file(r);ready.getParentFile().mkdirs();
    File part=File.createTempFile("module-",".part",ready.getParentFile());
    HttpURLConnection c=(HttpURLConnection)new URL(r.url()).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);
    try{
      if(c.getResponseCode()!=200)throw new IOException("Module server HTTP "+c.getResponseCode());
      // Android 14+: mark the code file read-only before writing via the already-open descriptor.
      try(FileOutputStream out=new FileOutputStream(part);InputStream in=c.getInputStream()){
        if(!part.setReadOnly())throw new IOException("Cannot protect module code");
        byte[] bytes=new byte[32768];int n,last=-1;long total=0;
        while((n=in.read(bytes))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("Download cancelled");total+=n;if(total>r.size())throw new SecurityException("Module exceeds catalog size");out.write(bytes,0,n);int p=(int)(total*100/r.size());if(p!=last){last=p;progress.update(p);}}
        out.getFD().sync();
      }
      verify(r,part);
      Files.move(part.toPath(),ready.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }finally{c.disconnect();part.delete();}
  }
  private void checkCompatibility(SignedModuleCatalog.Release r) throws Exception {
    if(r.api()!=ModuleV1.API_VERSION||AppUpdater.installed(context)<r.minApp())throw new IOException("This module requires a newer compatible oTraiL host");
  }
  private void verify(SignedModuleCatalog.Release r,File file) throws Exception {
    if(file.length()!=r.size())throw new SecurityException("Module size mismatch");
    MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
    if(!AppUpdater.hex(digest.digest()).equals(r.sha256()))throw new SecurityException("Module checksum mismatch");
    try(ZipFile zip=new ZipFile(file)){
      ZipEntry manifest=zip.getEntry("module.json");if(manifest==null||manifest.getSize()>16000||zip.getEntry("classes.dex")==null)throw new SecurityException("Invalid module package");
      JSONObject m=new JSONObject(new String(readManifest(zip.getInputStream(manifest)),java.nio.charset.StandardCharsets.UTF_8));
      if(!r.id().equals(m.getString("id"))||!r.version().equals(m.getString("version"))||r.api()!=m.getInt("api")||!r.entry().equals(m.getString("entry")))throw new SecurityException("Module identity mismatch");
    }
  }
  private byte[] readManifest(InputStream stream) throws IOException {
    try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>16000)throw new IOException("Module manifest too large");out.write(b,0,n);}return out.toByteArray();
    }
  }
  ModuleV1 load(SignedModuleCatalog.Release r) throws Exception {
    checkCompatibility(r);File pkg=file(r);verify(r,pkg);
    DexClassLoader loader=new DexClassLoader(pkg.getAbsolutePath(),context.getCodeCacheDir().getAbsolutePath(),null,ModuleV1.class.getClassLoader());
    Object object=loader.loadClass(r.entry()).getDeclaredConstructor().newInstance();
    if(!(object instanceof ModuleV1 module)||!module.id().equals(r.id()))throw new SecurityException("Module entry does not implement the expected API");return module;
  }
}
