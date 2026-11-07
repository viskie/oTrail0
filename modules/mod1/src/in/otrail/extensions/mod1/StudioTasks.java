package in.otrail.extensions.mod1;

import in.otrail.modules.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.json.*;

final class StudioTasks {
  static void merge(File source,File destination)throws IOException {
    if(source.isDirectory()){
      if(!destination.isDirectory()&&!destination.mkdirs())throw new IOException("Cannot create tool directory");
      File[] children=source.listFiles();if(children==null)throw new IOException("Cannot read tool directory");
      for(File child:children)merge(child,new File(destination,child.getName()));
      if(!source.delete())throw new IOException("Cannot remove tool staging directory");
    }else Files.move(source.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
  }
  static JSONObject metadata(File pkg)throws Exception{try(ZipFile zip=new ZipFile(pkg);InputStream in=zip.getInputStream(zip.getEntry("studio.json"))){return new JSONObject(new String(StudioFiles.read(in,100000),StandardCharsets.UTF_8));}}
  static File download(JSONObject spec,File target,ModuleV1.Progress progress,String phase,int base,int span)throws Exception{
    String url=spec.getString("url");long size=spec.getLong("sizeBytes");
    if((!url.startsWith("https://raw.githubusercontent.com/viskie/oTrail0/main/")&&!url.startsWith("https://dl.google.com/android/repository/"))||url.contains("..")||size<1||size>250000000)throw new IOException("Untrusted or oversized tool artifact");
    if(target.isFile()&&target.length()==size&&StudioFiles.sha(target).equals(spec.getString("sha256")))return target;
    File part=new File(target.getPath()+".part");target.getParentFile().mkdirs();HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setInstanceFollowRedirects(false);
    try{if(c.getResponseCode()!=200)throw new IOException("Download HTTP "+c.getResponseCode());try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){
      byte[] b=new byte[65536];int n,last=-1;long count=0;while((n=in.read(b))!=-1){if(progress.cancelled())throw new IOException("Cancelled");count+=n;if(count>size)throw new IOException("Download exceeds verified size");out.write(b,0,n);int p=(int)(count*100/size);if(p!=last){last=p;progress.update(phase,base+p*span/100,count+" / "+size+" bytes");}}out.getFD().sync();
    }if(part.length()!=size||!StudioFiles.sha(part).equals(spec.getString("sha256")))throw new SecurityException("Download checksum mismatch");Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);return target;
    }finally{c.disconnect();part.delete();}
  }
  static void run(File pkg,File data,String request,ModuleV1.Progress progress)throws Exception{
    JSONObject args=new JSONObject(request),meta=metadata(pkg);JSONObject chain=args.optJSONObject("_verifiedToolchain");if(chain!=null)for(Iterator<String> it=chain.keys();it.hasNext();){String key=it.next();meta.put(key,chain.get(key));}String action=args.getString("action");
    if(action.equals("source")){
      JSONObject spec=args.getJSONObject("source");String version=spec.getString("version");boolean trusted=false;JSONArray sources=meta.getJSONArray("sources");for(int i=0;i<sources.length();i++)if(sources.getJSONObject(i).getString("url").equals(spec.getString("url"))&&sources.getJSONObject(i).getString("sha256").equals(spec.getString("sha256"))&&sources.getJSONObject(i).getString("version").equals(version))trusted=true;
      if(!trusted||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("Source version is not in this module's verified manifest");
      File dest=new File(data,"projects/otrail-"+version);
      if(!dest.isDirectory()){
        File zip=download(spec,new File(data,"downloads/source-"+version+".zip"),progress,"Download source",0,90);
        File stage=new File(data,"projects/stage-"+System.currentTimeMillis());try{StudioFiles.unzip(zip,stage);if(!new File(stage,"settings.gradle").isFile())throw new IOException("Invalid project archive");if(!stage.renameTo(dest))throw new IOException("Cannot activate source workspace");}finally{StudioFiles.remove(stage);}
      }
      StudioFiles.write(new File(data,"project.json"),new JSONObject().put("path",dest.getCanonicalPath()).toString().getBytes(StandardCharsets.UTF_8));progress.update("Source ready",100,"Existing workspace edits retained.");
    }else if(action.equals("setup")){
      if(!args.optBoolean("acceptLicenses"))throw new IOException("Review and accept tool licenses first");
      JSONArray artifacts=meta.getJSONArray("tools");File target=new File(data,"tools");target.mkdirs();
      for(int i=0;i<artifacts.length();i++){
        JSONObject spec=artifacts.getJSONObject(i);String name=spec.getString("file");if(!name.matches("[A-Za-z0-9._-]+"))throw new IOException("Invalid tool filename");
        File zip=download(spec,new File(data,"downloads/"+name),progress,"Download "+name,i*100/artifacts.length(),100/artifacts.length());
        if(spec.has("extractFiles")){
          JSONObject extracts=spec.getJSONObject("extractFiles");try(ZipFile archive=new ZipFile(zip)){
            for(Iterator<String> it=extracts.keys();it.hasNext();){String entry=it.next(),dest=extracts.getString(entry);File output=StudioFiles.safe(target,dest);
              try(InputStream in=archive.getInputStream(archive.getEntry(entry));OutputStream out=new FileOutputStream(output)){byte[] b=new byte[32768];int n;long count=0;while((n=in.read(b))!=-1){if((count+=n)>100000000)throw new IOException("SDK file too large");out.write(b,0,n);}}
            }
          }
        }else if(spec.optBoolean("archive")){File stage=new File(data,"tool-stage");StudioFiles.remove(stage);StudioFiles.unzip(zip,stage);File[] files=stage.listFiles();if(files!=null)for(File f:files){File dest=new File(target,f.getName());merge(f,dest);}StudioFiles.remove(stage);}else Files.copy(zip.toPath(),new File(target,spec.getString("target")).toPath(),StandardCopyOption.REPLACE_EXISTING);
      }
      JSONObject checks=meta.getJSONObject("toolFiles");for(Iterator<String> it=checks.keys();it.hasNext();){String name=it.next();if(!StudioFiles.sha(new File(target,name)).equals(checks.getString(name)))throw new IOException("Tool verification failed: "+name);}
      StudioFiles.write(new File(target,"ready.json"),checks.toString().getBytes(StandardCharsets.UTF_8));progress.update("Tools ready",100,"Compiler, SDK classpath, dependency DEX and APK template verified. Compilation is entirely offline.");
    }else if(action.equals("build")){
      File tools=new File(data,"tools"),dex=new File(tools,"compiler.jar");JSONObject checks=meta.getJSONObject("toolFiles");
      for(Iterator<String> it=checks.keys();it.hasNext();){String name=it.next();if(!StudioFiles.sha(new File(tools,name)).equals(checks.getString(name)))throw new IOException("Download/repair build tools first: "+name);}
      if(!dex.setReadOnly())throw new IOException("Cannot protect compiler code");
      File workspace=StudioFiles.project(data);File backup=StudioFiles.snapshot(workspace,data);progress.update("Source snapshot saved",5,backup.getName());
      DexClassLoader loader=new DexClassLoader(dex.getAbsolutePath(),new File(data,"compiler-cache").getAbsolutePath(),null,CompilerV1.class.getClassLoader());
      CompilerV1 compiler=(CompilerV1)loader.loadClass("in.otrail.extensions.god.compiler.CompilerEngine").getDeclaredConstructor().newInstance();
      compiler.compile(workspace,tools,data,meta.toString(),progress);
    }else if(action.equals("githubCheck")||action.equals("githubPublish")){
      File tools=new File(data,"tools"),dex=new File(tools,"publisher.jar");if(!dex.isFile()||!StudioFiles.sha(dex).equals(meta.getJSONObject("toolFiles").getString("publisher.jar")))throw new IOException("Download the selected toolchain first");
      if(!dex.setReadOnly())throw new IOException("Cannot protect publisher code");
      DexClassLoader loader=new DexClassLoader(dex.getAbsolutePath(),new File(data,"publisher-cache").getAbsolutePath(),null,PublisherV1.class.getClassLoader());
      PublisherV1 publisher=(PublisherV1)loader.loadClass("in.otrail.extensions.god.compiler.GitPublisher").getDeclaredConstructor().newInstance();
      args.put("toolchain",meta);publisher.run(action,data,args.toString(),progress);
    }else throw new IOException("Unknown in-app task");
  }
}
