package in.otrail.extensions.mod1;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

final class StudioFiles {
  static byte[] read(File f,int limit)throws IOException{try(InputStream in=new FileInputStream(f)){return read(in,limit);}}
  static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("File exceeds size limit");out.write(b,0,n);}return out.toByteArray();}
  static JSONObject json(File f)throws Exception{return new JSONObject(new String(read(f,2000000),StandardCharsets.UTF_8));}
  static void write(File f,byte[] bytes)throws IOException{f.getParentFile().mkdirs();File part=new File(f.getPath()+".part");try(FileOutputStream out=new FileOutputStream(part)){out.write(bytes);out.getFD().sync();}Files.move(part.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING);}
  static File project(File data)throws Exception{File f=new File(json(new File(data,"project.json")).getString("path")).getCanonicalFile();File parent=new File(data,"projects").getCanonicalFile();if(!f.getPath().startsWith(parent.getPath()+File.separator)||!f.isDirectory())throw new IOException("Download a source version first");return f;}
  static File safe(File root,String relative)throws IOException{if(relative.isEmpty()||relative.contains("\\"))throw new IOException("Invalid workspace path");File f=new File(root,relative).getCanonicalFile();if(!f.getPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Path outside project");for(String part:relative.split("/"))if(part.equals(".git")||part.equals("build")||part.equals(".gradle"))throw new IOException("Generated files are not editable");return f;}
  static List<File> files(File root)throws IOException{List<File> out=new ArrayList<>();walk(root,root,out);return out;}
  private static void walk(File root,File dir,List<File> out)throws IOException{File[] files=dir.listFiles();if(files==null)return;for(File f:files){if(Files.isSymbolicLink(f.toPath()))continue;if(f.isDirectory()){if(!Arrays.asList("build",".gradle",".git","releases").contains(f.getName()))walk(root,f,out);}else if(f.length()<500000)out.add(f);if(out.size()>25000)throw new IOException("Too many project files");}}
  static String relative(File root,File f){return root.toURI().relativize(f.toURI()).getPath();}
  static String sha(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  static File snapshot(File root,File data)throws Exception{File dest=new File(data,"snapshots/source-"+System.currentTimeMillis()+".zip");dest.getParentFile().mkdirs();try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(dest))){for(File f:files(root)){zip.putNextEntry(new ZipEntry(relative(root,f)));try(InputStream in=new FileInputStream(f)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);}zip.closeEntry();}}File[] old=dest.getParentFile().listFiles();if(old!=null){Arrays.sort(old,Comparator.comparing(File::getName));for(int i=0;i<old.length-5;i++)old[i].delete();}return dest;}
  static void unzip(File archive,File destination)throws Exception{destination.mkdirs();long total=0;int count=0;Set<String> names=new HashSet<>();try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){ZipEntry e;byte[] b=new byte[32768];while((e=zip.getNextEntry())!=null){if(++count>30000||!names.add(e.getName()))throw new IOException("Invalid archive entries");File target=safe(destination,e.getName());if(e.isDirectory()){target.mkdirs();continue;}target.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(target)){if((target.getName().endsWith(".dex")||target.getName().equals("compiler.jar")||target.getName().equals("publisher.jar"))&&!target.setReadOnly())throw new IOException("Cannot protect compiler code");int n;while((n=zip.read(b))!=-1){total+=n;if(total>250000000)throw new IOException("Archive exceeds unpacked size limit");out.write(b,0,n);}}}}}
  static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File f:children)remove(f);file.delete();}
}
