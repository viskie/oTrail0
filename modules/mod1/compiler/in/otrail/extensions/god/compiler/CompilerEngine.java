package in.otrail.extensions.god.compiler;

import in.otrail.modules.*;
import org.eclipse.jdt.core.compiler.CompilationProgress;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import com.android.tools.r8.*;
import com.android.apksig.ApkSigner;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import org.json.*;

/** Real, offline Java → DEX → aligned/signed APK build. No shell, Gradle, server or external app. */
public final class CompilerEngine implements CompilerV1 {
  private static String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  private static byte[] bytes(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){copy(in,out);return out.toByteArray();}}
  private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
  private static void walk(File root,List<File> out,String suffix){File[] list=root.listFiles();if(list==null)return;for(File f:list)if(f.isDirectory())walk(f,out,suffix);else if(f.getName().endsWith(suffix))out.add(f);}
  private static void remove(File f){File[] list=f.listFiles();if(list!=null)for(File child:list)remove(child);f.delete();}
  private static void check(ModuleV1.Progress p)throws IOException{if(p.cancelled())throw new IOException("Build cancelled; original app and prior APKs retained");}
  @Override public void compile(File workspace,File tools,File data,String configuration,ModuleV1.Progress progress)throws Exception{
    JSONObject config=new JSONObject(configuration);StringBuilder log=new StringBuilder("Offline in-app build\n");
    File baselineFile=new File(workspace,".studio-baseline.json");if(!baselineFile.isFile())throw new IOException("Source package has no verified build baseline");
    if(!hash(baselineFile).equals(config.getString("baselineSha256")))throw new SecurityException("Source baseline was modified; download a verified source workspace");
    JSONObject baseline=new JSONObject(new String(bytes(baselineFile),StandardCharsets.UTF_8));
    JSONObject immutable=baseline.getJSONObject("protected");
    for(Iterator<String> it=immutable.keys();it.hasNext();){String name=it.next();File f=new File(workspace,name);if(!f.isFile()||!hash(f).equals(immutable.getString(name)))throw new IOException("Unsupported resource/build-system change: "+name+". Restore it or select a compatible updated toolchain.");}
    List<File> resourceFiles=new ArrayList<>();walk(new File(workspace,"app/src/main/res"),resourceFiles,"");for(File f:resourceFiles){String path=workspace.toURI().relativize(f.toURI()).getPath();if(!immutable.has(path))throw new IOException("New resource requires a resource-capable toolchain: "+path);}
    // Signing data never lives in source, build snapshots, logs or GitHub artifacts.
    File signingFile=new File(data,"signing.json"),keystore=new File(data,"signing.keystore");
    if(!signingFile.isFile()||!keystore.isFile())throw new IOException("Import the existing signing keystore in Tools before building an installable APK");
    JSONObject signing=new JSONObject(new String(bytes(signingFile),StandardCharsets.UTF_8));char[] password=signing.getString("password").toCharArray(),keyPassword=signing.getString("keyPassword").toCharArray();
    PrivateKey privateKey;X509Certificate certificate;
    try{KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(keystore)){keys.load(in,password);}String alias=signing.getString("alias");privateKey=(PrivateKey)keys.getKey(alias,keyPassword);certificate=(X509Certificate)keys.getCertificate(alias);if(privateKey==null||certificate==null)throw new IOException("Signing alias not found");}
    finally{Arrays.fill(password,'\0');Arrays.fill(keyPassword,'\0');}
    String certificateHash=hex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    if(!certificateHash.equals(config.getString("signingCertificate")))throw new SecurityException("Signing certificate differs from oTraiL; original app must remain installed");
    check(progress);File work=new File(data,"compile-work");remove(work);work.mkdirs();File classes=new File(work,"classes");classes.mkdirs();
    List<File> sources=new ArrayList<>();JSONArray roots=baseline.getJSONArray("sourceRoots");for(int i=0;i<roots.length();i++)walk(new File(workspace,roots.getString(i)),sources,".java");
    if(sources.isEmpty())throw new IOException("No Java source files found");
    progress.update("Compile Java",15,log.toString()+sources.size()+" source files\n");
    org.eclipse.jdt.internal.compiler.env.ICompilationUnit[] units=new org.eclipse.jdt.internal.compiler.env.ICompilationUnit[sources.size()];String[] filenames=new String[sources.size()];
    for(int i=0;i<sources.size();i++){File file=sources.get(i);filenames[i]=file.getPath();units[i]=new org.eclipse.jdt.internal.compiler.batch.CompilationUnit(new String(bytes(file),StandardCharsets.UTF_8).toCharArray(),file.getPath(),"UTF-8");}
    org.eclipse.jdt.internal.compiler.batch.FileSystem environment=new org.eclipse.jdt.internal.compiler.batch.FileSystem(new String[]{new File(tools,"android.jar").getPath(),new File(tools,"classpath.jar").getPath(),new File(tools,"compiler-stubs.jar").getPath()},filenames,"UTF-8");
    Map<String,String> options=new HashMap<>();
    options.put(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.OPTION_Source,"17");options.put(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.OPTION_Compliance,"17");options.put(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.OPTION_TargetPlatform,"17");
    options.put(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.OPTION_Process_Annotations,org.eclipse.jdt.internal.compiler.impl.CompilerOptions.DISABLED);
    options.put(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.OPTION_LineNumberAttribute,org.eclipse.jdt.internal.compiler.impl.CompilerOptions.GENERATE);
    options.put(org.eclipse.jdt.internal.compiler.impl.CompilerOptions.OPTION_SourceFileAttribute,org.eclipse.jdt.internal.compiler.impl.CompilerOptions.GENERATE);
    final boolean[] failed={false};final IOException[] outputError={null};
    org.eclipse.jdt.internal.compiler.ICompilerRequestor requestor=result->{
      if(result.hasErrors()){failed[0]=true;for(org.eclipse.jdt.core.compiler.CategorizedProblem problem:result.getProblems())if(problem.isError())log.append(new String(problem.getOriginatingFileName())).append(':').append(problem.getSourceLineNumber()).append(": ").append(problem.getMessage()).append('\n');}
      else for(org.eclipse.jdt.internal.compiler.ClassFile file:result.getClassFiles())try{File target=new File(classes,new String(file.fileName())+".class");target.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(target)){out.write(file.getBytes());}}catch(IOException error){outputError[0]=error;}
    };
    org.eclipse.jdt.internal.compiler.Compiler compiler=new org.eclipse.jdt.internal.compiler.Compiler(environment,org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies.proceedWithAllProblems(),new org.eclipse.jdt.internal.compiler.impl.CompilerOptions(options),requestor,new org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory(Locale.US),new PrintWriter(new StringWriter()),new CompilationProgress(){
      public void begin(int total){}public void done(){}public boolean isCanceled(){return progress.cancelled();}public void setTaskName(String task){}public void worked(int amount,int remaining){}
    });
    try{compiler.compile(units);}finally{environment.cleanup();}
    if(outputError[0]!=null)throw outputError[0];if(failed[0])throw new IOException(log.toString());
    check(progress);File jar=new File(work,"app-classes.jar");List<File> classFiles=new ArrayList<>();walk(classes,classFiles,".class");
    try(JarOutputStream out=new JarOutputStream(new FileOutputStream(jar))){for(File f:classFiles){out.putNextEntry(new JarEntry(classes.toURI().relativize(f.toURI()).getPath()));try(InputStream in=new FileInputStream(f)){copy(in,out);}out.closeEntry();}}
    File dex=new File(work,"dex");dex.mkdirs();progress.update("Build Android DEX",50,log.toString());
    D8.run(D8Command.builder().setMode(CompilationMode.DEBUG).setMinApiLevel(30).addLibraryFiles(new File(tools,"android.jar").toPath(),new File(tools,"compiler-stubs.jar").toPath()).addProgramFiles(jar.toPath(),new File(tools,"dependency-dex.zip").toPath()).setOutput(dex.toPath(),OutputMode.DexIndexed).build());
    check(progress);progress.update("Package aligned APK",75,log.toString());File unsigned=new File(work,"unsigned.apk");
    try(ZipFile template=new ZipFile(new File(tools,"template.apk"));CountingStream count=new CountingStream(new FileOutputStream(unsigned));ZipOutputStream out=new ZipOutputStream(count)){
      Enumeration<? extends ZipEntry> entries=template.entries();while(entries.hasMoreElements()){
        ZipEntry old=entries.nextElement();String name=old.getName();if(name.matches("classes[0-9]*\\.dex")||name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")||name.equals("META-INF/MANIFEST.MF")||old.isDirectory())continue;
        ZipEntry next=new ZipEntry(name);next.setTime(315532800000L);
        if(old.getMethod()==ZipEntry.STORED){next.setMethod(ZipEntry.STORED);next.setSize(old.getSize());next.setCompressedSize(old.getSize());next.setCrc(old.getCrc());int alignment=name.endsWith(".so")?16384:4;int padding=(int)((alignment-(count.count+30+name.getBytes(StandardCharsets.UTF_8).length)%alignment)%alignment);if(padding>0){if(padding<4)padding+=alignment;byte[] extra=new byte[padding];extra[0]=(byte)0xfe;extra[1]=(byte)0xca;extra[2]=(byte)(padding-4);extra[3]=(byte)((padding-4)>>8);next.setExtra(extra);}}
        out.putNextEntry(next);try(InputStream in=template.getInputStream(old)){copy(in,out);}out.closeEntry();
      }
      File[] files=dex.listFiles();if(files==null)throw new IOException("No compiled DEX");for(File f:files)if(f.getName().endsWith(".dex")){out.putNextEntry(new ZipEntry(f.getName()));try(InputStream in=new FileInputStream(f)){copy(in,out);}out.closeEntry();}
    }
    check(progress);progress.update("Sign APK",90,log.toString());File output=new File(data,"outputs/oTraiL-phone-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".apk");output.getParentFile().mkdirs();File part=new File(work,"signed.apk");
    ApkSigner.SignerConfig signer=new ApkSigner.SignerConfig.Builder("otrail",privateKey,Collections.singletonList(certificate)).build();
    new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(unsigned).setOutputApk(part).setMinSdkVersion(30).setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).build().sign();
    check(progress);Files.move(part.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);progress.update("APK ready",100,log.append("\n").append(output.getName()).append("\nSHA256: ").append(hash(output)).append("\nResources/native libraries retained from verified template.\n").toString());
  }
  private static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
  private static final class CountingStream extends FilterOutputStream{long count;CountingStream(OutputStream out){super(out);}public void write(int b)throws IOException{out.write(b);count++;}public void write(byte[] b,int off,int length)throws IOException{out.write(b,off,length);count+=length;}}
}
