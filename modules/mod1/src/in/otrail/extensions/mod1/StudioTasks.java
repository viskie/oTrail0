package in.otrail.extensions.mod1;

import in.otrail.modules.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import android.system.Os;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
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
    if((!url.startsWith("https://raw.githubusercontent.com/viskie/oTrail0/main/")&&!url.startsWith("https://dl.google.com/android/repository/")&&!url.startsWith("https://images.linuxcontainers.org/images/debian/"))||url.contains("..")||size<1||size>250000000)throw new IOException("Untrusted or oversized tool artifact");
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
      File dest=new File(data,"projects/otrail-"+version+"-"+spec.getString("sha256").substring(0,12));
      if(!dest.isDirectory()){
        File zip=download(spec,new File(data,"downloads/source-"+version+".zip"),progress,"Download source",0,90);
        File stage=new File(data,"projects/stage-"+System.currentTimeMillis());try{StudioFiles.unzip(zip,stage);if(!new File(stage,"settings.gradle").isFile())throw new IOException("Invalid project archive");if(!stage.renameTo(dest))throw new IOException("Cannot activate source workspace");}finally{StudioFiles.remove(stage);}
      }
      StudioFiles.write(new File(data,"project.json"),new JSONObject().put("path",dest.getCanonicalPath()).toString().getBytes(StandardCharsets.UTF_8));progress.update("Source ready",100,"Existing workspace edits retained.");
    }else if(action.equals("importFolder")){
      File source=new File(args.getString("path")).getCanonicalFile(),external=android.os.Environment.getExternalStorageDirectory().getCanonicalFile();if(!source.isDirectory()||!source.getPath().startsWith(external.getPath()+File.separator))throw new IOException("Choose an existing shared-storage folder");File dest=new File(data,"projects/import-"+System.currentTimeMillis());copyTree(source,dest,source,progress,new int[]{0});if(!new File(dest,"settings.gradle").isFile())throw new IOException("Selected folder is not an Android source project");StudioFiles.write(new File(data,"project.json"),new JSONObject().put("path",dest.getCanonicalPath()).toString().getBytes(StandardCharsets.UTF_8));progress.update("Folder ready",100,dest.getName());
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
    }else if(action.equals("shellSetup")){
      setupShell(pkg,meta,data,progress);
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
  private static void copyTree(File source,File target,File root,ModuleV1.Progress progress,int[] count)throws Exception{if(progress.cancelled())throw new IOException("Cancelled");if(++count[0]>50000)throw new IOException("Folder contains too many files");String relative=root.toURI().relativize(source.toURI()).getPath();if(relative.contains("/.git/")||relative.startsWith(".git/")||relative.contains("/build/")||relative.startsWith("build/"))return;if(source.isDirectory()){if(!target.mkdirs()&&!target.isDirectory())throw new IOException("Cannot create workspace folder");File[] children=source.listFiles();if(children==null)throw new IOException("Cannot read "+source);for(File child:children)copyTree(child,new File(target,child.getName()),root,progress,count);}else{if(source.length()>100000000)throw new IOException("Workspace file too large: "+relative);Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}if(count[0]%250==0)progress.update("Import folder",Math.min(95,count[0]/500),count[0]+" entries");}

  private static void setupShell(File pkg,JSONObject meta,File data,ModuleV1.Progress progress)throws Exception{
    JSONObject spec=meta.getJSONObject("debianRootfs");
    File shellDir=new File(data,"debian"),root=new File(shellDir,"rootfs"),ready=new File(shellDir,"ready.json");
    if(ready.isFile()&&root.isDirectory()){
      JSONObject installed=StudioFiles.json(ready);
      if(spec.getString("sha256").equals(installed.optString("sha256"))){progress.update("Debian ready",100,"Existing verified root filesystem retained.");return;}
    }
    File archive=new File(data,"downloads/"+spec.getString("file"));archive.getParentFile().mkdirs();
    if(!archive.isFile()||archive.length()!=spec.getLong("sizeBytes")||!StudioFiles.sha(archive).equals(spec.getString("sha256"))){
      File part=new File(archive.getPath()+".part");try(ZipFile module=new ZipFile(pkg)){java.util.zip.ZipEntry entry=module.getEntry(spec.getString("entry"));if(entry==null||entry.getSize()!=spec.getLong("sizeBytes"))throw new IOException("GOD module does not contain the verified Debian image");try(InputStream in=module.getInputStream(entry);FileOutputStream out=new FileOutputStream(part)){byte[] buffer=new byte[65536];int n,last=-1;long copied=0;while((n=in.read(buffer))!=-1){if(progress.cancelled())throw new IOException("Cancelled");copied+=n;if(copied>spec.getLong("sizeBytes"))throw new IOException("Bundled Debian image exceeds verified size");out.write(buffer,0,n);int percent=(int)(copied*20/spec.getLong("sizeBytes"));if(percent!=last){last=percent;progress.update("Prepare included Debian",percent,copied+" / "+spec.getLong("sizeBytes")+" bytes");}}out.getFD().sync();}}
      if(part.length()!=spec.getLong("sizeBytes")||!StudioFiles.sha(part).equals(spec.getString("sha256")))throw new SecurityException("Bundled Debian checksum mismatch");Files.move(part.toPath(),archive.toPath(),StandardCopyOption.REPLACE_EXISTING);part.delete();
    }
    File stage=new File(shellDir,"stage-"+System.currentTimeMillis());StudioFiles.remove(stage);stage.mkdirs();
    try{
      extractRootfs(archive,stage,progress);
      File etc=new File(stage,"etc");etc.mkdirs();File resolv=new File(etc,"resolv.conf");Files.deleteIfExists(resolv.toPath());
      StudioFiles.write(resolv,("# Managed by GOD for the private Debian shell\n"+"nameserver 8.8.8.8\n"+"nameserver 1.1.1.1\n").getBytes(StandardCharsets.UTF_8));
      StudioFiles.write(new File(etc,"hosts"),("127.0.0.1 localhost\n::1 localhost\n").getBytes(StandardCharsets.UTF_8));
      new File(stage,"root").mkdirs();new File(stage,"project").mkdirs();new File(stage,"god-data").mkdirs();new File(stage,"app-data").mkdirs();new File(stage,"app-files").mkdirs();new File(stage,"app-cache").mkdirs();new File(stage,"external-app").mkdirs();new File(stage,"shared-storage").mkdirs();
      if(!new File(stage,"usr/bin/bash").canExecute())throw new IOException("Debian rootfs does not contain executable bash");
      StudioFiles.remove(root);if(!stage.renameTo(root))throw new IOException("Cannot activate Debian root filesystem");
      StudioFiles.write(ready,new JSONObject().put("sha256",spec.getString("sha256")).put("installedMs",System.currentTimeMillis()).put("source","bundled in GOD module").toString().getBytes(StandardCharsets.UTF_8));
      progress.update("Debian ready",100,"Root filesystem verified and installed privately.");
    }finally{StudioFiles.remove(stage);}
  }

  private static Path tarTarget(Path root,String raw)throws IOException{
    String name=raw.replace('\\','/');while(name.startsWith("./"))name=name.substring(2);
    if(name.startsWith("/")||name.indexOf('\0')>=0)throw new IOException("Unsafe rootfs entry");
    if(name.isEmpty())return root;
    Path target=root.resolve(name).normalize();
    if(!target.startsWith(root))throw new IOException("Rootfs entry escapes destination: "+raw);
    return target;
  }

  private static void createArchiveParents(Path root,Path target)throws IOException{
    if(target.equals(root))return;
    Path current=root;for(Path part:root.relativize(target.getParent())){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IOException("Rootfs entry traverses symlink: "+target);Files.createDirectories(current);}
  }

  private static void extractRootfs(File archive,File root,ModuleV1.Progress progress)throws Exception{
    long expanded=0;int entries=0;Map<Path,String> hardLinks=new LinkedHashMap<>();Path base=root.toPath().toAbsolutePath().normalize();
    try(InputStream file=new BufferedInputStream(new FileInputStream(archive));XZCompressorInputStream xz=new XZCompressorInputStream(file,true);TarArchiveInputStream tar=new TarArchiveInputStream(xz)){
      TarArchiveEntry entry;byte[] buffer=new byte[65536];
      while((entry=tar.getNextEntry())!=null){
        if(++entries>250000)throw new IOException("Debian archive has too many entries");
        Path target=tarTarget(base,entry.getName());try{createArchiveParents(base,target);}catch(IOException error){throw new IOException(error.getMessage()+" [entry "+entry.getName()+"]",error);}
        if(entry.isDirectory()){target.toFile().mkdirs();}
        else if(entry.isSymbolicLink()){
          Files.deleteIfExists(target);Files.createSymbolicLink(target,Paths.get(entry.getLinkName()));
        }else if(entry.isLink()){hardLinks.put(target,entry.getLinkName());}
        else if(entry.isFile()){
          File fileTarget=target.toFile();try(OutputStream out=new BufferedOutputStream(new FileOutputStream(fileTarget))){int n;while((n=tar.read(buffer))!=-1){expanded+=n;if(expanded>1600000000L)throw new IOException("Debian rootfs exceeds expanded size limit");out.write(buffer,0,n);}}
          try{Os.chmod(fileTarget.getAbsolutePath(),entry.getMode()&0777);}catch(Exception ignored){fileTarget.setExecutable((entry.getMode()&0111)!=0,false);}
        }
        if(entries%1000==0)progress.update("Extract included Debian",20+Math.min(79,entries/1400),entries+" files");
      }
    }
    for(Map.Entry<Path,String> link:hardLinks.entrySet()){
      Path source=tarTarget(base,link.getValue());Files.deleteIfExists(link.getKey());Files.createLink(link.getKey(),source);
    }
  }
}
